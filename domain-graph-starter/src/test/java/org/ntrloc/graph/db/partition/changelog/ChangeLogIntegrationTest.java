package org.ntrloc.graph.db.partition.changelog;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.ntrloc.graph.AbstractIntegrationTest;
import org.ntrloc.graph.db.coordinator.CoordinatorTestDomainInitializer;
import org.ntrloc.graph.db.partition.authorization.AccessService;
import org.ntrloc.graph.db.partition.authorization.repository.GrantStoreReader;
import org.ntrloc.graph.db.partition.authorization.repository.Permission;
import org.ntrloc.graph.db.partition.changelog.ChangeEntries.Entry;
import org.ntrloc.graph.db.partition.security.UserService;
import org.ntrloc.graph.db.partition.security.repository.UserStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// The live changelog (docs/ntrloc-system-model-notes.md §13.6): every change to users, groups, memberships
// and grants leaves exactly one entry, in its own transaction, saying who or what made it.
class ChangeLogIntegrationTest extends AbstractIntegrationTest {

    private static final String ADMIN = "root@example.com";

    @Autowired
    private ChangeLog changeLog;

    @Autowired
    private ChangeLogRetention retention;

    @Autowired
    private UserService userService;

    @Autowired
    private UserStore userStore;

    @Autowired
    private AccessService accessService;

    @Autowired
    private CoordinatorTestDomainInitializer fixture;

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private JdbcClient jdbcClient;

    private long start;

    @BeforeEach
    void notePosition() {
        start = changeLog.latestPosition();
    }

    @AfterEach
    void keepEverything() {
        retention.setRetentionDays(null);
    }

    private List<Entry> since() {
        return changeLog.entriesAfter(start);
    }

    private List<String> kinds() {
        return since().stream().map(Entry::kind).toList();
    }

    private UUID root() {
        return userStore.findUserGroupByName("everyone").orElseThrow().id();
    }

    private UUID group(String name) {
        return userService.createUserGroup(name + "-" + UUID.randomUUID(), root()).id();
    }

    private Permission createProducts() {
        return Permission.itemType(fixture.productTypeId(), "create");
    }

    @Test
    void creatingRenamingAndMovingAGroup_eachLeaveOneEntry_describingIt() {
        UUID oldParent = group("old");
        UUID newParent = group("new");
        start = changeLog.latestPosition();

        UUID g = userService.createUserGroup("logged-" + UUID.randomUUID(), oldParent).id();
        userService.renameUserGroup(g, "renamed-" + g);
        userService.setParent(g, newParent);

        assertThat(kinds()).containsExactly("group.created", "group.renamed", "group.moved");
        Entry moved = since().getLast();
        assertThat(moved.elementKey()).isEqualTo("group:" + g);
        assertThat(moved.before()).containsEntry("parentId", oldParent.toString());
        assertThat(moved.after()).containsEntry("name", "renamed-" + g).containsEntry("parentId", newParent.toString());
        assertThat(moved.refs()).containsExactlyInAnyOrder(g, oldParent, newParent);
    }

    @Test
    void writesThatChangeNothing_leaveNoEntry() {
        UUID g = group("unchanged");
        UUID user = userService.createUser("Member", "member-" + UUID.randomUUID() + "@example.com", false).id();
        userService.addMember(user, g);
        start = changeLog.latestPosition();

        userService.addMember(user, g);
        userService.renameUserGroup(g, userStore.findUserGroupById(g).orElseThrow().name());
        userService.setParent(g, root());

        assertThat(since()).isEmpty();
    }

    @Test
    void membershipChanges_nameTheUserAndTheGroup() {
        UUID g = group("members");
        UUID user = userService.createUser("Pat", "pat-" + UUID.randomUUID() + "@example.com", false).id();
        start = changeLog.latestPosition();

        userService.addMember(user, g);
        userService.removeMember(user, g);

        assertThat(kinds()).containsExactly("membership.added", "membership.removed");
        assertThat(since().getFirst().after()).containsEntry("userName", "Pat").containsEntry("groupId", g.toString());
        assertThat(since().getLast().before()).containsEntry("userName", "Pat");
    }

    @Test
    void aChangeMadeThroughTheApi_recordsWhoMadeIt() {
        UUID g = group("api");
        start = changeLog.latestPosition();

        webTestClient.put().uri("/api/admin/user-groups/" + g).header(HttpHeaders.AUTHORIZATION, bearerTokenFor(ADMIN))
                .bodyValue(Map.of("name", "via-api-" + g)).exchange().expectStatus().isOk();

        assertThat(since()).singleElement().satisfies(e -> {
            assertThat(e.actorId()).isEqualTo(userStore.findUserByEmail(ADMIN).orElseThrow().id());
            assertThat(e.actorName()).isEqualTo("Root (admin)");
            assertThat(e.source()).isNull();
        });
    }

    @Test
    void workOutsideARequest_isTheSystems_withItsSource() {
        ChangeLog.withSource("a test", () -> group("system"));

        assertThat(since()).singleElement().satisfies(e -> {
            assertThat(e.actorId()).isNull();
            assertThat(e.source()).isEqualTo("a test");
        });
    }

    @Test
    void settingAPermission_leavesOneEntry_evenThoughItWritesSeveralRows() {
        UUID g = group("granted");
        UUID marker = accessService.createMarker("log-" + UUID.randomUUID(), "", "ITEM_TYPE", fixture.productTypeId()).id();
        start = changeLog.latestPosition();

        accessService.setPermission("USER_GROUP", g, Permission.forMarker(marker, Permission.AREA_PROPERTY, fixture.namePropertyId(), "read"), true);
        accessService.setPermission("USER_GROUP", g, createProducts(), true);
        accessService.setPermission("USER_GROUP", g, createProducts(), false);

        assertThat(kinds()).containsExactly("grant.granted", "grant.granted", "grant.revoked");
        assertThat(since().getFirst().refs()).contains(g, marker, fixture.namePropertyId());
    }

    @Test
    void theOlderPerAreaGrantWrites_areLoggedToo() {
        UUID g = group("legacy");
        start = changeLog.latestPosition();

        accessService.grantItemType(fixture.productTypeId(), "USER_GROUP", g, GrantStoreReader.ITEM_TYPE_READ);

        assertThat(since()).singleElement().satisfies(e -> {
            assertThat(e.kind()).isEqualTo("grant.granted");
            assertThat(e.elementKey()).startsWith("grant:USER_GROUP:" + g + ":itemType|");
        });
    }

    @Test
    void deletingAUser_isOneEntry_listingTheirGroupsAndGrants() {
        UUID g = group("former");
        UUID user = userService.createUser("Leaving", "leaving-" + UUID.randomUUID() + "@example.com", false).id();
        userService.addMember(user, g);
        accessService.setPermission("USER", user, createProducts(), true);
        start = changeLog.latestPosition();

        userService.deleteUser(user);

        assertThat(since()).singleElement().satisfies(e -> {
            assertThat(e.kind()).isEqualTo("user.deleted");
            assertThat(e.before()).containsEntry("displayName", "Leaving");
            assertThat((List<?>) e.before().get("groups")).hasSize(1);
            assertThat((List<?>) e.before().get("grants")).hasSize(1);
        });
    }

    @Test
    void forcingAGroupDelete_isOneEntryPerGroup() {
        UUID parent = group("doomed");
        userService.createUserGroup("doomed-child-" + UUID.randomUUID(), parent);
        start = changeLog.latestPosition();

        userService.deleteUserGroup(parent, true);

        assertThat(kinds()).containsExactly("group.deleted", "group.deleted");
    }

    @Test
    void deletingAMarker_isOneEntry_listingTheGrantsThatWentWithIt() {
        UUID g = group("marker-holder");
        UUID marker = accessService.createMarker("doomed-" + UUID.randomUUID(), "", "ITEM_TYPE", fixture.productTypeId()).id();
        accessService.setPermission("USER_GROUP", g, Permission.forMarker(marker, Permission.AREA_ITEM, null, "read"), true);
        start = changeLog.latestPosition();

        accessService.deleteMarker(marker);

        assertThat(since()).singleElement().satisfies(e -> {
            assertThat(e.kind()).isEqualTo("marker.deleted");
            assertThat((List<?>) e.before().get("grants")).hasSize(1);
        });
    }

    @Test
    void aChangeThatRollsBack_leavesNoEntry() {
        String name = "rolled-back-" + UUID.randomUUID();

        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(_ -> {
            userService.createUserGroup(name, root());
            throw new IllegalStateException("roll back");
        })).hasMessage("roll back");

        assertThat(since()).isEmpty();
        assertThat(userStore.findUserGroupByName(name)).isEmpty();
    }

    @Test
    void pruning_removesOldEntries_butNotWhatAnOpenDraftNeeds() {
        retention.setRetentionDays(1);
        group("old-and-unneeded");
        long draftStart = changeLog.latestPosition();
        group("old-but-needed");
        jdbcClient.sql("UPDATE config_change_log SET at = now() - interval '2 days' WHERE seq > :start").param("start", start).update();
        UUID draft = jdbcClient.sql("INSERT INTO draft (name, changelog_position) VALUES ('Pruning test', :position) RETURNING id")
                .param("position", draftStart).query(UUID.class).single();
        try {
            retention.prune();

            assertThat(since()).extracting(Entry::seq).allMatch(seq -> seq > draftStart).isNotEmpty();
        } finally {
            jdbcClient.sql("DELETE FROM draft WHERE id = :id").param("id", draft).update();
        }
    }

    @Test
    void withNoRetentionSet_nothingIsPruned() {
        group("kept");
        jdbcClient.sql("UPDATE config_change_log SET at = now() - interval '400 days' WHERE seq > :start").param("start", start).update();

        assertThat(retention.prune()).isZero();
        assertThat(since()).hasSize(1);
    }

    @Test
    void aNewDraft_startsAtTheLatestPosition() {
        group("before-the-draft");
        long position = changeLog.latestPosition();
        @SuppressWarnings("unchecked")
        Map<String, Object> draft = webTestClient.post().uri("/api/drafts").header(HttpHeaders.AUTHORIZATION, bearerTokenFor(ADMIN))
                .bodyValue(Map.of("name", "Position test")).exchange().expectStatus().isCreated()
                .expectBody(Map.class).returnResult().getResponseBody();
        try {
            assertThat(jdbcClient.sql("SELECT changelog_position FROM draft WHERE id = :id").param("id", UUID.fromString((String) draft.get("id")))
                    .query(Long.class).single()).isEqualTo(position);
        } finally {
            webTestClient.post().uri("/api/drafts/" + draft.get("id") + "/discard").header(HttpHeaders.AUTHORIZATION, bearerTokenFor(ADMIN))
                    .exchange().expectStatus().isOk();
        }
    }

}
