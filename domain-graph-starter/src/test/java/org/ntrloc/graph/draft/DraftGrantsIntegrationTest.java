package org.ntrloc.graph.draft;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.ntrloc.graph.AbstractIntegrationTest;
import org.ntrloc.graph.db.coordinator.CoordinatorTestDomainInitializer;
import org.ntrloc.graph.db.partition.authorization.AccessService;
import org.ntrloc.graph.db.partition.authorization.repository.GrantStoreReader;
import org.ntrloc.graph.db.partition.security.repository.UserStore;
import org.ntrloc.graph.db.partition.security.repository.UserGroupStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

// Grants inside a draft: the draft's overrides on top of live (docs/ntrloc-system-model-notes.md §7.2.1).
// Each test works on a fresh group, so nothing it grants live can reach another test.
class DraftGrantsIntegrationTest extends AbstractIntegrationTest {

    private static final String ADMIN = "root@example.com";

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private UserStore userStore;

    @Autowired
    private UserGroupStore userGroupStore;

    @Autowired
    private AccessService accessService;

    @Autowired
    private GrantStoreReader liveGrants;

    @Autowired
    private DraftGrantStore draftGrantStore;

    @Autowired
    private CoordinatorTestDomainInitializer fixture;

    private UUID draftId;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void startADraft() {
        discardOpenDrafts();
        draftId = UUID.fromString((String) webTestClient.post().uri("/api/drafts").header(HttpHeaders.AUTHORIZATION, bearerTokenFor(ADMIN))
                .bodyValue(Map.of("name", "Draft grants test")).exchange().expectStatus().isCreated()
                .expectBody(Map.class).returnResult().getResponseBody().get("id"));
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

    private String live() {
        return "/api/admin/grants";
    }

    private String draft() {
        return "/api/drafts/" + draftId + "/admin/grants";
    }

    private UUID newGroup() {
        return userGroupStore.createUserGroup("draft-grants-" + UUID.randomUUID()).id();
    }

    private UUID newMarker() {
        return accessService.createMarker("draft-grants-" + UUID.randomUUID(), "", "ITEM_TYPE", fixture.productTypeId()).id();
    }

    private static Map<String, Object> markerPermission(UUID markerId, String area, UUID targetId, String op) {
        var permission = new HashMap<String, Object>();
        permission.put("kind", "marker");
        permission.put("markerId", markerId);
        permission.put("area", area);
        permission.put("targetId", targetId);
        permission.put("op", op);
        return permission;
    }

    private Map<String, Object> createProducts() {
        return Map.of("kind", "itemType", "itemTypeId", fixture.productTypeId(), "op", "create");
    }

    private WebTestClient.ResponseSpec put(String address, UUID groupId, Map<String, Object> permission, boolean value) {
        return webTestClient.put().uri(address)
                .header(HttpHeaders.AUTHORIZATION, bearerTokenFor(ADMIN))
                .bodyValue(Map.of("principal", Map.of("kind", "group", "id", groupId), "permission", permission, "value", value))
                .exchange();
    }

    // Each permission as "area/op" ("null/create" for an item-type permission).
    @SuppressWarnings("unchecked")
    private List<String> permissionsOf(String address, UUID groupId) {
        List<Map<String, Object>> body = (List<Map<String, Object>>) (List<?>) webTestClient.get().uri(address + "?principal=group:" + groupId)
                .header(HttpHeaders.AUTHORIZATION, bearerTokenFor(ADMIN))
                .exchange().expectStatus().isOk()
                .expectBodyList(Map.class).returnResult().getResponseBody();
        return ((List<Map<String, Object>>) body.getFirst().get("permissions")).stream()
                .map(p -> p.get("area") + "/" + p.get("op")).toList();
    }

    @Test
    void anUntouchedDraft_hasExactlyLivesGrants() {
        assertThat(draftGrantStore.getGrantsView(draftId).rows()).isEqualTo(liveGrants.getGrantsView().rows());
    }

    @Test
    void aDraftThatChangesAPermissionBack_hasExactlyLivesGrantsAgain() {
        UUID group = newGroup();
        put(draft(), group, createProducts(), true).expectStatus().isNoContent();
        put(draft(), group, createProducts(), false).expectStatus().isNoContent();

        assertThat(draftGrantStore.overrides(draftId)).isEmpty();
        assertThat(draftGrantStore.getGrantsView(draftId).rows()).isEqualTo(liveGrants.getGrantsView().rows());
    }

    @Test
    void aPermissionSetInADraft_isInTheDraft_andNotLive() {
        UUID group = newGroup();

        put(draft(), group, createProducts(), true).expectStatus().isNoContent();

        assertThat(permissionsOf(draft(), group)).containsExactly("null/create");
        assertThat(permissionsOf(live(), group)).isEmpty();
        assertThat(draftGrantStore.overrides(draftId)).singleElement()
                .satisfies(o -> assertThat(o.value()).isTrue())
                .satisfies(o -> assertThat(o.baseValue()).isFalse());
    }

    @Test
    void aDraftRemovingOneOp_leavesTheRowsOtherOpsAsLiveHasThem() {
        UUID group = newGroup();
        UUID marker = newMarker();
        UUID name = fixture.namePropertyId();
        put(live(), group, markerPermission(marker, "property", name, "read"), true).expectStatus().isNoContent();
        put(live(), group, markerPermission(marker, "property", name, "write"), true).expectStatus().isNoContent();

        put(draft(), group, markerPermission(marker, "property", name, "read"), false).expectStatus().isNoContent();

        assertThat(permissionsOf(draft(), group)).containsExactly("property/write");
        assertThat(permissionsOf(live(), group)).containsExactlyInAnyOrder("property/read", "property/write");
    }

    @Test
    void liveChangesToPermissionsTheDraftDidntTouch_showInTheDraft() {
        UUID group = newGroup();
        UUID marker = newMarker();
        put(draft(), group, createProducts(), true).expectStatus().isNoContent();
        assertThat(permissionsOf(draft(), group)).containsExactly("null/create");

        put(live(), group, markerPermission(marker, "item", null, "read"), true).expectStatus().isNoContent();

        assertThat(permissionsOf(draft(), group)).containsExactlyInAnyOrder("null/create", "item/read");
    }

    @Test
    void aPermissionNamingSomethingThatDoesNotExist_isABadRequest_inADraftToo() {
        UUID group = newGroup();
        UUID marker = newMarker();

        put(draft(), group, markerPermission(marker, "property", UUID.randomUUID(), "read"), true).expectStatus().isBadRequest();
        put(draft(), group, markerPermission(UUID.randomUUID(), "item", null, "read"), true).expectStatus().isBadRequest();
        put(draft(), group, Map.of("kind", "itemType", "itemTypeId", UUID.randomUUID(), "op", "read"), true).expectStatus().isBadRequest();
        assertThat(draftGrantStore.overrides(draftId)).isEmpty();
    }
}
