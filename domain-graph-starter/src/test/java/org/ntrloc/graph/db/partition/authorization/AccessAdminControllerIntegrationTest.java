package org.ntrloc.graph.db.partition.authorization;

import org.junit.jupiter.api.Test;
import org.ntrloc.graph.AbstractIntegrationTest;
import org.ntrloc.graph.db.partition.authorization.repository.AuthorizationRepository;
import org.ntrloc.graph.db.partition.schema.repository.SchemaRepository;
import org.ntrloc.graph.db.partition.security.repository.SecurityRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

// Covers AccessAdminController's group-permission CRUD, user-effective-permissions rollup,
// user-group listing, and item-type listing endpoints. Reuses AuthorizationTestDataInitializer's
// standing fixture (alice/bob/carol/root, group "viewers", AclTestPublicDoc's item-type:read
// grant -- see that class's own comment) for read-only assertions, since those never mutate it.
// Anything that WRITES a grant uses a freshly created, UUID-suffixed group instead, so this class
// can never leave state behind that could flip an assertion in AuthorizationEndpointsIntegrationTest
// (same shared singleton Postgres/schema -- see AbstractIntegrationTest's own comment on why).
//
// Grants are direct (item_type_id, principal_type, principal_id, permission) rows -- no marker
// indirection -- so there's no "not assigned yet" branch to special-case the way the old
// marker-routed implementation had; grant/revoke behave the same regardless of an item type's
// prior grant history.
class AccessAdminControllerIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private SecurityRepository securityRepo;

    @Autowired
    private AuthorizationRepository authRepo;

    @Autowired
    private SchemaRepository schemaRepo;

    private UUID publicDocId() {
        return schemaRepo.getAllItems().stream()
                .filter(item -> item.name().equals("AclTestPublicDoc"))
                .findFirst()
                .orElseThrow()
                .id();
    }

    private UUID viewersGroupId() {
        return securityRepo.findGroupByName("viewers").orElseThrow().id();
    }

    private UUID aliceId() {
        return securityRepo.findUserByExternalId("alice").orElseThrow().id();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> getAsRoot(String uri) {
        return (List<Map<String, Object>>) (List<?>) webTestClient.get().uri(uri)
                .header("X-Ntrloc-User", "root")
                .exchange()
                .expectStatus().isOk()
                .expectBodyList(Map.class)
                .returnResult()
                .getResponseBody();
    }

    // --- Admin-only enforcement ---

    @Test
    void nonAdminCallerIsForbidden() {
        webTestClient.get().uri("/api/admin/users/" + aliceId() + "/groups")
                .header("X-Ntrloc-User", "alice")
                .exchange()
                .expectStatus().isForbidden();
    }

    // --- Group permissions ---

    @Test
    void getGroupPermissions_showsTheStandingViewersGrant() {
        List<Map<String, Object>> permissions = getAsRoot("/api/admin/groups/" + viewersGroupId() + "/permissions");

        assertThat(permissions)
                .filteredOn(p -> p.get("itemTypeName").equals("AclTestPublicDoc"))
                .flatExtracting(p -> (List<String>) p.get("operations"))
                .contains("item-type:read");
    }

    @Test
    void getGroupPermissions_groupsMultipleOperationsOnTheSameItemTypeIntoOneEntry() {
        var group = securityRepo.createGroup("access-admin-test-" + UUID.randomUUID());
        authRepo.grantItemTypeIfAbsent(publicDocId(), "GROUP", group.id(), "item-type:read");
        authRepo.grantItemTypeIfAbsent(publicDocId(), "GROUP", group.id(), "item-type:create");

        List<Map<String, Object>> permissions = getAsRoot("/api/admin/groups/" + group.id() + "/permissions");

        assertThat(permissions)
                .filteredOn(p -> p.get("itemTypeName").equals("AclTestPublicDoc"))
                .flatExtracting(p -> (List<String>) p.get("operations"))
                .containsExactlyInAnyOrder("item-type:read", "item-type:create");
    }

    @Test
    void getGroupPermissions_forUnknownGroup_returnsNotFound() {
        webTestClient.get().uri("/api/admin/groups/" + UUID.randomUUID() + "/permissions")
                .header("X-Ntrloc-User", "root")
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    void grantGroupPermission_addsAGrantThatWasNotPreviouslyThere() {
        var group = securityRepo.createGroup("access-admin-test-" + UUID.randomUUID());
        assertThat(authRepo.findItemTypeGrant(publicDocId(), "GROUP", group.id(), "item-type:read")).isEmpty();

        webTestClient.post().uri("/api/admin/groups/" + group.id() + "/permissions")
                .header("X-Ntrloc-User", "root")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("itemTypeId", publicDocId().toString(), "operation", "item-type:read"))
                .exchange()
                .expectStatus().isNoContent();

        assertThat(authRepo.findItemTypeGrant(publicDocId(), "GROUP", group.id(), "item-type:read")).isPresent();
    }

    @Test
    void grantGroupPermission_forUnknownGroup_returnsNotFound() {
        webTestClient.post().uri("/api/admin/groups/" + UUID.randomUUID() + "/permissions")
                .header("X-Ntrloc-User", "root")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("itemTypeId", publicDocId().toString(), "operation", "item-type:read"))
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    void grantGroupPermission_withoutAnItemTypeId_returnsBadRequest() {
        var group = securityRepo.createGroup("access-admin-test-" + UUID.randomUUID());

        webTestClient.post().uri("/api/admin/groups/" + group.id() + "/permissions")
                .header("X-Ntrloc-User", "root")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("operation", "item-type:read"))
                .exchange()
                .expectStatus().isBadRequest();
    }

    @Test
    void grantGroupPermission_withAnUnrecognizedOperation_returnsBadRequest() {
        var group = securityRepo.createGroup("access-admin-test-" + UUID.randomUUID());

        webTestClient.post().uri("/api/admin/groups/" + group.id() + "/permissions")
                .header("X-Ntrloc-User", "root")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("itemTypeId", publicDocId().toString(), "operation", "item:read"))
                .exchange()
                .expectStatus().isBadRequest();
    }

    @Test
    void revokeGroupPermission_removesAnExistingGrant() {
        var group = securityRepo.createGroup("access-admin-test-" + UUID.randomUUID());
        authRepo.grantItemTypeIfAbsent(publicDocId(), "GROUP", group.id(), "item-type:read");

        webTestClient.method(org.springframework.http.HttpMethod.DELETE)
                .uri("/api/admin/groups/" + group.id() + "/permissions")
                .header("X-Ntrloc-User", "root")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("itemTypeId", publicDocId().toString(), "operation", "item-type:read"))
                .exchange()
                .expectStatus().isNoContent();

        assertThat(authRepo.findItemTypeGrant(publicDocId(), "GROUP", group.id(), "item-type:read")).isEmpty();
    }

    @Test
    void revokeGroupPermission_forUnknownGroup_returnsNotFound() {
        webTestClient.method(org.springframework.http.HttpMethod.DELETE)
                .uri("/api/admin/groups/" + UUID.randomUUID() + "/permissions")
                .header("X-Ntrloc-User", "root")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("itemTypeId", publicDocId().toString(), "operation", "item-type:read"))
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    void revokeGroupPermission_withoutAnItemTypeId_returnsBadRequest() {
        var group = securityRepo.createGroup("access-admin-test-" + UUID.randomUUID());

        webTestClient.method(org.springframework.http.HttpMethod.DELETE)
                .uri("/api/admin/groups/" + group.id() + "/permissions")
                .header("X-Ntrloc-User", "root")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("operation", "item-type:read"))
                .exchange()
                .expectStatus().isBadRequest();
    }

    @Test
    void revokeGroupPermission_forAGrantThatDoesNotExist_returnsNotFound() {
        var group = securityRepo.createGroup("access-admin-test-" + UUID.randomUUID());

        webTestClient.method(org.springframework.http.HttpMethod.DELETE)
                .uri("/api/admin/groups/" + group.id() + "/permissions")
                .header("X-Ntrloc-User", "root")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("itemTypeId", publicDocId().toString(), "operation", "item-type:read"))
                .exchange()
                .expectStatus().isNotFound();
    }

    // --- User effective permissions ---

    @Test
    void getUserEffectivePermissions_showsAGrantInheritedFromAGroup() {
        List<Map<String, Object>> permissions = getAsRoot("/api/admin/users/" + aliceId() + "/permissions");

        assertThat(permissions).anySatisfy(p -> {
            assertThat(p.get("itemTypeName")).isEqualTo("AclTestPublicDoc");
            List<Map<String, Object>> operations = (List<Map<String, Object>>) (List<?>) p.get("operations");
            assertThat(operations).anySatisfy(op -> {
                assertThat(op.get("operation")).isEqualTo("item-type:read");
                assertThat((List<String>) op.get("via")).contains("viewers");
            });
        });
    }

    @Test
    void getUserEffectivePermissions_groupsMultipleOperationsOnTheSameItemTypeIntoOneEntry() {
        var group = securityRepo.createGroup("access-admin-test-" + UUID.randomUUID());
        var user = securityRepo.createUser("access-admin-test-" + UUID.randomUUID(), "Multi-Op User", null, false);
        securityRepo.addUserToGroup(user.id(), group.id());
        authRepo.grantItemTypeIfAbsent(publicDocId(), "GROUP", group.id(), "item-type:read");
        authRepo.grantItemTypeIfAbsent(publicDocId(), "GROUP", group.id(), "item-type:create");

        List<Map<String, Object>> permissions = getAsRoot("/api/admin/users/" + user.id() + "/permissions");

        assertThat(permissions).anySatisfy(p -> {
            assertThat(p.get("itemTypeName")).isEqualTo("AclTestPublicDoc");
            List<Map<String, Object>> operations = (List<Map<String, Object>>) (List<?>) p.get("operations");
            assertThat(operations).extracting(op -> op.get("operation"))
                    .containsExactlyInAnyOrder("item-type:read", "item-type:create");
        });
    }

    @Test
    void getUserEffectivePermissions_forAUserInNoGroups_returnsAnEmptyList() {
        var user = securityRepo.createUser("access-admin-test-" + UUID.randomUUID(), "No Groups", null, false);

        List<Map<String, Object>> permissions = getAsRoot("/api/admin/users/" + user.id() + "/permissions");

        assertThat(permissions).isEmpty();
    }

    // --- User group memberships ---

    @Test
    void getUserGroups_listsEveryGroupTheUserBelongsTo() {
        List<Map<String, Object>> groups = getAsRoot("/api/admin/users/" + aliceId() + "/groups");

        assertThat(groups).extracting(g -> g.get("name")).contains("viewers");
    }

    // --- Schema item types ---

    @Test
    void listItemTypes_includesKnownItemTypes() {
        List<Map<String, Object>> itemTypes = getAsRoot("/api/admin/schema/item-types");

        assertThat(itemTypes).extracting(t -> t.get("name")).contains("AclTestPublicDoc");
    }
}
