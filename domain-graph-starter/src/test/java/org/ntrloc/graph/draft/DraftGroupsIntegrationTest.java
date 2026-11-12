package org.ntrloc.graph.draft;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.ntrloc.graph.AbstractIntegrationTest;
import org.ntrloc.graph.db.coordinator.CoordinatorTestDomainInitializer;
import org.ntrloc.graph.db.partition.authorization.AccessService;
import org.ntrloc.graph.db.partition.authorization.repository.GrantStoreReader;
import org.ntrloc.graph.db.partition.authorization.repository.Permission;
import org.ntrloc.graph.db.partition.security.UserService;
import org.ntrloc.graph.db.partition.security.repository.UserGroupStore;
import org.ntrloc.graph.db.partition.security.repository.UserStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

// Groups and memberships changed in a draft (docs/ntrloc-drafts-plan.md step 2.4): seen in the draft,
// not in live, until the draft is applied. Each test works on fresh groups and users.
class DraftGroupsIntegrationTest extends AbstractIntegrationTest {

    private static final String ADMIN = "root@example.com";
    private static final String PRODUCT = "CoordinatorTestProduct";

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private UserService userService;

    @Autowired
    private UserStore userStore;

    @Autowired
    private UserGroupStore userGroupStore;

    @Autowired
    private AccessService accessService;

    @Autowired
    private GrantStoreReader liveGrants;

    @Autowired
    private CoordinatorTestDomainInitializer fixture;

    private String draftId;
    private UUID root;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void startADraft() {
        discardOpenDrafts();
        root = userGroupStore.findUserGroupByName("everyone").orElseThrow().id();
        draftId = (String) webTestClient.post().uri("/api/drafts").header(HttpHeaders.AUTHORIZATION, bearerTokenFor(ADMIN))
                .bodyValue(Map.of("name", "Draft groups test")).exchange().expectStatus().isCreated()
                .expectBody(Map.class).returnResult().getResponseBody().get("id");
    }

    @AfterEach
    @SuppressWarnings("unchecked")
    void discardOpenDrafts() {
        List<Map<String, Object>> open = (List<Map<String, Object>>) (List<?>) webTestClient.get().uri("/api/drafts")
                .header(HttpHeaders.AUTHORIZATION, bearerTokenFor(ADMIN)).exchange().expectStatus().isOk()
                .expectBodyList(Map.class).returnResult().getResponseBody();
        for (var draft : open) {
            webTestClient.post().uri("/api/drafts/" + draft.get("id") + "/discard").header(HttpHeaders.AUTHORIZATION, bearerTokenFor(ADMIN))
                    .exchange().expectStatus().isOk();
        }
    }

    // --- Helpers ---

    private String draft() {
        return "/api/drafts/" + draftId + "/admin";
    }

    private static String unique(String name) {
        return name + "-" + UUID.randomUUID();
    }

    private UUID liveGroup(String name, UUID parent) {
        return userService.createUserGroup(name, parent).id();
    }

    private WebTestClient.RequestHeadersSpec<?> asAdmin(WebTestClient.RequestHeadersSpec<?> request) {
        return request.header(HttpHeaders.AUTHORIZATION, bearerTokenFor(ADMIN));
    }

    @SuppressWarnings("unchecked")
    private UUID createInDraft(String name, UUID parent) {
        Map<String, Object> created = asAdmin(webTestClient.post().uri(draft() + "/user-groups").bodyValue(Map.of("name", name, "parentUserGroupId", parent)))
                .exchange().expectStatus().isOk().expectBody(Map.class).returnResult().getResponseBody();
        return UUID.fromString((String) created.get("id"));
    }

    private WebTestClient.ResponseSpec rename(UUID group, String name) {
        return asAdmin(webTestClient.put().uri(draft() + "/user-groups/" + group).bodyValue(Map.of("name", name))).exchange();
    }

    private WebTestClient.ResponseSpec move(UUID group, UUID parent) {
        return asAdmin(webTestClient.put().uri(draft() + "/user-groups/" + group + "/parent").bodyValue(Map.of("parentUserGroupId", parent))).exchange();
    }

    private WebTestClient.ResponseSpec delete(UUID group, boolean force) {
        return asAdmin(webTestClient.delete().uri(draft() + "/user-groups/" + group + "?force=" + force)).exchange();
    }

    private WebTestClient.ResponseSpec addMember(UUID group, UUID user) {
        return asAdmin(webTestClient.post().uri(draft() + "/user-groups/" + group + "/members").bodyValue(Map.of("userId", user))).exchange();
    }

    private WebTestClient.ResponseSpec removeMember(UUID group, UUID user) {
        return asAdmin(webTestClient.delete().uri(draft() + "/user-groups/" + group + "/members/" + user)).exchange();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Map<String, Object>> draftGroupsByName() {
        List<Map<String, Object>> groups = (List<Map<String, Object>>) (List<?>) asAdmin(webTestClient.get().uri(draft() + "/user-groups"))
                .exchange().expectStatus().isOk().expectBodyList(Map.class).returnResult().getResponseBody();
        Map<String, Map<String, Object>> byName = new java.util.HashMap<>();
        groups.forEach(g -> byName.put((String) g.get("name"), g));
        return byName;
    }

    @SuppressWarnings("unchecked")
    private List<String> draftMemberEmails(UUID group) {
        List<Map<String, Object>> members = (List<Map<String, Object>>) (List<?>) asAdmin(webTestClient.get().uri(draft() + "/user-groups/" + group + "/members"))
                .exchange().expectStatus().isOk().expectBodyList(Map.class).returnResult().getResponseBody();
        return members.stream().map(m -> (String) m.get("email")).toList();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> changes() {
        return asAdmin(webTestClient.get().uri("/api/drafts/" + draftId + "/changes")).exchange().expectStatus().isOk()
                .expectBody(Map.class).returnResult().getResponseBody();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> groupChanges() {
        return (List<Map<String, Object>>) changes().get("groups");
    }

    @SuppressWarnings("unchecked")
    private List<String> draftLogKinds() {
        List<Map<String, Object>> entries = (List<Map<String, Object>>) (List<?>) asAdmin(webTestClient.get().uri("/api/drafts/" + draftId + "/log"))
                .exchange().expectStatus().isOk().expectBodyList(Map.class).returnResult().getResponseBody();
        return entries.stream().map(e -> (String) e.get("kind")).toList();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> apply() {
        return asAdmin(webTestClient.post().uri("/api/drafts/" + draftId + "/apply")).exchange().expectStatus().isOk()
                .expectBody(Map.class).returnResult().getResponseBody();
    }

    private UserStore.UserRow freshUser() {
        return userService.createUser("Draft Groups User", unique("draft-groups") + "@example.com", false);
    }

    private Permission productRead() {
        return new Permission(Permission.ITEM_TYPE, fixture.productTypeId(), null, null, null, "read");
    }

    private void grantInDraft(UUID group, Permission permission) {
        Map<String, Object> p = new java.util.HashMap<>();
        p.put("kind", permission.kind());
        p.put("itemTypeId", permission.itemTypeId());
        p.put("op", permission.op());
        asAdmin(webTestClient.put().uri(draft() + "/grants")
                .bodyValue(Map.of("principal", Map.of("kind", "group", "id", group), "permission", p, "value", true)))
                .exchange().expectStatus().isNoContent();
    }

    // --- Creating ---

    @Test
    void aGroupCreatedInTheDraft_isThereOnlyInTheDraft_untilApplied_andKeepsItsId() {
        String name = unique("created");
        UUID id = createInDraft(name, root);

        assertThat(draftGroupsByName()).containsKey(name);
        assertThat(userGroupStore.findUserGroupById(id)).isEmpty();
        assertThat(groupChanges()).singleElement().satisfies(c -> assertThat(c).containsEntry("created", true).containsEntry("name", name));

        apply();

        assertThat(userGroupStore.findUserGroupById(id)).hasValueSatisfying(g -> {
            assertThat(g.name()).isEqualTo(name);
            assertThat(g.parentId()).isEqualTo(root);
        });
    }

    @Test
    void aGrantAndAMemberGivenToADraftGroup_goWithItToLive() {
        UUID id = createInDraft(unique("granted"), root);
        var user = freshUser();
        grantInDraft(id, productRead());
        addMember(id, user.id()).expectStatus().isNoContent();

        apply();

        assertThat(liveGrants.getPermissions("USER_GROUP", id)).contains(productRead());
        assertThat(userGroupStore.listUserGroupMembers(id)).extracting(UserStore.UserRow::id).containsExactly(user.id());
    }

    @Test
    void aSubgroupOfADraftGroup_isCreatedAfterIt() {
        UUID parent = createInDraft(unique("parent"), root);
        UUID child = createInDraft(unique("child"), parent);

        apply();

        assertThat(userGroupStore.findUserGroupById(child).orElseThrow().parentId()).isEqualTo(parent);
    }

    @Test
    void aNameTakenInTheDraft_isRefused_whetherLiveOrDrafted() {
        String liveName = unique("taken");
        liveGroup(liveName, root);
        String draftName = unique("drafted");
        createInDraft(draftName, root);

        asAdmin(webTestClient.post().uri(draft() + "/user-groups").bodyValue(Map.of("name", liveName, "parentUserGroupId", root)))
                .exchange().expectStatus().isEqualTo(409);
        asAdmin(webTestClient.post().uri(draft() + "/user-groups").bodyValue(Map.of("name", draftName, "parentUserGroupId", root)))
                .exchange().expectStatus().isEqualTo(409);
    }

    // --- Renaming and moving ---

    @Test
    void aRenameInTheDraft_leavesLiveAlone_andSettingItBack_isNoChange() {
        String name = unique("named");
        UUID id = liveGroup(name, root);
        String renamed = unique("renamed");

        rename(id, renamed).expectStatus().isOk();
        assertThat(draftGroupsByName()).containsKey(renamed).doesNotContainKey(name);
        assertThat(userGroupStore.findUserGroupById(id).orElseThrow().name()).isEqualTo(name);
        assertThat(groupChanges()).singleElement().satisfies(c -> assertThat(c).containsEntry("name", renamed).containsEntry("baseName", name));

        rename(id, name).expectStatus().isOk();
        assertThat(groupChanges()).isEmpty();
    }

    @Test
    void aMoveInTheDraft_isAppliedToLive() {
        UUID from = liveGroup(unique("from"), root);
        UUID to = liveGroup(unique("to"), root);
        UUID moving = liveGroup(unique("moving"), from);

        move(moving, to).expectStatus().isOk();
        assertThat(userGroupStore.findUserGroupById(moving).orElseThrow().parentId()).isEqualTo(from);
        assertThat(groupChanges()).singleElement().satisfies(c -> assertThat(c)
                .containsEntry("parentId", to.toString()).containsEntry("baseParentId", from.toString()));

        apply();

        assertThat(userGroupStore.findUserGroupById(moving).orElseThrow().parentId()).isEqualTo(to);
    }

    @Test
    void aMoveThatWouldMakeACycleInTheDraft_isRefused() {
        UUID a = liveGroup(unique("a"), root);
        UUID b = createInDraft(unique("b"), a);

        move(a, b).expectStatus().isBadRequest();
    }

    @Test
    void movesAreAppliedParentFirst_soLiveNeverHasACycle() {
        // Live: outer > inner. Draft: inner up to the root, then outer under inner -- the other way round.
        UUID outer = liveGroup(unique("outer"), root);
        UUID inner = liveGroup(unique("inner"), outer);
        move(inner, root).expectStatus().isOk();
        move(outer, inner).expectStatus().isOk();

        apply();

        assertThat(userGroupStore.findUserGroupById(inner).orElseThrow().parentId()).isEqualTo(root);
        assertThat(userGroupStore.findUserGroupById(outer).orElseThrow().parentId()).isEqualTo(inner);
    }

    @Test
    void liveCatchingUpWithAChange_takesItOffTheList() {
        UUID id = liveGroup(unique("caught-up"), root);
        String renamed = unique("caught-up-renamed");
        rename(id, renamed).expectStatus().isOk();

        userService.renameUserGroup(id, renamed);

        assertThat(groupChanges()).isEmpty();
    }

    // --- Deleting and restoring ---

    @Test
    void aGroupDeletedInTheDraft_isGoneOnlyThere_andApplyDeletesItWithItsGrants() {
        UUID id = liveGroup(unique("doomed"), root);
        accessService.setPermission("USER_GROUP", id, productRead(), true);

        delete(id, false).expectStatus().isNoContent();
        assertThat(draftGroupsByName().values()).noneMatch(g -> g.get("id").equals(id.toString()));
        assertThat(userGroupStore.findUserGroupById(id)).isPresent();

        apply();

        assertThat(userGroupStore.findUserGroupById(id)).isEmpty();
        assertThat(liveGrants.getPermissions("USER_GROUP", id)).isEmpty();
    }

    @Test
    @SuppressWarnings("unchecked")
    void theGroupsADraftDeletes_areListedAsLiveHasThem() {
        UUID parent = liveGroup(unique("deleted-parent"), root);
        UUID child = liveGroup(unique("deleted-child"), parent);
        userService.addMember(freshUser().id(), child);
        delete(parent, true).expectStatus().isNoContent();

        List<Map<String, Object>> deleted = (List<Map<String, Object>>) (List<?>) asAdmin(webTestClient.get().uri(draft() + "/user-groups/deleted"))
                .exchange().expectStatus().isOk().expectBodyList(Map.class).returnResult().getResponseBody();

        assertThat(deleted).extracting(g -> g.get("id")).containsExactlyInAnyOrder(parent.toString(), child.toString());
        assertThat(deleted).filteredOn(g -> g.get("id").equals(child.toString())).singleElement()
                .satisfies(g -> assertThat(g).containsEntry("memberCount", 1).containsEntry("parentIds", List.of(parent.toString())));
    }

    @Test
    void aGroupWithSubgroups_isDeletedInTheDraftOnlyWithForce() {
        UUID parent = liveGroup(unique("parent"), root);
        UUID child = liveGroup(unique("child"), parent);

        delete(parent, false).expectStatus().isEqualTo(409);
        delete(parent, true).expectStatus().isNoContent();

        apply();

        assertThat(userGroupStore.findUserGroupById(parent)).isEmpty();
        assertThat(userGroupStore.findUserGroupById(child)).isEmpty();
    }

    @Test
    void everyone_cantBeDeletedInTheDraft() {
        delete(root, true).expectStatus().isForbidden();
    }

    @Test
    void restoringADeletedGroup_bringsBackWhatTheDraftHadChangedAboutIt() {
        UUID id = liveGroup(unique("restored"), root);
        String renamed = unique("restored-renamed");
        rename(id, renamed).expectStatus().isOk();
        grantInDraft(id, productRead());
        delete(id, false).expectStatus().isNoContent();
        assertThat(((List<?>) changes().get("grants"))).isEmpty();

        asAdmin(webTestClient.post().uri(draft() + "/user-groups/" + id + "/restore")).exchange().expectStatus().isOk();

        assertThat(draftGroupsByName()).containsKey(renamed);
        assertThat(((List<?>) changes().get("grants"))).hasSize(1);
    }

    @Test
    void aDraftGroupDeletedAgain_leavesNothingBehind() {
        UUID id = createInDraft(unique("fleeting"), root);
        grantInDraft(id, productRead());
        addMember(id, freshUser().id()).expectStatus().isNoContent();

        delete(id, false).expectStatus().isNoContent();

        assertThat(groupChanges()).isEmpty();
        assertThat(((List<?>) changes().get("memberships"))).isEmpty();
        assertThat(((List<?>) changes().get("grants"))).isEmpty();
        apply();
        assertThat(userGroupStore.findUserGroupById(id)).isEmpty();
    }

    // --- Floating on live ---

    @Test
    void aLiveChange_showsInTheDraft_alongsideTheDraftsOwn() {
        UUID group = liveGroup(unique("floating"), root);
        var drafted = freshUser();
        addMember(group, drafted.id()).expectStatus().isNoContent();
        assertThat(draftMemberEmails(group)).containsExactly(drafted.email());

        var joinedInLive = freshUser();
        userService.addMember(joinedInLive.id(), group);
        String renamedInLive = unique("floating-renamed");
        userService.renameUserGroup(group, renamedInLive);

        assertThat(draftMemberEmails(group)).containsExactlyInAnyOrder(drafted.email(), joinedInLive.email());
        assertThat(draftGroupsByName()).containsKey(renamedInLive);
    }

    // --- Memberships ---

    @Test
    void membersAddedAndRemovedInTheDraft_changeOnlyTheDraft_untilApplied() {
        UUID group = liveGroup(unique("team"), root);
        var joining = freshUser();
        var leaving = freshUser();
        userService.addMember(leaving.id(), group);

        addMember(group, joining.id()).expectStatus().isNoContent();
        removeMember(group, leaving.id()).expectStatus().isNoContent();

        assertThat(draftMemberEmails(group)).containsExactly(joining.email());
        assertThat(userGroupStore.listUserGroupMembers(group)).extracting(UserStore.UserRow::id).containsExactly(leaving.id());
        assertThat(((List<?>) changes().get("memberships"))).hasSize(2);

        apply();

        assertThat(userGroupStore.listUserGroupMembers(group)).extracting(UserStore.UserRow::id).containsExactly(joining.id());
    }

    @Test
    void aUsersGroupsInTheDraft_includeTheDraftsMemberships() {
        UUID group = liveGroup(unique("joined"), root);
        var user = freshUser();
        addMember(group, user.id()).expectStatus().isNoContent();

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> groups = (List<Map<String, Object>>) (List<?>) asAdmin(webTestClient.get().uri(draft() + "/users/" + user.id() + "/user-groups"))
                .exchange().expectStatus().isOk().expectBodyList(Map.class).returnResult().getResponseBody();
        assertThat(groups).extracting(g -> g.get("id")).contains(group.toString());
    }

    @Test
    void aParticipantJoiningAGroupInTheDraft_getsItsGrantsInTheDraft_only() {
        UUID readers = liveGroup(unique("readers"), root);
        accessService.setPermission("USER_GROUP", readers, productRead(), true);
        var user = freshUser();
        asAdmin(webTestClient.post().uri("/api/drafts/" + draftId + "/participants").bodyValue(Map.of("userId", user.id())))
                .exchange().expectStatus().isNoContent();

        addMember(readers, user.id()).expectStatus().isNoContent();

        assertThat(schemaItemTypes("/api/drafts/" + draftId, user.email())).contains(PRODUCT);
        assertThat(schemaItemTypes("/api", user.email())).doesNotContain(PRODUCT);
    }

    @SuppressWarnings("unchecked")
    private List<String> schemaItemTypes(String base, String email) {
        Map<String, Object> schema = webTestClient.get().uri(base + "/schema").header(HttpHeaders.AUTHORIZATION, bearerTokenFor(email))
                .exchange().expectStatus().isOk().expectBody(Map.class).returnResult().getResponseBody();
        return ((List<Map<String, Object>>) schema.get("items")).stream().map(i -> (String) i.get("name")).toList();
    }

    // --- The record ---

    @Test
    void eachChange_isInTheDraftsChangelog() {
        UUID live = liveGroup(unique("logged"), root);
        UUID drafted = createInDraft(unique("logged-draft"), root);
        rename(live, unique("logged-renamed")).expectStatus().isOk();
        move(live, drafted).expectStatus().isOk();
        addMember(drafted, freshUser().id()).expectStatus().isNoContent();
        delete(live, false).expectStatus().isNoContent();

        assertThat(draftLogKinds()).containsSubsequence("group.created", "group.renamed", "group.moved", "membership.added", "group.deleted");
    }

    @Test
    void onlyAdmins_changeGroupsInADraft() {
        var user = freshUser();
        asAdmin(webTestClient.post().uri("/api/drafts/" + draftId + "/participants").bodyValue(Map.of("userId", user.id())))
                .exchange().expectStatus().isNoContent();

        webTestClient.post().uri(draft() + "/user-groups").header(HttpHeaders.AUTHORIZATION, bearerTokenFor(user.email()))
                .bodyValue(Map.of("name", unique("sneaky"), "parentUserGroupId", root)).exchange().expectStatus().isForbidden();
    }
}
