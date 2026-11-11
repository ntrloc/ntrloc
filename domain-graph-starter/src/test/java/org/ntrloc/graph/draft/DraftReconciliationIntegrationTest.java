package org.ntrloc.graph.draft;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.ntrloc.graph.AbstractIntegrationTest;
import org.ntrloc.graph.db.coordinator.CoordinatorTestDomainInitializer;
import org.ntrloc.graph.db.partition.authorization.AccessService;
import org.ntrloc.graph.db.partition.authorization.repository.Permission;
import org.ntrloc.graph.db.partition.security.repository.UserStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

// Live changing underneath a draft's grant changes (docs/ntrloc-system-model-notes.md §13.3-13.4): each
// change records what it depends on in live; when that moves, /changes says why the change needs
// reconciling; an admin keeps it or takes live's value; and apply waits until nothing needs it.
class DraftReconciliationIntegrationTest extends AbstractIntegrationTest {

    private static final String ADMIN = "root@example.com";
    private static final String INVITED = "alice@example.com";

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private UserStore userStore;

    @Autowired
    private AccessService accessService;

    @Autowired
    private CoordinatorTestDomainInitializer fixture;

    private UUID draftId;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void startADraftWithAliceInvited() {
        discardOpenDrafts();
        draftId = UUID.fromString((String) webTestClient.post().uri("/api/drafts").header(HttpHeaders.AUTHORIZATION, bearerTokenFor(ADMIN))
                .bodyValue(Map.of("name", "Reconciliation test")).exchange().expectStatus().isCreated()
                .expectBody(Map.class).returnResult().getResponseBody().get("id"));
        UUID alice = userStore.findUserByEmail(INVITED).orElseThrow().id();
        webTestClient.post().uri("/api/drafts/" + draftId + "/participants").header(HttpHeaders.AUTHORIZATION, bearerTokenFor(ADMIN))
                .bodyValue(Map.of("userId", alice)).exchange().expectStatus().isNoContent();
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

    private UUID group(String name) {
        return userStore.createUserGroup(name + "-" + UUID.randomUUID()).id();
    }

    private UUID group(String name, UUID parent) {
        return userStore.createUserGroup(name + "-" + UUID.randomUUID(), parent).id();
    }

    private Permission createProducts() {
        return Permission.itemType(fixture.productTypeId(), "create");
    }

    private static Map<String, Object> body(Permission permission) {
        var body = new HashMap<String, Object>();
        body.put("kind", permission.kind());
        body.put("itemTypeId", permission.itemTypeId());
        body.put("markerId", permission.markerId());
        body.put("area", permission.area());
        body.put("targetId", permission.targetId());
        body.put("op", permission.op());
        return body;
    }

    private static Map<String, Object> principal(String kind, UUID id) {
        return Map.of("kind", kind, "id", id);
    }

    private void setInDraft(String kind, UUID principal, Permission permission, boolean value) {
        webTestClient.put().uri("/api/drafts/" + draftId + "/admin/grants").header(HttpHeaders.AUTHORIZATION, bearerTokenFor(ADMIN))
                .bodyValue(Map.of("principal", principal(kind, principal), "permission", body(permission), "value", value))
                .exchange().expectStatus().isNoContent();
    }

    private WebTestClient.ResponseSpec reconcile(String as, String kind, UUID principal, Permission permission, String resolution) {
        return webTestClient.post().uri("/api/drafts/" + draftId + "/reconcile").header(HttpHeaders.AUTHORIZATION, bearerTokenFor(as))
                .bodyValue(Map.of("principal", principal(kind, principal), "permission", body(permission), "resolution", resolution))
                .exchange();
    }

    // Each change's principal id, with its reasons as "KIND" or "KIND id".
    @SuppressWarnings("unchecked")
    private Map<UUID, List<String>> reasons() {
        Map<String, Object> body = webTestClient.get().uri("/api/drafts/" + draftId + "/changes").header(HttpHeaders.AUTHORIZATION, bearerTokenFor(ADMIN))
                .exchange().expectStatus().isOk().expectBody(Map.class).returnResult().getResponseBody();
        Map<UUID, List<String>> byPrincipal = new HashMap<>();
        for (var change : (List<Map<String, Object>>) body.get("grants")) {
            UUID id = UUID.fromString((String) ((Map<String, Object>) change.get("principal")).get("id"));
            byPrincipal.put(id, ((List<Map<String, Object>>) change.get("reconcile")).stream()
                    .map(r -> r.get("id") == null ? (String) r.get("kind") : r.get("kind") + " " + r.get("id")).toList());
        }
        return byPrincipal;
    }

    @Test
    void aChangeLiveHasntTouched_needsNoReconciling_evenAfterUnrelatedLiveChanges() {
        UUID g = group("untouched");
        setInDraft("group", g, createProducts(), true);
        userStore.updateUserGroup(group("elsewhere"), "renamed-" + UUID.randomUUID());

        assertThat(reasons()).containsEntry(g, List.of());
    }

    // The example that started it (notes §13.3): another admin moves the group in live.
    @Test
    void movingTheGroupInLive_marksItsChange() {
        UUID oldParent = group("old");
        UUID newParent = group("new");
        UUID g = group("moved", oldParent);
        setInDraft("group", g, createProducts(), true);

        userStore.setParent(g, newParent);

        assertThat(reasons().get(g)).containsExactly("PRINCIPAL_CHANGED " + g);
    }

    @Test
    void movingAGroupAboveIt_marksItsChange_namingThatGroup() {
        UUID top = group("top");
        UUID parent = group("parent", top);
        UUID g = group("below", parent);
        setInDraft("group", g, createProducts(), true);

        userStore.setParent(parent, group("elsewhere"));

        assertThat(reasons().get(g)).containsExactly("GROUP_CHANGED " + parent);
    }

    @Test
    void liveGrantingTheSamePermissionToAParent_marksTheChange() {
        UUID parent = group("parent");
        UUID g = group("child", parent);
        setInDraft("group", g, createProducts(), false);
        setInDraft("group", g, createProducts(), true);

        accessService.setPermission("USER_GROUP", parent, createProducts(), true);

        assertThat(reasons().get(g)).containsExactly("INHERITED_CHANGED");
    }

    @Test
    void aUserJoiningAGroupInLive_marksTheirChange() {
        UUID user = userStore.createUser("Joiner", "joiner-" + UUID.randomUUID() + "@example.com", false).id();
        setInDraft("user", user, createProducts(), true);

        userStore.addUserToUserGroup(user, group("joined"));

        assertThat(reasons().get(user)).containsExactly("PRINCIPAL_CHANGED " + user);
    }

    @Test
    void deletingThePrincipalOrTheTarget_makesTheChangeAnOrphan() {
        UUID deletedGroup = group("deleted");
        UUID g = group("kept");
        UUID marker = accessService.createMarker("doomed-" + UUID.randomUUID(), "", "ITEM_TYPE", fixture.productTypeId()).id();
        setInDraft("group", deletedGroup, createProducts(), true);
        setInDraft("group", g, Permission.forMarker(marker, Permission.AREA_ITEM, null, "read"), true);

        userStore.deleteUserGroup(deletedGroup, false);
        accessService.deleteMarker(marker);

        assertThat(reasons()).containsEntry(deletedGroup, List.of("PRINCIPAL_DELETED " + deletedGroup))
                .containsEntry(g, List.of("TARGET_DELETED"));
    }

    @Test
    void keeping_reconcilesTheChange_andItApplies() {
        UUID g = group("kept", group("old"));
        setInDraft("group", g, createProducts(), true);
        userStore.setParent(g, group("new"));

        reconcile(ADMIN, "group", g, createProducts(), "KEEP").expectStatus().isNoContent();

        assertThat(reasons().get(g)).isEmpty();
        webTestClient.post().uri("/api/drafts/" + draftId + "/apply").header(HttpHeaders.AUTHORIZATION, bearerTokenFor(ADMIN))
                .exchange().expectStatus().isOk();
    }

    @Test
    void usingLive_dropsTheChangeFromTheDraft() {
        UUID g = group("dropped", group("old"));
        setInDraft("group", g, createProducts(), true);
        userStore.setParent(g, group("new"));

        reconcile(ADMIN, "group", g, createProducts(), "USE_LIVE").expectStatus().isNoContent();

        assertThat(reasons()).doesNotContainKey(g);
    }

    @Test
    void adjustingTheChange_reconcilesItToo() {
        UUID g = group("adjusted", group("old"));
        setInDraft("group", g, createProducts(), true);
        userStore.setParent(g, group("new"));

        setInDraft("group", g, createProducts(), true);

        assertThat(reasons().get(g)).isEmpty();
    }

    @Test
    void applyWaits_untilEveryChangeIsReconciled() {
        UUID g = group("waiting", group("old"));
        setInDraft("group", g, createProducts(), true);
        userStore.setParent(g, group("new"));

        webTestClient.post().uri("/api/drafts/" + draftId + "/apply").header(HttpHeaders.AUTHORIZATION, bearerTokenFor(ADMIN))
                .exchange().expectStatus().isEqualTo(409)
                .expectBody().jsonPath("$.message").isEqualTo("1 change needs reconciling with live before the draft can be applied");
    }

    @Test
    void onlyAdminsReconcile() {
        UUID g = group("participant");
        setInDraft("group", g, createProducts(), true);

        reconcile(INVITED, "group", g, createProducts(), "KEEP").expectStatus().isForbidden();
    }
}
