package org.ntrloc.graph.db.partition.authorization;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.ntrloc.graph.AbstractIntegrationTest;
import org.ntrloc.graph.db.coordinator.CoordinatorTestDomainInitializer;
import org.ntrloc.graph.db.coordinator.CountedPredicateTestDomainInitializer;
import org.ntrloc.graph.db.partition.authorization.repository.GrantStoreReader;
import org.ntrloc.graph.db.partition.authorization.repository.Permission;
import org.ntrloc.graph.db.partition.security.repository.UserStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

// The per-area marker grant endpoints the legacy /admin UI uses (AccessAdminController), for a group and
// for a user: each sets what it names, and deleting a principal's grant of a marker removes all of it.
class AccessAdminMarkerGrantsIntegrationTest extends AbstractIntegrationTest {

    private static final String ADMIN = "root@example.com";

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private UserStore userStore;

    @Autowired
    private AccessService accessService;

    @Autowired
    private GrantStoreReader grants;

    @Autowired
    private CoordinatorTestDomainInitializer products;

    @Autowired
    private CountedPredicateTestDomainInitializer portfolios;

    private UUID marker;

    @BeforeEach
    void newMarker() {
        marker = accessService.createMarker("legacy-grants-" + UUID.randomUUID(), "", "ITEM_TYPE", products.productTypeId()).id();
    }

    private WebTestClient.ResponseSpec send(String method, String path, Object body) {
        var request = webTestClient.method(org.springframework.http.HttpMethod.valueOf(method)).uri("/api/admin" + path)
                .header(HttpHeaders.AUTHORIZATION, bearerTokenFor(ADMIN));
        return body == null ? request.exchange() : request.bodyValue(body).exchange();
    }

    @SuppressWarnings("unchecked")
    private UUID firstTransitionFromTargets() {
        Map<String, Object> targets = webTestClient.get().uri("/api/admin/grants/targets").header(HttpHeaders.AUTHORIZATION, bearerTokenFor(ADMIN))
                .exchange().expectStatus().isOk().expectBody(Map.class).returnResult().getResponseBody();
        var itemTypes = (java.util.List<Map<String, Object>>) targets.get("itemTypes");
        var portfolio = itemTypes.stream().filter(t -> t.get("id").equals(portfolios.portfolioTypeId().toString())).findFirst().orElseThrow();
        var machine = ((java.util.List<Map<String, Object>>) portfolio.get("stateMachines")).getFirst();
        var transition = ((java.util.List<Map<String, Object>>) machine.get("transitions")).getFirst();
        return UUID.fromString((String) transition.get("id"));
    }

    // Sets one of each kind through the legacy endpoints under base ("/user-groups/{id}" or "/users/{id}").
    private void grantOneOfEach(String base, UUID transitionId) {
        String m = base + "/markers/" + marker;
        send("PUT", m + "/item-permissions", Map.of("canRead", true, "canDelete", false)).expectStatus().isNoContent();
        send("PUT", m + "/properties/" + products.namePropertyId(), Map.of("canRead", false, "canWrite", true)).expectStatus().isNoContent();
        send("PUT", m + "/link-properties/" + products.rolePropertyId(), Map.of("canRead", true, "canWrite", false)).expectStatus().isNoContent();
        send("PUT", m + "/link-perspectives/" + products.productPerspectiveId(), Map.of("canCreate", true, "canRead", false, "canDelete", true))
                .expectStatus().isNoContent();
        send("POST", m + "/transitions/" + transitionId, null).expectStatus().isNoContent();
        send("POST", m + "/state-machines/" + portfolios.publicationMachineId() + "/start", null).expectStatus().isNoContent();
    }

    private void assertOneOfEach(String principalType, UUID principalId, UUID transitionId) {
        assertThat(grants.getPermissions(principalType, principalId)).containsExactlyInAnyOrder(
                Permission.forMarker(marker, Permission.AREA_ITEM, null, "read"),
                Permission.forMarker(marker, Permission.AREA_PROPERTY, products.namePropertyId(), "write"),
                Permission.forMarker(marker, Permission.AREA_LINK_PROPERTY, products.rolePropertyId(), "read"),
                Permission.forMarker(marker, Permission.AREA_PERSPECTIVE, products.productPerspectiveId(), "create"),
                Permission.forMarker(marker, Permission.AREA_PERSPECTIVE, products.productPerspectiveId(), "delete"),
                Permission.forMarker(marker, Permission.AREA_TRANSITION, transitionId, null),
                Permission.forMarker(marker, Permission.AREA_START, portfolios.publicationMachineId(), null));
    }

    @Test
    void aGroupsMarkerGrants_areSetPerArea_revokedPerArea_andDeletedWhole() {
        UUID group = userStore.createUserGroup("legacy-grants-" + UUID.randomUUID()).id();
        UUID transition = firstTransitionFromTargets();
        String base = "/user-groups/" + group;

        grantOneOfEach(base, transition);
        assertOneOfEach("USER_GROUP", group, transition);

        send("DELETE", base + "/markers/" + marker + "/transitions/" + transition, null).expectStatus().isNoContent();
        send("DELETE", base + "/markers/" + marker + "/state-machines/" + portfolios.publicationMachineId() + "/start", null).expectStatus().isNoContent();
        assertThat(grants.getPermissions("USER_GROUP", group))
                .noneMatch(p -> Permission.AREA_TRANSITION.equals(p.area()) || Permission.AREA_START.equals(p.area()));

        send("DELETE", base + "/markers/" + marker, null).expectStatus().isNoContent();
        assertThat(grants.getPermissions("USER_GROUP", group)).isEmpty();
    }

    @Test
    void aUsersMarkerGrants_areSetPerArea_revokedPerArea_andDeletedWhole() {
        UUID user = userStore.createUser("Legacy Grants", "legacy-grants-" + UUID.randomUUID() + "@example.com", false).id();
        UUID transition = firstTransitionFromTargets();
        String base = "/users/" + user;

        grantOneOfEach(base, transition);
        assertOneOfEach("USER", user, transition);

        send("DELETE", base + "/markers/" + marker + "/transitions/" + transition, null).expectStatus().isNoContent();
        send("DELETE", base + "/markers/" + marker + "/state-machines/" + portfolios.publicationMachineId() + "/start", null).expectStatus().isNoContent();
        assertThat(grants.getPermissions("USER", user))
                .noneMatch(p -> Permission.AREA_TRANSITION.equals(p.area()) || Permission.AREA_START.equals(p.area()));

        send("DELETE", base + "/markers/" + marker, null).expectStatus().isNoContent();
        assertThat(grants.getPermissions("USER", user)).isEmpty();
    }
}
