package org.ntrloc.graph.db;

import org.junit.jupiter.api.Test;
import org.ntrloc.graph.AbstractIntegrationTest;
import org.ntrloc.graph.db.mutation.ItemCreateMutation;
import org.ntrloc.graph.db.mutation.MutationRequest;
import org.ntrloc.graph.db.mutation.MutationRequestProcessor;
import org.ntrloc.graph.db.mutation.MutationResponse;
import org.ntrloc.graph.db.partition.authorization.DefaultGroupInitializer;
import org.ntrloc.graph.db.partition.authorization.MarkerAssignmentService;
import org.ntrloc.graph.db.partition.authorization.PermissionService;
import org.ntrloc.graph.db.partition.authorization.repository.AuthorizationRepository;
import org.ntrloc.graph.db.partition.security.NtrlocPrincipal;
import org.ntrloc.graph.db.partition.schema.SchemaManager;
import org.ntrloc.graph.db.projection.SingleItemProjectionSpec;
import org.ntrloc.graph.db.partition.schema.definition.PropertyCardinality;
import org.ntrloc.graph.db.partition.schema.definition.PropertyType;
import org.ntrloc.graph.db.partition.schema.definition.PropertyUsage;
import org.ntrloc.graph.db.partition.schema.definition.mutation.CreateItemDefinitionMutation;
import org.ntrloc.graph.db.partition.schema.definition.mutation.CreatePropertyDefinitionMutation;
import org.ntrloc.graph.db.partition.schema.definition.mutation.CreateTraitDefinitionMutation;
import org.ntrloc.graph.db.partition.schema.definition.mutation.ImplementTraitMutation;
import org.ntrloc.graph.db.partition.security.ResolvedPrincipal;
import org.ntrloc.graph.db.partition.security.repository.SecurityRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

// Phase 2 of item-type inheritance: the cross-type query engine (RegisterPartitionManager.
// projectAcrossTypes, SchemaManager's supertype-inclusive/trait-implementer resolvers, and
// EntityManagerImpl's dispatch between the single-table and cross-type paths), driven end to end
// through the real /api/entity/projection HTTP endpoint against a real Postgres, matching
// AuthorizationEndpointsIntegrationTest's own style for the permission-scoped case.
class EntityCrossTypeProjectionIntegrationTest extends AbstractIntegrationTest {

    private static final ResolvedPrincipal SOME_PRINCIPAL =
            new ResolvedPrincipal(UUID.randomUUID(), "test-user", "Test User", null, Set.of(), true);

    @Autowired
    private SchemaManager schemaManager;

    @Autowired
    private MutationRequestProcessor processor;

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private SecurityRepository securityRepo;

    @Autowired
    private DefaultGroupInitializer defaultGroupInitializer;

    @Autowired
    private MarkerAssignmentService markerAssignmentService;

    @Autowired
    private AuthorizationRepository authRepo;

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private EntityManager entityManager;

    private UUID idOf(String itemTypeName) {
        return schemaManager.getAdminSchema().items().stream()
                .filter(i -> i.name().equals(itemTypeName)).findFirst().orElseThrow().id();
    }

    private UUID createItem(String itemTypeName) {
        return createItem(itemTypeName, Map.of());
    }

    private UUID createItem(String itemTypeName, Map<String, Object> properties) {
        MutationResponse response = processor.process(
                new MutationRequest(List.of(new ItemCreateMutation(null, itemTypeName, properties)), List.of()),
                SOME_PRINCIPAL);
        return response.items().get(0).itemId();
    }

    @SuppressWarnings("unchecked")
    private List<String> itemIdsOf(byte[] responseBody) {
        // Minimal hand-rolled extraction, deliberately not a full JSON library round-trip -- these
        // tests only ever need the list of item ids back.
        String body = new String(responseBody, java.nio.charset.StandardCharsets.UTF_8);
        return java.util.regex.Pattern.compile("\"itemId\":\"([0-9a-fA-F-]{36})\"").matcher(body)
                .results().map(m -> m.group(1)).toList();
    }

    @Test
    void collectionProjection_byASupertype_returnsInstancesOfEveryDescendant() {
        String vehicleName = "CrossTypeVehicle-" + UUID.randomUUID();
        schemaManager.applyMutations(List.of(new CreateItemDefinitionMutation(vehicleName, "d", List.of(), null, false, null)));
        UUID vehicleId = idOf(vehicleName);

        String carName = "CrossTypeCar-" + UUID.randomUUID();
        schemaManager.applyMutations(List.of(new CreateItemDefinitionMutation(carName, "d", List.of(), vehicleId, false, null)));

        UUID vehicleItemId = createItem(vehicleName);
        UUID carItemId = createItem(carName);

        var body = webTestClient.post().uri("/api/entity/projection")
                .header("X-Ntrloc-User", "root")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"itemTypeName\":\"" + vehicleName + "\"}")
                .exchange()
                .expectStatus().isOk()
                .expectBody().returnResult().getResponseBody();

        assertThat(itemIdsOf(body)).containsExactlyInAnyOrder(vehicleItemId.toString(), carItemId.toString());
    }

    @Test
    void collectionProjection_byASupertype_withNoDescendants_stillWorks_theSingleTablePath() {
        // A leaf type with no children resolves to a set of exactly one -- EntityManagerImpl routes
        // this through the original, untouched project() rather than projectAcrossTypes. Not a
        // cross-type scenario at all; included here to pin down that the routing decision itself
        // doesn't break the plain case.
        String leafName = "CrossTypeLeaf-" + UUID.randomUUID();
        schemaManager.applyMutations(List.of(new CreateItemDefinitionMutation(leafName, "d", List.of(), null, false, null)));
        UUID leafItemId = createItem(leafName);

        var body = webTestClient.post().uri("/api/entity/projection")
                .header("X-Ntrloc-User", "root")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"itemTypeName\":\"" + leafName + "\"}")
                .exchange()
                .expectStatus().isOk()
                .expectBody().returnResult().getResponseBody();

        assertThat(itemIdsOf(body)).containsExactly(leafItemId.toString());
    }

    @Test
    void collectionProjection_byARootThreeLevelsUp_returnsEveryLevel() {
        String vehicleName = "CrossTypeVehicle-" + UUID.randomUUID();
        schemaManager.applyMutations(List.of(new CreateItemDefinitionMutation(vehicleName, "d", List.of(), null, false, null)));
        UUID vehicleId = idOf(vehicleName);

        String carName = "CrossTypeCar-" + UUID.randomUUID();
        schemaManager.applyMutations(List.of(new CreateItemDefinitionMutation(carName, "d", List.of(), vehicleId, false, null)));
        UUID carId = idOf(carName);

        String sportsCarName = "CrossTypeSportsCar-" + UUID.randomUUID();
        schemaManager.applyMutations(List.of(new CreateItemDefinitionMutation(sportsCarName, "d", List.of(), carId, false, null)));

        UUID vehicleItemId = createItem(vehicleName);
        UUID carItemId = createItem(carName);
        UUID sportsCarItemId = createItem(sportsCarName);

        var body = webTestClient.post().uri("/api/entity/projection")
                .header("X-Ntrloc-User", "root")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"itemTypeName\":\"" + vehicleName + "\"}")
                .exchange()
                .expectStatus().isOk()
                .expectBody().returnResult().getResponseBody();

        assertThat(itemIdsOf(body)).containsExactlyInAnyOrder(
                vehicleItemId.toString(), carItemId.toString(), sportsCarItemId.toString());
    }

    // The supertype-aware half of trait-implementer resolution: the trait is implemented by the
    // SUPERTYPE, not the subtype directly. AdminItemDefinitionView.traits() alone would miss this
    // (it only reflects direct assignments) -- resolveTraitImplementerItemTypeIds has to walk the
    // chain, matching this session's own design note on why.
    @Test
    void collectionProjection_byATrait_includesASubtypeThatInheritsItFromASupertype() {
        String vehicleName = "CrossTypeVehicle-" + UUID.randomUUID();
        schemaManager.applyMutations(List.of(new CreateItemDefinitionMutation(vehicleName, "d", List.of(), null, false, null)));
        UUID vehicleId = idOf(vehicleName);

        String traitName = "CrossTypeInsurable-" + UUID.randomUUID();
        schemaManager.applyMutations(List.of(new CreateTraitDefinitionMutation(traitName, "d", List.of())));
        UUID traitId = schemaManager.getAdminSchema().traits().stream()
                .filter(t -> t.name().equals(traitName)).findFirst().orElseThrow().id();
        schemaManager.applyMutations(List.of(new ImplementTraitMutation(vehicleId, traitId)));

        String carName = "CrossTypeCar-" + UUID.randomUUID();
        schemaManager.applyMutations(List.of(new CreateItemDefinitionMutation(carName, "d", List.of(), vehicleId, false, null)));

        String unrelatedName = "CrossTypeUnrelated-" + UUID.randomUUID();
        schemaManager.applyMutations(List.of(new CreateItemDefinitionMutation(unrelatedName, "d", List.of(), null, false, null)));

        UUID carItemId = createItem(carName);
        createItem(unrelatedName);

        var body = webTestClient.post().uri("/api/entity/projection")
                .header("X-Ntrloc-User", "root")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"traitName\":\"" + traitName + "\"}")
                .exchange()
                .expectStatus().isOk()
                .expectBody().returnResult().getResponseBody();

        assertThat(itemIdsOf(body)).containsExactly(carItemId.toString());
    }

    @Test
    void collectionProjection_sortAndFilterOnAnInheritedProperty_workAcrossBranches() {
        String vehicleName = "CrossTypeVehicle-" + UUID.randomUUID();
        schemaManager.applyMutations(List.of(new CreateItemDefinitionMutation(vehicleName, "d", List.of(
                new CreatePropertyDefinitionMutation("wheels", "d", PropertyType.INT, PropertyCardinality.SINGLE, PropertyUsage.OPTIONAL, false, java.util.List.of())),
                null, false, null)));
        UUID vehicleId = idOf(vehicleName);

        String carName = "CrossTypeCar-" + UUID.randomUUID();
        schemaManager.applyMutations(List.of(new CreateItemDefinitionMutation(carName, "d", List.of(), vehicleId, false, null)));

        UUID vehicle4 = createItem(vehicleName, Map.of("wheels", 4));
        UUID car2 = createItem(carName, Map.of("wheels", 2));
        UUID car6 = createItem(carName, Map.of("wheels", 6));

        // Sort ascending by the inherited property, resolved against the query root (Vehicle) but
        // applied across both branches.
        var sortedBody = webTestClient.post().uri("/api/entity/projection")
                .header("X-Ntrloc-User", "root")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"itemTypeName\":\"" + vehicleName + "\",\"sortField\":\"wheels\",\"sortDirection\":\"ASC\"}")
                .exchange()
                .expectStatus().isOk()
                .expectBody().returnResult().getResponseBody();

        assertThat(itemIdsOf(sortedBody)).containsExactly(car2.toString(), vehicle4.toString(), car6.toString());

        // Filter across branches: only the Car with wheels=6 should match.
        var filteredBody = webTestClient.post().uri("/api/entity/projection")
                .header("X-Ntrloc-User", "root")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"itemTypeName":"%s","filter":{"type":"PROPERTY_VALUE","propertyName":"wheels","operator":"EQUALS","value":"6"}}
                        """.formatted(vehicleName))
                .exchange()
                .expectStatus().isOk()
                .expectBody().returnResult().getResponseBody();

        assertThat(itemIdsOf(filteredBody)).containsExactly(car6.toString());
    }

    @Test
    void collectionProjection_facetsAndFacetFiltersOnAnInheritedProperty_aggregateAcrossBranches() {
        String vehicleName = "CrossTypeVehicle-" + UUID.randomUUID();
        schemaManager.applyMutations(List.of(new CreateItemDefinitionMutation(vehicleName, "d", List.of(
                new CreatePropertyDefinitionMutation("electric", "d", PropertyType.BOOLEAN, PropertyCardinality.SINGLE, PropertyUsage.OPTIONAL, true, java.util.List.of())),
                null, false, null)));
        UUID vehicleId = idOf(vehicleName);

        String carName = "CrossTypeCar-" + UUID.randomUUID();
        schemaManager.applyMutations(List.of(new CreateItemDefinitionMutation(carName, "d", List.of(), vehicleId, false, null)));

        createItem(vehicleName, Map.of("electric", true));
        UUID gasCar = createItem(carName, Map.of("electric", false));
        createItem(carName, Map.of("electric", true));

        // facets: auto-populated across the whole polymorphic result set.
        var facetBody = webTestClient.post().uri("/api/entity/projection")
                .header("X-Ntrloc-User", "root")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"itemTypeName\":\"" + vehicleName + "\",\"facets\":[]}")
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class).returnResult().getResponseBody();
        assertThat(facetBody).contains("\"true\"").contains("\"false\"").contains("\"count\":2").contains("\"count\":1");

        // facetFilters: narrows facetedCount but leaves totalCount alone, same contract as the
        // single-table path -- here spanning both branches.
        var filteredBody = webTestClient.post().uri("/api/entity/projection")
                .header("X-Ntrloc-User", "root")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"itemTypeName":"%s","facetFilters":[{"type":"TERMS","field":"electric","values":["false"],"includeNull":false}]}
                        """.formatted(vehicleName))
                .exchange()
                .expectStatus().isOk()
                .expectBody().returnResult().getResponseBody();
        assertThat(itemIdsOf(filteredBody)).containsExactly(gasCar.toString());
    }

    @Test
    void collectionProjection_sortedBySystemField_worksAcrossBranches() {
        String vehicleName = "CrossTypeVehicle-" + UUID.randomUUID();
        schemaManager.applyMutations(List.of(new CreateItemDefinitionMutation(vehicleName, "d", List.of(), null, false, null)));
        UUID vehicleId = idOf(vehicleName);

        String carName = "CrossTypeCar-" + UUID.randomUUID();
        schemaManager.applyMutations(List.of(new CreateItemDefinitionMutation(carName, "d", List.of(), vehicleId, false, null)));

        UUID vehicleItemId = createItem(vehicleName);
        UUID carItemId = createItem(carName);

        var body = webTestClient.post().uri("/api/entity/projection")
                .header("X-Ntrloc-User", "root")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"itemTypeName\":\"" + vehicleName + "\",\"sortField\":\"itemId\",\"sortDirection\":\"ASC\"}")
                .exchange()
                .expectStatus().isOk()
                .expectBody().returnResult().getResponseBody();

        assertThat(itemIdsOf(body)).containsExactlyInAnyOrder(vehicleItemId.toString(), carItemId.toString());
    }

    // Direct item-type:read grant revocation, mirroring AuthorizationTestDataInitializer's own
    // revoke-default-then-grant-explicitly pattern rather than inventing a new one. Vehicle keeps
    // its default "everyone" read grant; Car's is revoked and never re-granted to "root2" -- so a
    // polymorphic query by a principal who can read Vehicle but not Car should see only the
    // Vehicle instance, not fail outright (the partial-drop case), while a principal who can't
    // read *either* branch still gets 404 (the empty-after-filtering case), not an empty 200.
    @Test
    void collectionProjection_silentlyDropsAnUnreadableBranch_butStillWorksForReadableOnes() {
        String vehicleName = "CrossTypeVehicle-" + UUID.randomUUID();
        schemaManager.applyMutations(List.of(new CreateItemDefinitionMutation(vehicleName, "d", List.of(), null, false, null)));
        UUID vehicleId = idOf(vehicleName);

        String carName = "CrossTypeCar-" + UUID.randomUUID();
        schemaManager.applyMutations(List.of(new CreateItemDefinitionMutation(carName, "d", List.of(), vehicleId, false, null)));
        UUID carId = idOf(carName);

        revokeDefaultReadGrant(carId);

        // DefaultGroupInitializer only back-fills *existing* users into "everyone" once, at
        // ApplicationReadyEvent -- a user created here, mid-test, is never auto-added the way
        // alice/bob/carol/root were at boot, so joining explicitly is required to pick up
        // Vehicle's still-intact default read grant at all.
        var restrictedUser = securityRepo.createUser("restricted-" + UUID.randomUUID(), "Restricted", null, false);
        securityRepo.addUserToGroup(restrictedUser.id(), defaultGroupInitializer.getDefaultGroupId());

        UUID vehicleItemId = createItem(vehicleName);
        createItem(carName);

        // Type-level read (revoked/kept above) is a prerequisite, not sufficient on its own --
        // instance-level item:read (marker-based, see PermissionServiceInstanceReadIntegrationTest)
        // is a separate, also-real gate now. Grant it on the Vehicle instance specifically so this
        // test keeps exercising type-level partial-branch-drop, the thing it's actually about,
        // without being blocked by the orthogonal instance-level check.
        var marker = authRepo.createMarker("cross-type-vehicle-read-" + UUID.randomUUID(), "test fixture");
        markerAssignmentService.addItemMarker(vehicleItemId, marker.id(), "test-actor", "test reason");
        authRepo.grantMarker(marker.id(), "GROUP", defaultGroupInitializer.getDefaultGroupId(), PermissionService.ITEM_READ, null);

        var body = webTestClient.post().uri("/api/entity/projection")
                .header("X-Ntrloc-User", restrictedUser.externalId())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"itemTypeName\":\"" + vehicleName + "\"}")
                .exchange()
                .expectStatus().isOk()
                .expectBody().returnResult().getResponseBody();

        assertThat(itemIdsOf(body)).containsExactly(vehicleItemId.toString());
    }

    @Test
    void collectionProjection_withNeitherOrBothOfItemTypeNameAndTraitName_isRejected() {
        webTestClient.post().uri("/api/entity/projection")
                .header("X-Ntrloc-User", "root")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{}")
                .exchange()
                .expectStatus().isBadRequest();
    }

    @Test
    void collectionProjection_byAnUnknownTraitName_returnsNotFound() {
        webTestClient.post().uri("/api/entity/projection")
                .header("X-Ntrloc-User", "root")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"traitName\":\"NoSuchTrait-" + UUID.randomUUID() + "\"}")
                .exchange()
                .expectStatus().isNotFound();
    }

    // --- SingleItemProjectionSpec (not JSON-bound, used internally/by process scripts -- see
    // EntityController, which has no endpoint for it) -- exercised directly via the bean rather
    // than HTTP, same polymorphic-by-default resolution as the collection path above.

    @Test
    void singleItemProjection_byASupertypeName_findsASubtypeInstance() {
        String vehicleName = "CrossTypeVehicle-" + UUID.randomUUID();
        schemaManager.applyMutations(List.of(new CreateItemDefinitionMutation(vehicleName, "d", List.of(), null, false, null)));
        UUID vehicleId = idOf(vehicleName);

        String carName = "CrossTypeCar-" + UUID.randomUUID();
        schemaManager.applyMutations(List.of(new CreateItemDefinitionMutation(carName, "d", List.of(), vehicleId, false, null)));

        UUID carItemId = createItem(carName);

        var result = entityManager.project(new SingleItemProjectionSpec(vehicleName, carItemId), "http://binary", SOME_PRINCIPAL);

        assertThat(result).isPresent();
        assertThat(result.get().itemId()).isEqualTo(carItemId);
        assertThat(result.get().itemType()).isEqualTo(carName);
    }

    @Test
    void singleItemProjection_forAnItemOutsideTheQueriedTypesOwnLineage_returnsEmpty() {
        String vehicleName = "CrossTypeVehicle-" + UUID.randomUUID();
        schemaManager.applyMutations(List.of(new CreateItemDefinitionMutation(vehicleName, "d", List.of(), null, false, null)));

        String unrelatedName = "CrossTypeUnrelated-" + UUID.randomUUID();
        schemaManager.applyMutations(List.of(new CreateItemDefinitionMutation(unrelatedName, "d", List.of(), null, false, null)));
        UUID unrelatedItemId = createItem(unrelatedName);

        // unrelatedItemId genuinely exists, but its type isn't Vehicle or a descendant of it.
        var result = entityManager.project(new SingleItemProjectionSpec(vehicleName, unrelatedItemId), "http://binary", SOME_PRINCIPAL);

        assertThat(result).isEmpty();
    }

    @Test
    void singleItemProjection_forAnUnreadableType_throwsNotFound() {
        String vehicleName = "CrossTypeVehicle-" + UUID.randomUUID();
        schemaManager.applyMutations(List.of(new CreateItemDefinitionMutation(vehicleName, "d", List.of(), null, false, null)));
        UUID vehicleId = idOf(vehicleName);
        revokeDefaultReadGrant(vehicleId);

        UUID vehicleItemId = createItem(vehicleName);

        var restrictedUser = securityRepo.createUser("restricted-" + UUID.randomUUID(), "Restricted", null, false);
        securityRepo.addUserToGroup(restrictedUser.id(), defaultGroupInitializer.getDefaultGroupId());
        NtrlocPrincipal restrictedPrincipal = new ResolvedPrincipal(restrictedUser.id(), restrictedUser.externalId(), "Restricted", null, Set.of(), false);

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        entityManager.project(new SingleItemProjectionSpec(vehicleName, vehicleItemId), "http://binary", restrictedPrincipal))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                .hasMessageContaining("404");
    }

    private void revokeDefaultReadGrant(UUID itemTypeId) {
        jdbcClient.sql("""
                DELETE FROM authorization_item_type_grant
                WHERE item_type_id = :itemTypeId AND permission = 'item-type:read'
                  AND principal_type = 'GROUP' AND principal_id = :groupId
                """)
                .param("itemTypeId", itemTypeId)
                .param("groupId", defaultGroupInitializer.getDefaultGroupId())
                .update();
    }
}
