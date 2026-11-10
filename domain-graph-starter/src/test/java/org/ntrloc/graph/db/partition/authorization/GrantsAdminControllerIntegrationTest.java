package org.ntrloc.graph.db.partition.authorization;

import org.junit.jupiter.api.Test;
import org.ntrloc.graph.AbstractIntegrationTest;
import org.ntrloc.graph.db.coordinator.CoordinatorTestDomainInitializer;
import org.ntrloc.graph.db.partition.security.repository.UserStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

// The grants API: a principal's own permissions, read as a list and set one at a time. Each test works on
// a fresh group so nothing it grants can reach another test.
class GrantsAdminControllerIntegrationTest extends AbstractIntegrationTest {

    private static final String ADMIN = "root@example.com";

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private UserStore userStore;

    @Autowired
    private AccessService accessService;

    @Autowired
    private CoordinatorTestDomainInitializer fixture;

    private UUID newGroup() {
        return userStore.createUserGroup("grants-api-" + UUID.randomUUID()).id();
    }

    private UUID newMarker() {
        return accessService.createMarker("grants-api-" + UUID.randomUUID(), "", "ITEM_TYPE", fixture.productTypeId()).id();
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

    private WebTestClient.ResponseSpec put(UUID groupId, Map<String, Object> permission, boolean value) {
        return webTestClient.put().uri("/api/admin/grants")
                .header(HttpHeaders.AUTHORIZATION, bearerTokenFor(ADMIN))
                .bodyValue(Map.of("principal", Map.of("kind", "group", "id", groupId), "permission", permission, "value", value))
                .exchange();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> permissionsOf(UUID groupId) {
        List<Map<String, Object>> body = (List<Map<String, Object>>) (List<?>) webTestClient.get().uri("/api/admin/grants?principal=group:" + groupId)
                .header(HttpHeaders.AUTHORIZATION, bearerTokenFor(ADMIN))
                .exchange()
                .expectStatus().isOk()
                .expectBodyList(Map.class).returnResult().getResponseBody();
        assertThat(body).hasSize(1);
        return (List<Map<String, Object>>) body.getFirst().get("permissions");
    }

    @Test
    void settingOneOp_leavesTheOtherOpsOnTheSameGrantAlone() {
        UUID group = newGroup();
        UUID marker = newMarker();
        UUID name = fixture.namePropertyId();

        put(group, markerPermission(marker, "property", name, "read"), true).expectStatus().isNoContent();
        put(group, markerPermission(marker, "property", name, "write"), true).expectStatus().isNoContent();
        put(group, markerPermission(marker, "property", name, "read"), false).expectStatus().isNoContent();

        assertThat(permissionsOf(group))
                .extracting(p -> p.get("area") + "/" + p.get("op"))
                .containsExactly("property/write");
    }

    @Test
    void permissions_listItemTypeAndMarkerGrants_inTheShapeTheyAreSet() {
        UUID group = newGroup();
        UUID marker = newMarker();
        put(group, Map.of("kind", "itemType", "itemTypeId", fixture.productTypeId(), "op", "create"), true).expectStatus().isNoContent();
        put(group, markerPermission(marker, "item", null, "read"), true).expectStatus().isNoContent();
        put(group, markerPermission(marker, "perspective", fixture.productPerspectiveId(), "delete"), true).expectStatus().isNoContent();

        assertThat(permissionsOf(group))
                .extracting(p -> p.get("kind") + "/" + p.get("area") + "/" + p.get("op"))
                .containsExactlyInAnyOrder("itemType/null/create", "marker/item/read", "marker/perspective/delete");
    }

    @Test
    void settingWhatIsAlreadySet_changesNothing() {
        UUID group = newGroup();
        UUID marker = newMarker();

        put(group, markerPermission(marker, "item", null, "delete"), true).expectStatus().isNoContent();
        put(group, markerPermission(marker, "item", null, "delete"), true).expectStatus().isNoContent();
        put(group, markerPermission(marker, "item", null, "read"), false).expectStatus().isNoContent();

        assertThat(permissionsOf(group)).extracting(p -> p.get("op")).containsExactly("delete");
    }

    @Test
    void aPermissionThatDoesNotFitItsArea_isABadRequest() {
        put(newGroup(), markerPermission(newMarker(), "item", fixture.namePropertyId(), "read"), true).expectStatus().isBadRequest();
        put(newGroup(), markerPermission(newMarker(), "transition", UUID.randomUUID(), "read"), true).expectStatus().isBadRequest();
    }

    @Test
    void aPermissionNamingSomethingThatDoesNotExist_isABadRequest() {
        put(newGroup(), markerPermission(newMarker(), "property", UUID.randomUUID(), "read"), true).expectStatus().isBadRequest();
    }

    @Test
    void anUnknownPrincipal_isNotFound_andAMalformedOneABadRequest() {
        webTestClient.get().uri("/api/admin/grants?principal=group:" + UUID.randomUUID())
                .header(HttpHeaders.AUTHORIZATION, bearerTokenFor(ADMIN))
                .exchange().expectStatus().isNotFound();
        webTestClient.get().uri("/api/admin/grants?principal=team:" + UUID.randomUUID())
                .header(HttpHeaders.AUTHORIZATION, bearerTokenFor(ADMIN))
                .exchange().expectStatus().isBadRequest();
    }

    @Test
    void nonAdmins_areForbidden() {
        webTestClient.get().uri("/api/admin/grants/targets")
                .header(HttpHeaders.AUTHORIZATION, bearerTokenFor("alice@example.com"))
                .exchange().expectStatus().isForbidden();
    }

    @Test
    @SuppressWarnings("unchecked")
    void targets_listEachItemTypesGrantableParts_andTheMarkers() {
        UUID marker = newMarker();

        Map<String, Object> targets = webTestClient.get().uri("/api/admin/grants/targets")
                .header(HttpHeaders.AUTHORIZATION, bearerTokenFor(ADMIN))
                .exchange().expectStatus().isOk()
                .expectBody(Map.class).returnResult().getResponseBody();

        var product = ((List<Map<String, Object>>) targets.get("itemTypes")).stream()
                .filter(t -> t.get("id").equals(fixture.productTypeId().toString())).findFirst().orElseThrow();
        assertThat((List<Map<String, Object>>) product.get("properties"))
                .extracting(p -> p.get("id")).contains(fixture.namePropertyId().toString());
        assertThat((List<Map<String, Object>>) product.get("perspectives"))
                .extracting(p -> p.get("id")).contains(fixture.productPerspectiveId().toString());
        assertThat((List<Map<String, Object>>) targets.get("markers"))
                .extracting(m -> m.get("id")).contains(marker.toString());
    }
}
