package org.ntrloc.graph.draft;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.ntrloc.graph.AbstractIntegrationTest;
import org.ntrloc.graph.db.partition.security.UserService;
import org.ntrloc.graph.db.partition.security.repository.UserGroupStore;
import org.ntrloc.graph.db.partition.security.repository.UserGroupStore.UserGroupRow;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

// Group and membership changes live has put in question (docs/ntrloc-system-model-notes.md §13.4.1, plan
// step 2.5): conflicts, settled by keeping the draft's change or live's; changes no longer valid, settled
// by editing or abandoning them; and the cases that aren't either. Apply waits for all of them.
class DraftGroupConflictsIntegrationTest extends AbstractIntegrationTest {

    private static final String ADMIN = "root@example.com";

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private UserService userService;

    @Autowired
    private UserGroupStore userGroupStore;

    private String draftId;
    private UUID root;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void startADraft() {
        discardOpenDrafts();
        root = userGroupStore.findUserGroupByName("everyone").orElseThrow().id();
        draftId = (String) asAdmin(webTestClient.post().uri("/api/drafts").bodyValue(Map.of("name", "Group conflicts test")))
                .exchange().expectStatus().isCreated().expectBody(Map.class).returnResult().getResponseBody().get("id");
    }

    @AfterEach
    @SuppressWarnings("unchecked")
    void discardOpenDrafts() {
        List<Map<String, Object>> open = (List<Map<String, Object>>) (List<?>) asAdmin(webTestClient.get().uri("/api/drafts"))
                .exchange().expectStatus().isOk().expectBodyList(Map.class).returnResult().getResponseBody();
        for (var draft : open) {
            asAdmin(webTestClient.post().uri("/api/drafts/" + draft.get("id") + "/discard")).exchange().expectStatus().isOk();
        }
    }

    // --- Helpers ---

    private WebTestClient.RequestHeadersSpec<?> asAdmin(WebTestClient.RequestHeadersSpec<?> request) {
        return request.header(HttpHeaders.AUTHORIZATION, bearerTokenFor(ADMIN));
    }

    private String draft() {
        return "/api/drafts/" + draftId + "/admin";
    }

    private static String unique(String name) {
        return name + "-" + UUID.randomUUID();
    }

    private UUID liveGroup(String name, UUID parent) {
        return userService.createUserGroup(name, parent).id();
    }

    private UserGroupRow live(UUID group) {
        return userGroupStore.findUserGroupById(group).orElseThrow();
    }

    private UUID freshUser() {
        return userService.createUser("Conflicts User", unique("conflicts") + "@example.com", false).id();
    }

    @SuppressWarnings("unchecked")
    private UUID createInDraft(String name, UUID parent) {
        Map<String, Object> created = asAdmin(webTestClient.post().uri(draft() + "/user-groups").bodyValue(Map.of("name", name, "parentUserGroupId", parent)))
                .exchange().expectStatus().isOk().expectBody(Map.class).returnResult().getResponseBody();
        return UUID.fromString((String) created.get("id"));
    }

    private void rename(UUID group, String name) {
        asAdmin(webTestClient.put().uri(draft() + "/user-groups/" + group).bodyValue(Map.of("name", name))).exchange().expectStatus().isOk();
    }

    private void move(UUID group, UUID parent) {
        asAdmin(webTestClient.put().uri(draft() + "/user-groups/" + group + "/parent").bodyValue(Map.of("parentUserGroupId", parent)))
                .exchange().expectStatus().isOk();
    }

    private void delete(UUID group) {
        asAdmin(webTestClient.delete().uri(draft() + "/user-groups/" + group + "?force=true")).exchange().expectStatus().isNoContent();
    }

    private void addMember(UUID group, UUID user) {
        asAdmin(webTestClient.post().uri(draft() + "/user-groups/" + group + "/members").bodyValue(Map.of("userId", user)))
                .exchange().expectStatus().isNoContent();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> changes() {
        return asAdmin(webTestClient.get().uri("/api/drafts/" + draftId + "/changes")).exchange().expectStatus().isOk()
                .expectBody(Map.class).returnResult().getResponseBody();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> groupChange(UUID group) {
        return ((List<Map<String, Object>>) changes().get("groups")).stream().filter(c -> c.get("groupId").equals(group.toString()))
                .findFirst().orElse(null);
    }

    /** The reasons a group change needs reconciling. */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> reasons(UUID group) {
        var change = groupChange(group);
        return change == null ? List.of() : (List<Map<String, Object>>) change.get("reconcile");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> onlyReason(UUID group) {
        assertThat(reasons(group)).hasSize(1);
        return reasons(group).getFirst();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> membershipReasons(UUID group, UUID user) {
        return ((List<Map<String, Object>>) changes().get("memberships")).stream()
                .filter(m -> m.get("groupId").equals(group.toString()) && m.get("userId").equals(user.toString()))
                .findFirst().map(m -> (List<Map<String, Object>>) m.get("reconcile")).orElseThrow();
    }

    @SuppressWarnings("unchecked")
    private List<String> noticeKeys() {
        return ((List<Map<String, Object>>) changes().get("notices")).stream()
                .map(n -> (String) ((Map<String, Object>) n.get("latest")).get("elementKey")).toList();
    }

    private WebTestClient.ResponseSpec reconcile(UUID group, String field, String resolution) {
        Map<String, Object> body = new HashMap<>();
        body.put("groupId", group);
        body.put("field", field);
        body.put("resolution", resolution);
        return asAdmin(webTestClient.post().uri("/api/drafts/" + draftId + "/reconcile").bodyValue(body)).exchange();
    }

    private void settle(UUID group, String field, String resolution) {
        reconcile(group, field, resolution).expectStatus().isNoContent();
    }

    private void apply() {
        asAdmin(webTestClient.post().uri("/api/drafts/" + draftId + "/apply")).exchange().expectStatus().isOk();
    }

    @SuppressWarnings("unchecked")
    private String applyRefused() {
        return (String) asAdmin(webTestClient.post().uri("/api/drafts/" + draftId + "/apply")).exchange().expectStatus().isEqualTo(409)
                .expectBody(Map.class).returnResult().getResponseBody().get("message");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> side(Map<String, Object> reason, String which) {
        return (Map<String, Object>) reason.get(which);
    }

    // --- The P1/P2/P3 example: both sides moved A ---

    private record Moves(UUID a, UUID p1, UUID p2, UUID p3) {}

    // A under P1; the draft moves A to P2; then live moves it to P3.
    private Moves bothMoveA() {
        UUID p1 = liveGroup(unique("P1"), root);
        UUID p2 = liveGroup(unique("P2"), root);
        UUID p3 = liveGroup(unique("P3"), root);
        UUID a = liveGroup(unique("A"), p1);
        move(a, p2);
        userService.setParent(a, p3);
        return new Moves(a, p1, p2, p3);
    }

    @Test
    void bothSidesMovingAGroup_isAConflict_showingBothSidesAndWhereItStarted() {
        var m = bothMoveA();

        var reason = onlyReason(m.a());
        assertThat(reason).containsEntry("kind", "CONFLICT").containsEntry("field", "parent")
                .containsEntry("choices", List.of("KEEP", "USE_LIVE"));
        assertThat(side(reason, "draft")).containsEntry("id", m.p2().toString());
        assertThat(side(reason, "live")).containsEntry("id", m.p3().toString());
        assertThat(side(reason, "base")).containsEntry("id", m.p1().toString()).containsEntry("name", live(m.p1()).name());
        assertThat(applyRefused()).isEqualTo("1 change needs reconciling with live before the draft can be applied: 1 in groups");
    }

    @Test
    void keepingTheDraftsMove_movesTheGroupFromWhereLiveHasIt() {
        var m = bothMoveA();

        settle(m.a(), "parent", "KEEP");

        assertThat(reasons(m.a())).isEmpty();
        assertThat(groupChange(m.a())).containsEntry("baseParentId", m.p3().toString());
        apply();
        assertThat(live(m.a()).parentId()).isEqualTo(m.p2());
    }

    @Test
    void preservingLivesMove_dropsTheDrafts() {
        var m = bothMoveA();

        settle(m.a(), "parent", "USE_LIVE");

        assertThat(groupChange(m.a())).isNull();
        apply();
        assertThat(live(m.a()).parentId()).isEqualTo(m.p3());
    }

    @Test
    void movingTheGroupAgainInTheDraft_settlesTheConflict() {
        var m = bothMoveA();
        UUID p4 = liveGroup(unique("P4"), root);

        move(m.a(), p4);

        assertThat(reasons(m.a())).isEmpty();
        apply();
        assertThat(live(m.a()).parentId()).isEqualTo(p4);
    }

    @Test
    void settlingAConflict_acknowledgesTheLiveChangeBehindIt() {
        var m = bothMoveA();
        String key = "group:" + m.a();

        // While the conflict stands, live's move is shown with it, not as a notice of its own.
        assertThat(noticeKeys()).doesNotContain(key);
        @SuppressWarnings("unchecked")
        var behind = (List<Map<String, Object>>) onlyReason(m.a()).get("liveChanges");
        assertThat(behind).singleElement().satisfies(n -> assertThat(n.get("latest")).asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsEntry("kind", "group.moved").containsEntry("elementKey", key));

        settle(m.a(), "parent", "KEEP");

        assertThat(noticeKeys()).doesNotContain(key);
    }

    // --- Not conflicts ---

    @Test
    void liveMakingTheSameMove_leavesTheDraftsRedundant() {
        UUID p2 = liveGroup(unique("P2"), root);
        UUID a = liveGroup(unique("A"), liveGroup(unique("P1"), root));
        move(a, p2);

        userService.setParent(a, p2);

        assertThat(groupChange(a)).isNull();
    }

    @Test
    void liveMovingTheGroupAwayAndBack_leavesTheDraftsMoveToApply() {
        UUID p1 = liveGroup(unique("P1"), root);
        UUID p2 = liveGroup(unique("P2"), root);
        UUID a = liveGroup(unique("A"), p1);
        move(a, p2);

        userService.setParent(a, liveGroup(unique("P3"), root));
        userService.setParent(a, p1);

        assertThat(reasons(a)).isEmpty();
        apply();
        assertThat(live(a).parentId()).isEqualTo(p2);
    }

    // --- No longer valid ---

    @Test
    void liveDeletingTheNewParent_leavesTheMoveNoLongerValid_untilAnotherParentIsChosen() {
        UUID p1 = liveGroup(unique("P1"), root);
        UUID p2 = liveGroup(unique("P2"), root);
        UUID a = liveGroup(unique("A"), p1);
        move(a, p2);
        String p2Name = live(p2).name();

        userService.deleteUserGroup(p2, false);

        var reason = onlyReason(a);
        assertThat(reason).containsEntry("kind", "PARENT_GONE").containsEntry("field", "parent").containsEntry("choices", List.of("ABANDON"));
        assertThat(side(reason, "draft")).containsEntry("id", p2.toString()).containsEntry("name", p2Name);
        reconcile(a, "parent", "KEEP").expectStatus().isEqualTo(409);
        applyRefused();

        UUID p3 = liveGroup(unique("P3"), root);
        move(a, p3);
        apply();
        assertThat(live(a).parentId()).isEqualTo(p3);
    }

    @Test
    void liveDeletingTheGroup_leavesAnOrphan_thatCanOnlyBeDropped() {
        UUID a = liveGroup(unique("A"), root);
        move(a, liveGroup(unique("P2"), root));

        userService.deleteUserGroup(a, false);

        assertThat(onlyReason(a)).containsEntry("kind", "GROUP_GONE").containsEntry("choices", List.of("USE_LIVE"));
        settle(a, null, "USE_LIVE");
        assertThat(groupChange(a)).isNull();
        apply();
    }

    @Test
    void liveMovingTheNewParentUnderTheGroup_makesACycle_thatCanBeAbandoned() {
        UUID a = liveGroup(unique("A"), root);
        UUID p2 = liveGroup(unique("P2"), root);
        move(a, p2);

        userService.setParent(p2, a);

        assertThat(onlyReason(a)).containsEntry("kind", "CYCLE").containsEntry("field", "parent");
        settle(a, "parent", "ABANDON");
        assertThat(groupChange(a)).isNull();
        apply();
        assertThat(live(p2).parentId()).isEqualTo(a);
    }

    @Test
    void aNameLiveHasSinceTaken_canBeChangedInTheDraft() {
        UUID g = liveGroup(unique("G"), root);
        String wanted = unique("wanted");
        rename(g, wanted);

        UUID taker = liveGroup(wanted, root);

        var reason = onlyReason(g);
        assertThat(reason).containsEntry("kind", "NAME_TAKEN").containsEntry("field", "name");
        assertThat(side(reason, "live")).containsEntry("id", taker.toString());
        String other = unique("other");
        rename(g, other);
        apply();
        assertThat(live(g).name()).isEqualTo(other);
    }

    @Test
    void aNewGroupWhoseNameLiveHasTaken_canBeAbandoned_withItsSubgroups() {
        String wanted = unique("new");
        UUID created = createInDraft(wanted, root);
        UUID below = createInDraft(unique("below"), created);

        liveGroup(wanted, root);

        assertThat(onlyReason(created)).containsEntry("kind", "NAME_TAKEN").containsEntry("field", "created");
        settle(created, "created", "ABANDON");
        assertThat(groupChange(created)).isNull();
        assertThat(groupChange(below)).isNull();
        apply();
    }

    @Test
    void aNewGroupWhoseParentLiveDeleted_isNoLongerValid() {
        UUID parent = liveGroup(unique("parent"), root);
        UUID created = createInDraft(unique("new"), parent);

        userService.deleteUserGroup(parent, false);

        assertThat(onlyReason(created)).containsEntry("kind", "PARENT_GONE").containsEntry("field", "created");
        move(created, root);
        assertThat(reasons(created)).isEmpty();
        apply();
        assertThat(live(created).parentId()).isEqualTo(root);
    }

    // --- Both sides renamed ---

    @Test
    void bothSidesRenamingAGroup_isAConflict_andKeepingTheDraftsNameAppliesIt() {
        String start = unique("start");
        UUID g = liveGroup(start, root);
        String drafted = unique("drafted");
        rename(g, drafted);
        String livesName = unique("lives");

        userService.renameUserGroup(g, livesName);

        var reason = onlyReason(g);
        assertThat(reason).containsEntry("kind", "CONFLICT").containsEntry("field", "name");
        assertThat(side(reason, "draft")).containsEntry("name", drafted);
        assertThat(side(reason, "live")).containsEntry("name", livesName);
        assertThat(side(reason, "base")).containsEntry("name", start);
        settle(g, "name", "KEEP");
        apply();
        assertThat(live(g).name()).isEqualTo(drafted);
    }

    @Test
    void aGroupWithBothItsNameAndParentInConflict_hasTwoItems_settledEachOnItsOwn() {
        var m = bothMoveA();
        rename(m.a(), unique("drafted"));
        userService.renameUserGroup(m.a(), unique("lives"));

        assertThat(reasons(m.a())).extracting(r -> r.get("field")).containsExactlyInAnyOrder("name", "parent");
        settle(m.a(), "name", "USE_LIVE");
        assertThat(onlyReason(m.a())).containsEntry("field", "parent");
    }

    // --- A group the draft deletes ---

    @Test
    void liveRenamingAGroupTheDraftDeletes_isAConflict_keptByDeletingIt() {
        UUID g = liveGroup(unique("doomed"), root);
        delete(g);

        userService.renameUserGroup(g, unique("renamed-in-live"));

        assertThat(onlyReason(g)).containsEntry("kind", "CHANGED_IN_LIVE").containsEntry("field", "deleted")
                .containsEntry("choices", List.of("KEEP", "USE_LIVE"));
        settle(g, "deleted", "KEEP");
        assertThat(reasons(g)).isEmpty();
        apply();
        assertThat(userGroupStore.findUserGroupById(g)).isEmpty();
    }

    @Test
    void liveAddingAMemberToAGroupTheDraftDeletes_isAConflict_preservedByRestoringIt() {
        UUID g = liveGroup(unique("doomed"), root);
        delete(g);

        userService.addMember(freshUser(), g);

        assertThat(onlyReason(g)).containsEntry("kind", "CHANGED_IN_LIVE");
        settle(g, "deleted", "USE_LIVE");
        assertThat(groupChange(g)).isNull();
        apply();
        assertThat(userGroupStore.findUserGroupById(g)).isPresent();
    }

    @Test
    void liveAddingASubgroupUnderAGroupTheDraftDeletes_isAConflict_settledByDeletingItToo() {
        UUID g = liveGroup(unique("doomed"), root);
        delete(g);

        UUID sub = liveGroup(unique("new-in-live"), g);

        var reason = onlyReason(g);
        assertThat(reason).containsEntry("kind", "NEW_SUBGROUPS").containsEntry("choices", List.of("DELETE_SUBGROUPS", "USE_LIVE"));
        assertThat(reason.get("groups")).asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.LIST)
                .singleElement().asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP).containsEntry("id", sub.toString());
        applyRefused();

        settle(g, "deleted", "DELETE_SUBGROUPS");
        assertThat(reasons(g)).isEmpty();
        apply();
        assertThat(userGroupStore.findUserGroupById(g)).isEmpty();
        assertThat(userGroupStore.findUserGroupById(sub)).isEmpty();
    }

    @Test
    void preservingLivesNewSubgroup_restoresTheDeletedGroupsAboveIt() {
        UUID top = liveGroup(unique("top"), root);
        UUID middle = liveGroup(unique("middle"), top);
        delete(top);

        UUID sub = liveGroup(unique("new-in-live"), middle);

        settle(middle, "deleted", "USE_LIVE");

        assertThat(groupChange(top)).isNull();
        assertThat(groupChange(middle)).isNull();
        apply();
        assertThat(live(sub).parentId()).isEqualTo(middle);
        assertThat(live(middle).parentId()).isEqualTo(top);
    }

    // --- Memberships ---

    @Test
    void addingAUserLiveDeleted_isAnOrphan_droppedByPreservingLive() {
        UUID g = liveGroup(unique("team"), root);
        UUID user = freshUser();
        addMember(g, user);

        userService.deleteUser(user);

        assertThat(membershipReasons(g, user)).singleElement().satisfies(r -> assertThat(r).containsEntry("kind", "USER_GONE"));
        applyRefused();
        asAdmin(webTestClient.post().uri("/api/drafts/" + draftId + "/reconcile")
                .bodyValue(Map.of("groupId", g, "userId", user, "resolution", "USE_LIVE"))).exchange().expectStatus().isNoContent();
        apply();
    }

    @Test
    void addingToAGroupLiveDeleted_isAnOrphan() {
        UUID g = liveGroup(unique("team"), root);
        UUID user = freshUser();
        addMember(g, user);

        userService.deleteUserGroup(g, false);

        assertThat(membershipReasons(g, user)).singleElement().satisfies(r -> assertThat(r).containsEntry("kind", "GROUP_GONE"));
    }

    // --- Apply ---

    @Test
    void applyingSwappedNames_andANameFreedByADelete_succeeds() {
        String nameA = unique("A");
        String nameB = unique("B");
        String nameC = unique("C");
        UUID a = liveGroup(nameA, root);
        UUID b = liveGroup(nameB, root);
        UUID c = liveGroup(nameC, root);
        String spare = unique("spare");
        rename(b, spare);
        rename(a, nameB);
        rename(b, nameA);
        delete(c);
        rename(liveGroup(unique("D"), root), nameC);

        apply();

        assertThat(live(a).name()).isEqualTo(nameB);
        assertThat(live(b).name()).isEqualTo(nameA);
        assertThat(userGroupStore.findUserGroupByName(nameC)).isPresent().get().extracting(UserGroupRow::id).isNotEqualTo(c);
    }

    @Test
    void aChoiceTheItemDoesntOffer_isRefused() {
        var m = bothMoveA();

        reconcile(m.a(), "parent", "DELETE_SUBGROUPS").expectStatus().isEqualTo(409);
        reconcile(m.a(), "name", "KEEP").expectStatus().isEqualTo(409);
    }
}
