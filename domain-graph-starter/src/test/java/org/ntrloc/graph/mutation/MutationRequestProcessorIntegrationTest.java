package org.ntrloc.graph.mutation;

import org.junit.jupiter.api.Test;
import org.ntrloc.graph.AbstractIntegrationTest;
import org.ntrloc.graph.db.coordinator.CoordinatorTestDomainInitializer;
import org.ntrloc.graph.db.partition.authorization.MarkerAssignmentService;
import org.ntrloc.graph.db.partition.authorization.PermissionService;
import org.ntrloc.graph.db.partition.authorization.repository.AuthorizationRepository;
import org.ntrloc.graph.db.partition.binary.BinaryPartitionManager;
import org.ntrloc.graph.db.partition.register.RegisterPartitionManager;
import org.ntrloc.graph.db.partition.schema.ControlledListManager;
import org.ntrloc.graph.db.partition.schema.SchemaManager;
import org.ntrloc.graph.db.partition.schema.definition.PropertyCardinality;
import org.ntrloc.graph.db.partition.schema.definition.PropertyType;
import org.ntrloc.graph.db.partition.schema.definition.PropertyUsage;
import org.ntrloc.graph.db.partition.schema.definition.mutation.CreateItemDefinitionMutation;
import org.ntrloc.graph.db.partition.schema.definition.mutation.CreateLinkDefinitionMutation;
import org.ntrloc.graph.db.partition.schema.definition.mutation.CreatePerspectiveDefinitionMutation;
import org.ntrloc.graph.db.partition.schema.definition.mutation.CreatePropertyDefinitionMutation;
import org.ntrloc.graph.db.partition.schema.definition.mutation.CreatePropertyGroupDefinitionMutation;
import org.ntrloc.graph.db.partition.schema.definition.mutation.DeleteItemDefinitionMutation;
import org.ntrloc.graph.db.partition.schema.definition.mutation.DeletePropertyDefinitionMutation;
import org.ntrloc.graph.db.partition.schema.definition.mutation.ExistingControlledListReference;
import org.ntrloc.graph.db.partition.schema.definition.mutation.SetPropertyControlledListMutation;
import org.ntrloc.graph.db.partition.schema.definition.mutation.UpdateItemDefinitionMutation;
import org.ntrloc.graph.db.partition.schema.definition.mutation.UpdatePropertyDefinitionMutation;
import org.ntrloc.graph.db.partition.security.ResolvedPrincipal;
import org.ntrloc.graph.db.partition.security.repository.SecurityRepository;
import org.ntrloc.graph.projection.ProjectedLink;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import reactor.core.publisher.Flux;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// Covers MutationRequestProcessor's remaining uncovered surface directly (bean-level, not via
// MutationController's HTTP layer) -- MutationControllerIntegrationTest already exercises the
// item-create/update/delete and link-create happy/error paths extensively; this class targets
// specifically what jacoco showed as still uncovered: a null items/links request, a null
// principal, the LinkUpdateMutation/LinkDeleteMutation top-level dispatch, an unknown perspective
// name, resolveLinkTypeId's "no common link"/"ambiguous link" branches (neither reachable via
// CoordinatorTestDomainInitializer's single-link fixture -- see
// MutationRequestProcessorTestDomainInitializer's own comment for why a second fixture was
// needed), the null-properties and null-property-value ("clear this property") branches, and the
// BINARY/LONG/DATETIME validateScalar branches (CoordinatorTestDomainInitializer only has
// STRING/DATE properties).
class MutationRequestProcessorIntegrationTest extends AbstractIntegrationTest {

    private static final String PRODUCT_TYPE = "CoordinatorTestProduct";
    private static final String CONTRIBUTOR_TYPE = "CoordinatorTestContributor";
    private static final String PRODUCT_PERSPECTIVE = "products";
    private static final String CONTRIBUTOR_PERSPECTIVE = "contributors";

    private static final ResolvedPrincipal SOME_PRINCIPAL =
            new ResolvedPrincipal(UUID.randomUUID(), "test-user", "Test User", null, Set.of(), true);

    @Autowired
    private MutationRequestProcessor processor;

    @Autowired
    private RegisterPartitionManager registerPartitionManager;

    @Autowired
    private CoordinatorTestDomainInitializer coordinatorFixture;

    @Autowired
    private MutationRequestProcessorTestDomainInitializer fixture;

    @Autowired
    private SchemaManager schemaManager;

    @Autowired
    private BinaryPartitionManager binaryPartitionManager;

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private ControlledListManager controlledListManager;

    @Autowired
    private AuthorizationRepository authRepo;

    @Autowired
    private SecurityRepository securityRepo;

    @Autowired
    private MarkerAssignmentService markerAssignmentService;

    private UUID storeBinary(byte[] content) {
        var buffer = new DefaultDataBufferFactory().wrap(content);
        return binaryPartitionManager.store(Flux.just(buffer)).block();
    }

    private UUID createItem(String itemTypeName) {
        MutationResponse response = processor.process(
                new MutationRequest(List.of(new ItemCreateMutation(null, itemTypeName, Map.of())), List.of()),
                SOME_PRINCIPAL);
        return response.items().get(0).itemId();
    }

    // --- Null items/links, null principal ---

    @Test
    void nullItemsAndLinks_isTreatedAsAnEmptyRequest() {
        MutationResponse response = processor.process(new MutationRequest(null, null), SOME_PRINCIPAL);

        assertThat(response.items()).isEmpty();
        assertThat(response.links()).isEmpty();
    }

    @Test
    void nullPrincipal_isAcceptedAndAttributesNothing_attributionIsBestEffortNotRequired() {
        MutationResponse response = processor.process(new MutationRequest(null, null), null);

        assertThat(response.items()).isEmpty();
    }

    // --- Link update/delete top-level dispatch ---

    @Test
    void linkUpdateMutation_persistsThePropertyChange() {
        UUID productId = createItem(PRODUCT_TYPE);
        UUID contributorId = createItem(CONTRIBUTOR_TYPE);
        MutationResponse createLinkResponse = processor.process(new MutationRequest(List.of(), List.of(
                new LinkCreateMutation(
                        new LinkEndpointReference(PRODUCT_PERSPECTIVE, new ExistingItemReference(productId)),
                        new LinkEndpointReference(CONTRIBUTOR_PERSPECTIVE, new ExistingItemReference(contributorId)),
                        Map.of("role", "author")))), SOME_PRINCIPAL);
        UUID linkId = createLinkResponse.links().get(0).linkId();

        processor.process(new MutationRequest(List.of(),
                List.of(new LinkUpdateMutation(linkId, Map.of("role", "editor")))), SOME_PRINCIPAL);

        var product = registerPartitionManager.projectOne(coordinatorFixture.productTypeId(), productId, "http://binary").orElseThrow();
        assertThat(product.links().values().stream().flatMap(List::stream))
                .anyMatch(link -> link.item().itemId().equals(contributorId) && link.properties().get("role").equals("editor"));
    }

    @Test
    void linkUpdateMutation_forAnUnknownLinkId_reportsAValidationError() {
        assertThatThrownBy(() -> processor.process(new MutationRequest(List.of(),
                List.of(new LinkUpdateMutation(UUID.randomUUID(), Map.of()))), SOME_PRINCIPAL))
                .isInstanceOf(MutationValidationException.class)
                .satisfies(e -> assertThat(((MutationValidationException) e).errors())
                        .anyMatch(err -> err.path().equals("links[0].linkId")));
    }

    @Test
    void linkDeleteMutation_removesTheLink() {
        UUID productId = createItem(PRODUCT_TYPE);
        UUID contributorId = createItem(CONTRIBUTOR_TYPE);
        MutationResponse createLinkResponse = processor.process(new MutationRequest(List.of(), List.of(
                new LinkCreateMutation(
                        new LinkEndpointReference(PRODUCT_PERSPECTIVE, new ExistingItemReference(productId)),
                        new LinkEndpointReference(CONTRIBUTOR_PERSPECTIVE, new ExistingItemReference(contributorId)),
                        Map.of()))), SOME_PRINCIPAL);
        UUID linkId = createLinkResponse.links().get(0).linkId();

        MutationResponse deleteResponse = processor.process(new MutationRequest(List.of(),
                List.of(new LinkDeleteMutation(linkId))), SOME_PRINCIPAL);

        assertThat(deleteResponse.links()).extracting(r -> r.operation().name()).containsExactly("DELETE");
        var product = registerPartitionManager.projectOne(coordinatorFixture.productTypeId(), productId, "http://binary").orElseThrow();
        assertThat(product.links().values().stream().flatMap(List::stream)).isEmpty();
    }

    // --- resolveEndpoint: unknown perspective name ---

    @Test
    void linkCreateMutation_withAnUnknownPerspectiveName_reportsAValidationError() {
        UUID productId = createItem(PRODUCT_TYPE);
        UUID contributorId = createItem(CONTRIBUTOR_TYPE);

        assertThatThrownBy(() -> processor.process(new MutationRequest(List.of(), List.of(
                new LinkCreateMutation(
                        new LinkEndpointReference("noSuchPerspective", new ExistingItemReference(productId)),
                        new LinkEndpointReference(CONTRIBUTOR_PERSPECTIVE, new ExistingItemReference(contributorId)),
                        Map.of()))), SOME_PRINCIPAL))
                .isInstanceOf(MutationValidationException.class)
                .satisfies(e -> assertThat(((MutationValidationException) e).errors())
                        .anyMatch(err -> err.path().equals("links[0].firstItem.perspectiveName")));
    }

    // --- resolveLinkTypeId: no common link type / ambiguous link type ---

    @Test
    void linkCreateMutation_betweenPerspectivesThatShareNoLinkType_reportsAValidationError() {
        UUID aId = createItem("MutReqProcA");
        UUID cId = createItem("MutReqProcC");

        assertThatThrownBy(() -> processor.process(new MutationRequest(List.of(), List.of(
                new LinkCreateMutation(
                        new LinkEndpointReference("toB", new ExistingItemReference(aId)),
                        new LinkEndpointReference("onlyC", new ExistingItemReference(cId)),
                        Map.of()))), SOME_PRINCIPAL))
                .isInstanceOf(MutationValidationException.class)
                .satisfies(e -> assertThat(((MutationValidationException) e).errors())
                        .anyMatch(err -> err.message().contains("No link connects")));
    }

    @Test
    void linkCreateMutation_betweenPerspectivesConnectedByMultipleLinkTypes_reportsAValidationError() {
        UUID aId = createItem("MutReqProcA");
        UUID bId = createItem("MutReqProcB");

        assertThatThrownBy(() -> processor.process(new MutationRequest(List.of(), List.of(
                new LinkCreateMutation(
                        new LinkEndpointReference("toB", new ExistingItemReference(aId)),
                        new LinkEndpointReference("fromA", new ExistingItemReference(bId)),
                        Map.of()))), SOME_PRINCIPAL))
                .isInstanceOf(MutationValidationException.class)
                .satisfies(e -> assertThat(((MutationValidationException) e).errors())
                        .anyMatch(err -> err.message().contains("Ambiguous link")));
    }

    // --- resolvePropertyIds: null properties map, null property value ---

    @Test
    void itemCreateMutation_withNullProperties_succeedsWithNoPropertiesSet() {
        MutationResponse response = processor.process(new MutationRequest(
                List.of(new ItemCreateMutation(null, PRODUCT_TYPE, null)), List.of()), SOME_PRINCIPAL);

        UUID itemId = response.items().get(0).itemId();
        var item = registerPartitionManager.projectOne(coordinatorFixture.productTypeId(), itemId, "http://binary").orElseThrow();
        assertThat(item.properties()).doesNotContainKey("name");
    }

    @Test
    void itemUpdateMutation_withAnExplicitNullPropertyValue_clearsThatProperty() {
        UUID productId = processor.process(new MutationRequest(
                List.of(new ItemCreateMutation(null, PRODUCT_TYPE, Map.of("name", "Widget"))), List.of()), SOME_PRINCIPAL)
                .items().get(0).itemId();

        Map<String, Object> clearName = new HashMap<>();
        clearName.put("name", null);
        processor.process(new MutationRequest(
                List.of(new ItemUpdateMutation(productId, clearName)), List.of()), SOME_PRINCIPAL);

        var item = registerPartitionManager.projectOne(coordinatorFixture.productTypeId(), productId, "http://binary").orElseThrow();
        assertThat(item.properties()).doesNotContainKey("name");
    }

    // --- Required properties (PropertyUsage.REQUIRED) ---
    //
    // Covers creation-time (missing-at-create), update-time explicit-null, and update-time
    // retroactive (became REQUIRED after the item was created) enforcement, all in
    // MutationRequestProcessor. Each item type is a fresh, UUID-suffixed one defined inline, not a
    // shared fixture -- CoordinatorTestDomainInitializer and MutationRequestProcessorTestDomainInitializer
    // both hardcode every property OPTIONAL and are relied on elsewhere to accept Map.of(); a
    // REQUIRED property on either would break those.

    @Test
    void itemCreateMutation_missingARequiredProperty_reportsAValidationErrorNamingIt() {
        String typeName = "MutReqProcRequired-" + UUID.randomUUID();
        schemaManager.applyMutations(List.of(new CreateItemDefinitionMutation(typeName, "d", List.of(
                new CreatePropertyDefinitionMutation("title", "d", PropertyType.STRING, PropertyCardinality.SINGLE, PropertyUsage.REQUIRED, false)),
                null, false, null)));

        assertThatThrownBy(() -> processor.process(new MutationRequest(
                List.of(new ItemCreateMutation(null, typeName, Map.of())), List.of()), SOME_PRINCIPAL))
                .isInstanceOf(MutationValidationException.class)
                .satisfies(e -> assertThat(((MutationValidationException) e).errors())
                        .anyMatch(err -> err.path().equals("items[0].properties.title") && err.message().toLowerCase().contains("required")));
    }

    @Test
    void itemCreateMutation_missingMultipleRequiredProperties_reportsAValidationErrorForEachOneButNotForOptionalOnes() {
        String typeName = "MutReqProcRequiredMulti-" + UUID.randomUUID();
        schemaManager.applyMutations(List.of(new CreateItemDefinitionMutation(typeName, "d", List.of(
                new CreatePropertyDefinitionMutation("title", "d", PropertyType.STRING, PropertyCardinality.SINGLE, PropertyUsage.REQUIRED, false),
                new CreatePropertyDefinitionMutation("author", "d", PropertyType.STRING, PropertyCardinality.SINGLE, PropertyUsage.REQUIRED, false),
                new CreatePropertyDefinitionMutation("subtitle", "d", PropertyType.STRING, PropertyCardinality.SINGLE, PropertyUsage.OPTIONAL, false)),
                null, false, null)));

        assertThatThrownBy(() -> processor.process(new MutationRequest(
                List.of(new ItemCreateMutation(null, typeName, Map.of())), List.of()), SOME_PRINCIPAL))
                .isInstanceOf(MutationValidationException.class)
                .satisfies(e -> {
                    List<ValidationError> errors = ((MutationValidationException) e).errors();
                    assertThat(errors).anyMatch(err -> err.path().equals("items[0].properties.title"));
                    assertThat(errors).anyMatch(err -> err.path().equals("items[0].properties.author"));
                    assertThat(errors).noneMatch(err -> err.path().equals("items[0].properties.subtitle"));
                });
    }

    @Test
    void itemCreateMutation_providingEveryRequiredProperty_succeeds() {
        String typeName = "MutReqProcRequiredOk-" + UUID.randomUUID();
        schemaManager.applyMutations(List.of(new CreateItemDefinitionMutation(typeName, "d", List.of(
                new CreatePropertyDefinitionMutation("title", "d", PropertyType.STRING, PropertyCardinality.SINGLE, PropertyUsage.REQUIRED, false)),
                null, false, null)));

        MutationResponse response = processor.process(new MutationRequest(
                List.of(new ItemCreateMutation(null, typeName, Map.of("title", "My Title"))), List.of()), SOME_PRINCIPAL);

        assertThat(response.items()).hasSize(1);
    }

    @Test
    void itemUpdateMutation_settingARequiredPropertyToNull_reportsAValidationErrorNamingIt() {
        String typeName = "MutReqProcRequiredClear-" + UUID.randomUUID();
        schemaManager.applyMutations(List.of(new CreateItemDefinitionMutation(typeName, "d", List.of(
                new CreatePropertyDefinitionMutation("title", "d", PropertyType.STRING, PropertyCardinality.SINGLE, PropertyUsage.REQUIRED, false)),
                null, false, null)));
        UUID itemId = processor.process(new MutationRequest(
                List.of(new ItemCreateMutation(null, typeName, Map.of("title", "Original Title"))), List.of()), SOME_PRINCIPAL)
                .items().get(0).itemId();

        Map<String, Object> clearTitle = new HashMap<>();
        clearTitle.put("title", null);

        assertThatThrownBy(() -> processor.process(new MutationRequest(
                List.of(new ItemUpdateMutation(itemId, clearTitle)), List.of()), SOME_PRINCIPAL))
                .isInstanceOf(MutationValidationException.class)
                .satisfies(e -> assertThat(((MutationValidationException) e).errors())
                        .anyMatch(err -> err.path().equals("items[0].properties.title") && err.message().toLowerCase().contains("null")));
    }

    // The schema can change after an item already exists -- a property that was OPTIONAL (or didn't
    // exist at all) when an item was created can become REQUIRED later, same as
    // markingAnItemTypeAbstract_doesNotAffectExistingInstances_onlyBlocksNewOnes' own abstract-type
    // case: the existing item is never retroactively touched or invalidated just by the schema
    // changing underneath it, it's still readable with "notes" simply absent. But the next time
    // *this* item is mutated, that mutation's own resulting state has to satisfy the schema as it
    // stands today -- not as it stood when the item was created -- so a mutation that still leaves
    // "notes" unset, even one that never mentions "notes" at all (it only touches the unrelated
    // "title" property here), has to fail the same way creating a brand new item without it would.
    // Not yet enforced: resolvePropertyIds/checkRequiredItemProperties only ever look at the keys a
    // payload itself supplies (or, for create, the full schema against an empty create payload) --
    // neither one currently reads the item's own *existing* stored property values at all, so there
    // is nothing today that would notice this item was already missing "notes" before this update
    // even ran.
    @Test
    void itemUpdateMutation_omittingAPropertyThatBecameRequiredAfterTheItemWasCreated_reportsAValidationErrorSayingItMustBeSet() {
        String typeName = "MutReqProcRetroRequired-" + UUID.randomUUID();
        schemaManager.applyMutations(List.of(new CreateItemDefinitionMutation(typeName, "d", List.of(
                new CreatePropertyDefinitionMutation("notes", "d", PropertyType.STRING, PropertyCardinality.SINGLE, PropertyUsage.OPTIONAL, false),
                new CreatePropertyDefinitionMutation("title", "d", PropertyType.STRING, PropertyCardinality.SINGLE, PropertyUsage.OPTIONAL, false)),
                null, false, null)));
        UUID notesPropertyId = schemaManager.getAdminSchema().items().stream()
                .filter(i -> i.name().equals(typeName)).findFirst().orElseThrow()
                .properties().stream().filter(p -> p.name().equals("notes")).findFirst().orElseThrow().id();

        // "notes" is optional at creation time, so leaving it unset is valid here.
        UUID itemId = processor.process(new MutationRequest(
                List.of(new ItemCreateMutation(null, typeName, Map.of())), List.of()), SOME_PRINCIPAL)
                .items().get(0).itemId();

        schemaManager.applyMutations(List.of(new UpdatePropertyDefinitionMutation(
                notesPropertyId, "notes", "d", PropertyType.STRING, PropertyCardinality.SINGLE, PropertyUsage.REQUIRED, false)));

        assertThatThrownBy(() -> processor.process(new MutationRequest(
                List.of(new ItemUpdateMutation(itemId, Map.of("title", "A Title"))), List.of()), SOME_PRINCIPAL))
                .isInstanceOf(MutationValidationException.class)
                .satisfies(e -> assertThat(((MutationValidationException) e).errors())
                        .anyMatch(err -> err.path().equals("items[0].properties.notes") && err.message().toLowerCase().contains("must be set")));
    }

    // --- Deprecated properties (PropertyUsage.DEPRECATED) ---
    //
    // Covers both creation-time and update-time enforcement in MutationRequestProcessor.

    @Test
    void itemCreateMutation_settingADeprecatedProperty_reportsAValidationErrorNamingIt() {
        String typeName = "MutReqProcDeprecated-" + UUID.randomUUID();
        schemaManager.applyMutations(List.of(new CreateItemDefinitionMutation(typeName, "d", List.of(
                new CreatePropertyDefinitionMutation("legacyCode", "d", PropertyType.STRING, PropertyCardinality.SINGLE, PropertyUsage.DEPRECATED, false)),
                null, false, null)));

        assertThatThrownBy(() -> processor.process(new MutationRequest(
                List.of(new ItemCreateMutation(null, typeName, Map.of("legacyCode", "ABC"))), List.of()), SOME_PRINCIPAL))
                .isInstanceOf(MutationValidationException.class)
                .satisfies(e -> assertThat(((MutationValidationException) e).errors())
                        .anyMatch(err -> err.path().equals("items[0].properties.legacyCode") && err.message().toLowerCase().contains("deprecated")));
    }

    // A property can become DEPRECATED after an item already exists (e.g. it was OPTIONAL at
    // creation time), the same retroactive-schema-change shape as the REQUIRED case above -- the
    // existing item isn't touched just by the schema changing underneath it, but a later update that
    // sets the now-deprecated property has to be rejected the same way creating a new item with it
    // set would be.
    @Test
    void itemUpdateMutation_settingADeprecatedProperty_reportsAValidationErrorNamingIt() {
        String typeName = "MutReqProcDeprecatedUpdate-" + UUID.randomUUID();
        schemaManager.applyMutations(List.of(new CreateItemDefinitionMutation(typeName, "d", List.of(
                new CreatePropertyDefinitionMutation("legacyCode", "d", PropertyType.STRING, PropertyCardinality.SINGLE, PropertyUsage.OPTIONAL, false)),
                null, false, null)));
        UUID legacyCodePropertyId = schemaManager.getAdminSchema().items().stream()
                .filter(i -> i.name().equals(typeName)).findFirst().orElseThrow()
                .properties().stream().filter(p -> p.name().equals("legacyCode")).findFirst().orElseThrow().id();

        UUID itemId = processor.process(new MutationRequest(
                List.of(new ItemCreateMutation(null, typeName, Map.of())), List.of()), SOME_PRINCIPAL)
                .items().get(0).itemId();

        schemaManager.applyMutations(List.of(new UpdatePropertyDefinitionMutation(
                legacyCodePropertyId, "legacyCode", "d", PropertyType.STRING, PropertyCardinality.SINGLE, PropertyUsage.DEPRECATED, false)));

        assertThatThrownBy(() -> processor.process(new MutationRequest(
                List.of(new ItemUpdateMutation(itemId, Map.of("legacyCode", "ABC"))), List.of()), SOME_PRINCIPAL))
                .isInstanceOf(MutationValidationException.class)
                .satisfies(e -> assertThat(((MutationValidationException) e).errors())
                        .anyMatch(err -> err.path().equals("items[0].properties.legacyCode") && err.message().toLowerCase().contains("deprecated")));
    }

    // --- Required and deprecated link properties (mirrors the item-side sections above) ---
    //
    // A fresh link type between two fresh, UUID-suffixed item types, defined inline per test -- the
    // shared fixtures' own link (CoordinatorTestDomainInitializer's C/D link) has every property
    // OPTIONAL and is relied on elsewhere to accept Map.of()/arbitrary values, same reasoning as the
    // item-side sections' own fixture note.

    private record FreshLinkFixture(String perspectiveA, String perspectiveB, UUID aItemId, UUID bItemId) {
        LinkCreateMutation create(Map<String, Object> properties) {
            return new LinkCreateMutation(
                    new LinkEndpointReference(perspectiveA, new ExistingItemReference(aItemId)),
                    new LinkEndpointReference(perspectiveB, new ExistingItemReference(bItemId)),
                    properties);
        }
    }

    private FreshLinkFixture createFreshLinkType(List<CreatePropertyDefinitionMutation> properties) {
        String aName = "MutReqProcLinkA-" + UUID.randomUUID();
        String bName = "MutReqProcLinkB-" + UUID.randomUUID();
        schemaManager.applyMutations(List.of(new CreateItemDefinitionMutation(aName, "d", List.of(), null, false, null)));
        schemaManager.applyMutations(List.of(new CreateItemDefinitionMutation(bName, "d", List.of(), null, false, null)));
        UUID aTypeId = schemaManager.getAdminSchema().items().stream().filter(i -> i.name().equals(aName)).findFirst().orElseThrow().id();
        UUID bTypeId = schemaManager.getAdminSchema().items().stream().filter(i -> i.name().equals(bName)).findFirst().orElseThrow().id();

        schemaManager.applyMutations(List.of(new CreateLinkDefinitionMutation(properties, List.of(
                new CreatePerspectiveDefinitionMutation(aTypeId, "toB", "d", 0, null),
                new CreatePerspectiveDefinitionMutation(bTypeId, "toA", "d", 0, null)))));

        return new FreshLinkFixture("toB", "toA", createItem(aName), createItem(bName));
    }

    @Test
    void linkCreateMutation_missingARequiredLinkProperty_reportsAValidationErrorNamingIt() {
        FreshLinkFixture link = createFreshLinkType(List.of(
                new CreatePropertyDefinitionMutation("role", "d", PropertyType.STRING, PropertyCardinality.SINGLE, PropertyUsage.REQUIRED, false)));

        assertThatThrownBy(() -> processor.process(new MutationRequest(List.of(),
                List.of(link.create(Map.of()))), SOME_PRINCIPAL))
                .isInstanceOf(MutationValidationException.class)
                .satisfies(e -> assertThat(((MutationValidationException) e).errors())
                        .anyMatch(err -> err.path().equals("links[0].properties.role") && err.message().toLowerCase().contains("required")));
    }

    @Test
    void linkCreateMutation_settingADeprecatedLinkProperty_reportsAValidationErrorNamingIt() {
        FreshLinkFixture link = createFreshLinkType(List.of(
                new CreatePropertyDefinitionMutation("legacyRole", "d", PropertyType.STRING, PropertyCardinality.SINGLE, PropertyUsage.DEPRECATED, false)));

        assertThatThrownBy(() -> processor.process(new MutationRequest(List.of(),
                List.of(link.create(Map.of("legacyRole", "X")))), SOME_PRINCIPAL))
                .isInstanceOf(MutationValidationException.class)
                .satisfies(e -> assertThat(((MutationValidationException) e).errors())
                        .anyMatch(err -> err.path().equals("links[0].properties.legacyRole") && err.message().toLowerCase().contains("deprecated")));
    }

    @Test
    void linkUpdateMutation_settingARequiredLinkPropertyToNull_reportsAValidationErrorNamingIt() {
        FreshLinkFixture link = createFreshLinkType(List.of(
                new CreatePropertyDefinitionMutation("role", "d", PropertyType.STRING, PropertyCardinality.SINGLE, PropertyUsage.REQUIRED, false)));
        UUID linkId = processor.process(new MutationRequest(List.of(),
                List.of(link.create(Map.of("role", "editor")))), SOME_PRINCIPAL)
                .links().get(0).linkId();

        Map<String, Object> clearRole = new HashMap<>();
        clearRole.put("role", null);

        assertThatThrownBy(() -> processor.process(new MutationRequest(List.of(),
                List.of(new LinkUpdateMutation(linkId, clearRole))), SOME_PRINCIPAL))
                .isInstanceOf(MutationValidationException.class)
                .satisfies(e -> assertThat(((MutationValidationException) e).errors())
                        .anyMatch(err -> err.path().equals("links[0].properties.role") && err.message().toLowerCase().contains("null")));
    }

    // Mirrors itemUpdateMutation_omittingAPropertyThatBecameRequiredAfterTheItemWasCreated... -- the
    // property is OPTIONAL when the link is created (leaving it unset is valid then), then becomes
    // REQUIRED afterward; the existing link isn't retroactively touched, but the next update to it
    // still has to leave it satisfying today's schema. A UUID-suffixed property name, not just the
    // UUID-suffixed item types the way the other link tests here get away with -- this one has to
    // find the property back by name across the whole admin schema (AdminLinkView has no name of
    // its own to filter links by first) to retroactively mark it REQUIRED, and the shared fixtures'
    // own link already has an unrelated "role"/"notes"-shaped property of its own.
    @Test
    void linkUpdateMutation_omittingALinkPropertyThatBecameRequiredAfterTheLinkWasCreated_reportsAValidationErrorSayingItMustBeSet() {
        String notesPropertyName = "linkNotes-" + UUID.randomUUID();
        FreshLinkFixture link = createFreshLinkType(List.of(
                new CreatePropertyDefinitionMutation(notesPropertyName, "d", PropertyType.STRING, PropertyCardinality.SINGLE, PropertyUsage.OPTIONAL, false)));
        UUID notesPropertyId = schemaManager.getAdminSchema().links().stream()
                .flatMap(l -> l.properties().stream())
                .filter(p -> p.name().equals(notesPropertyName))
                .findFirst().orElseThrow().id();

        // The property is optional at creation time, so leaving it unset is valid here.
        UUID linkId = processor.process(new MutationRequest(List.of(),
                List.of(link.create(Map.of()))), SOME_PRINCIPAL)
                .links().get(0).linkId();

        schemaManager.applyMutations(List.of(new UpdatePropertyDefinitionMutation(
                notesPropertyId, notesPropertyName, "d", PropertyType.STRING, PropertyCardinality.SINGLE, PropertyUsage.REQUIRED, false)));

        assertThatThrownBy(() -> processor.process(new MutationRequest(List.of(),
                List.of(new LinkUpdateMutation(linkId, Map.of()))), SOME_PRINCIPAL))
                .isInstanceOf(MutationValidationException.class)
                .satisfies(e -> assertThat(((MutationValidationException) e).errors())
                        .anyMatch(err -> err.path().equals("links[0].properties." + notesPropertyName) && err.message().toLowerCase().contains("must be set")));
    }

    @Test
    void linkUpdateMutation_settingADeprecatedLinkProperty_reportsAValidationErrorNamingIt() {
        FreshLinkFixture link = createFreshLinkType(List.of(
                new CreatePropertyDefinitionMutation("legacyRole", "d", PropertyType.STRING, PropertyCardinality.SINGLE, PropertyUsage.DEPRECATED, false)));
        UUID linkId = processor.process(new MutationRequest(List.of(),
                List.of(link.create(Map.of()))), SOME_PRINCIPAL)
                .links().get(0).linkId();

        assertThatThrownBy(() -> processor.process(new MutationRequest(List.of(),
                List.of(new LinkUpdateMutation(linkId, Map.of("legacyRole", "X")))), SOME_PRINCIPAL))
                .isInstanceOf(MutationValidationException.class)
                .satisfies(e -> assertThat(((MutationValidationException) e).errors())
                        .anyMatch(err -> err.path().equals("links[0].properties.legacyRole") && err.message().toLowerCase().contains("deprecated")));
    }

    // --- Property cardinality (PropertyCardinality.SINGLE/LIST/SET) ---
    //
    // A value's shape has to match its property's declared cardinality -- a LIST/SET property
    // expects a JSON array, a SINGLE property expects a bare scalar -- regardless of whether the
    // mutation is a create or an update, since both resolve through the same
    // resolvePropertyIds/validatePropertyValue path. Each item/link type here is a fresh,
    // UUID-suffixed one defined inline, not a shared fixture, same reasoning as the
    // required/deprecated sections above.

    private String createCardinalityTestItemType() {
        String typeName = "MutReqProcCardinality-" + UUID.randomUUID();
        schemaManager.applyMutations(List.of(new CreateItemDefinitionMutation(typeName, "d", List.of(
                new CreatePropertyDefinitionMutation("single", "d", PropertyType.STRING, PropertyCardinality.SINGLE, PropertyUsage.OPTIONAL, false),
                new CreatePropertyDefinitionMutation("list", "d", PropertyType.STRING, PropertyCardinality.LIST, PropertyUsage.OPTIONAL, false),
                new CreatePropertyDefinitionMutation("set", "d", PropertyType.STRING, PropertyCardinality.SET, PropertyUsage.OPTIONAL, false)),
                null, false, null)));
        return typeName;
    }

    private FreshLinkFixture createCardinalityTestLinkType() {
        return createFreshLinkType(List.of(
                new CreatePropertyDefinitionMutation("single", "d", PropertyType.STRING, PropertyCardinality.SINGLE, PropertyUsage.OPTIONAL, false),
                new CreatePropertyDefinitionMutation("list", "d", PropertyType.STRING, PropertyCardinality.LIST, PropertyUsage.OPTIONAL, false),
                new CreatePropertyDefinitionMutation("set", "d", PropertyType.STRING, PropertyCardinality.SET, PropertyUsage.OPTIONAL, false)));
    }

    @Test
    void itemCreateMutation_settingAListPropertyWithASingleValue_reportsAValidationError() {
        String typeName = createCardinalityTestItemType();

        assertThatThrownBy(() -> processor.process(new MutationRequest(
                List.of(new ItemCreateMutation(null, typeName, Map.of("list", "not-a-list"))), List.of()), SOME_PRINCIPAL))
                .isInstanceOf(MutationValidationException.class)
                .satisfies(e -> assertThat(((MutationValidationException) e).errors())
                        .anyMatch(err -> err.path().equals("items[0].properties.list")));
    }

    @Test
    void itemCreateMutation_settingASinglePropertyWithMultipleValues_reportsAValidationError() {
        String typeName = createCardinalityTestItemType();

        assertThatThrownBy(() -> processor.process(new MutationRequest(
                List.of(new ItemCreateMutation(null, typeName, Map.of("single", List.of("a", "b")))), List.of()), SOME_PRINCIPAL))
                .isInstanceOf(MutationValidationException.class)
                .satisfies(e -> assertThat(((MutationValidationException) e).errors())
                        .anyMatch(err -> err.path().equals("items[0].properties.single")));
    }

    @Test
    void itemCreateMutation_settingASetPropertyWithASingleValue_reportsAValidationError() {
        String typeName = createCardinalityTestItemType();

        assertThatThrownBy(() -> processor.process(new MutationRequest(
                List.of(new ItemCreateMutation(null, typeName, Map.of("set", "not-a-list"))), List.of()), SOME_PRINCIPAL))
                .isInstanceOf(MutationValidationException.class)
                .satisfies(e -> assertThat(((MutationValidationException) e).errors())
                        .anyMatch(err -> err.path().equals("items[0].properties.set")));
    }

    @Test
    void itemUpdateMutation_settingAListPropertyWithASingleValue_reportsAValidationError() {
        String typeName = createCardinalityTestItemType();
        UUID itemId = createItem(typeName);

        assertThatThrownBy(() -> processor.process(new MutationRequest(
                List.of(new ItemUpdateMutation(itemId, Map.of("list", "not-a-list"))), List.of()), SOME_PRINCIPAL))
                .isInstanceOf(MutationValidationException.class)
                .satisfies(e -> assertThat(((MutationValidationException) e).errors())
                        .anyMatch(err -> err.path().equals("items[0].properties.list")));
    }

    @Test
    void itemUpdateMutation_settingASetPropertyWithASingleValue_reportsAValidationError() {
        String typeName = createCardinalityTestItemType();
        UUID itemId = createItem(typeName);

        assertThatThrownBy(() -> processor.process(new MutationRequest(
                List.of(new ItemUpdateMutation(itemId, Map.of("set", "not-a-list"))), List.of()), SOME_PRINCIPAL))
                .isInstanceOf(MutationValidationException.class)
                .satisfies(e -> assertThat(((MutationValidationException) e).errors())
                        .anyMatch(err -> err.path().equals("items[0].properties.set")));
    }

    @Test
    void itemUpdateMutation_settingASinglePropertyWithMultipleValues_reportsAValidationError() {
        String typeName = createCardinalityTestItemType();
        UUID itemId = createItem(typeName);

        assertThatThrownBy(() -> processor.process(new MutationRequest(
                List.of(new ItemUpdateMutation(itemId, Map.of("single", List.of("a", "b")))), List.of()), SOME_PRINCIPAL))
                .isInstanceOf(MutationValidationException.class)
                .satisfies(e -> assertThat(((MutationValidationException) e).errors())
                        .anyMatch(err -> err.path().equals("items[0].properties.single")));
    }

    @Test
    void linkCreateMutation_settingAListPropertyWithASingleValue_reportsAValidationError() {
        FreshLinkFixture link = createCardinalityTestLinkType();

        assertThatThrownBy(() -> processor.process(new MutationRequest(List.of(),
                List.of(link.create(Map.of("list", "not-a-list")))), SOME_PRINCIPAL))
                .isInstanceOf(MutationValidationException.class)
                .satisfies(e -> assertThat(((MutationValidationException) e).errors())
                        .anyMatch(err -> err.path().equals("links[0].properties.list")));
    }

    @Test
    void linkCreateMutation_settingASinglePropertyWithMultipleValues_reportsAValidationError() {
        FreshLinkFixture link = createCardinalityTestLinkType();

        assertThatThrownBy(() -> processor.process(new MutationRequest(List.of(),
                List.of(link.create(Map.of("single", List.of("a", "b"))))), SOME_PRINCIPAL))
                .isInstanceOf(MutationValidationException.class)
                .satisfies(e -> assertThat(((MutationValidationException) e).errors())
                        .anyMatch(err -> err.path().equals("links[0].properties.single")));
    }

    @Test
    void linkCreateMutation_settingASetPropertyWithASingleValue_reportsAValidationError() {
        FreshLinkFixture link = createCardinalityTestLinkType();

        assertThatThrownBy(() -> processor.process(new MutationRequest(List.of(),
                List.of(link.create(Map.of("set", "not-a-list")))), SOME_PRINCIPAL))
                .isInstanceOf(MutationValidationException.class)
                .satisfies(e -> assertThat(((MutationValidationException) e).errors())
                        .anyMatch(err -> err.path().equals("links[0].properties.set")));
    }

    @Test
    void linkUpdateMutation_settingAListPropertyWithASingleValue_reportsAValidationError() {
        FreshLinkFixture link = createCardinalityTestLinkType();
        UUID linkId = processor.process(new MutationRequest(List.of(),
                List.of(link.create(Map.of()))), SOME_PRINCIPAL)
                .links().get(0).linkId();

        assertThatThrownBy(() -> processor.process(new MutationRequest(List.of(),
                List.of(new LinkUpdateMutation(linkId, Map.of("list", "not-a-list")))), SOME_PRINCIPAL))
                .isInstanceOf(MutationValidationException.class)
                .satisfies(e -> assertThat(((MutationValidationException) e).errors())
                        .anyMatch(err -> err.path().equals("links[0].properties.list")));
    }

    @Test
    void linkUpdateMutation_settingASetPropertyWithASingleValue_reportsAValidationError() {
        FreshLinkFixture link = createCardinalityTestLinkType();
        UUID linkId = processor.process(new MutationRequest(List.of(),
                List.of(link.create(Map.of()))), SOME_PRINCIPAL)
                .links().get(0).linkId();

        assertThatThrownBy(() -> processor.process(new MutationRequest(List.of(),
                List.of(new LinkUpdateMutation(linkId, Map.of("set", "not-a-list")))), SOME_PRINCIPAL))
                .isInstanceOf(MutationValidationException.class)
                .satisfies(e -> assertThat(((MutationValidationException) e).errors())
                        .anyMatch(err -> err.path().equals("links[0].properties.set")));
    }

    @Test
    void linkUpdateMutation_settingASinglePropertyWithMultipleValues_reportsAValidationError() {
        FreshLinkFixture link = createCardinalityTestLinkType();
        UUID linkId = processor.process(new MutationRequest(List.of(),
                List.of(link.create(Map.of()))), SOME_PRINCIPAL)
                .links().get(0).linkId();

        assertThatThrownBy(() -> processor.process(new MutationRequest(List.of(),
                List.of(new LinkUpdateMutation(linkId, Map.of("single", List.of("a", "b"))))), SOME_PRINCIPAL))
                .isInstanceOf(MutationValidationException.class)
                .satisfies(e -> assertThat(((MutationValidationException) e).errors())
                        .anyMatch(err -> err.path().equals("links[0].properties.single")));
    }

    // --- Controlled-list-backed property values ---
    //
    // A property pointing at a controlled list (AdminPropertyDefinitionView.controlledListId()) can
    // only ever be set to one of that list's own values. Not yet enforced anywhere -- the schema
    // records which list a property is backed by, but nothing in MutationRequestProcessor reads it
    // today, so these are written TDD-first against the intended behavior (fail red until that
    // enforcement exists), the same approach the required/deprecated sections above used. Checked
    // per scalar value, so it applies identically whether the property's cardinality is SINGLE (the
    // value itself) or LIST/SET (each element) -- covering all three the same way the cardinality
    // section above did. Each item/link type here is a fresh, UUID-suffixed one defined inline, not
    // a shared fixture, same reasoning as the sections above.

    private UUID createControlledList(String... allowedValues) {
        var list = controlledListManager.createList("MutReqProcList-" + UUID.randomUUID(), PropertyType.STRING);
        for (int i = 0; i < allowedValues.length; i++) {
            controlledListManager.addValue(list.id(), allowedValues[i], allowedValues[i], i);
        }
        return list.id();
    }

    private String createControlledListBackedItemType(PropertyCardinality cardinality) {
        String typeName = "MutReqProcControlledItem-" + UUID.randomUUID();
        schemaManager.applyMutations(List.of(new CreateItemDefinitionMutation(typeName, "d", List.of(
                new CreatePropertyDefinitionMutation("choice", "d", PropertyType.STRING, cardinality, PropertyUsage.OPTIONAL, false)),
                null, false, null)));
        UUID propId = schemaManager.getAdminSchema().items().stream()
                .filter(i -> i.name().equals(typeName)).findFirst().orElseThrow()
                .properties().stream().filter(p -> p.name().equals("choice")).findFirst().orElseThrow().id();
        schemaManager.applyMutations(List.of(new SetPropertyControlledListMutation(propId,
                new ExistingControlledListReference(createControlledList("A", "B")))));
        return typeName;
    }

    // AdminLinkView has no name of its own to filter links by first (unlike an item type), so the
    // property name has to be globally unique to find this exact property back afterward -- same
    // reasoning as linkUpdateMutation_omittingALinkPropertyThatBecameRequiredAfterTheLinkWasCreated's
    // own notesPropertyName.
    private FreshLinkFixture createControlledListBackedLinkType(PropertyCardinality cardinality, String propertyName) {
        FreshLinkFixture link = createFreshLinkType(List.of(
                new CreatePropertyDefinitionMutation(propertyName, "d", PropertyType.STRING, cardinality, PropertyUsage.OPTIONAL, false)));
        UUID propId = schemaManager.getAdminSchema().links().stream()
                .flatMap(l -> l.properties().stream())
                .filter(p -> p.name().equals(propertyName))
                .findFirst().orElseThrow().id();
        schemaManager.applyMutations(List.of(new SetPropertyControlledListMutation(propId,
                new ExistingControlledListReference(createControlledList("A", "B")))));
        return link;
    }

    @Test
    void itemCreateMutation_settingAnInvalidValueForAControlledListBackedSingleProperty_reportsAValidationError() {
        String typeName = createControlledListBackedItemType(PropertyCardinality.SINGLE);

        assertThatThrownBy(() -> processor.process(new MutationRequest(
                List.of(new ItemCreateMutation(null, typeName, Map.of("choice", "Z"))), List.of()), SOME_PRINCIPAL))
                .isInstanceOf(MutationValidationException.class)
                .satisfies(e -> assertThat(((MutationValidationException) e).errors())
                        .anyMatch(err -> err.path().equals("items[0].properties.choice") && err.message().toLowerCase().contains("controlled list")));
    }

    @Test
    void itemCreateMutation_settingAnInvalidValueForAControlledListBackedListProperty_reportsAValidationError() {
        String typeName = createControlledListBackedItemType(PropertyCardinality.LIST);

        assertThatThrownBy(() -> processor.process(new MutationRequest(
                List.of(new ItemCreateMutation(null, typeName, Map.of("choice", List.of("A", "Z")))), List.of()), SOME_PRINCIPAL))
                .isInstanceOf(MutationValidationException.class)
                .satisfies(e -> assertThat(((MutationValidationException) e).errors())
                        .anyMatch(err -> err.path().equals("items[0].properties.choice[1]") && err.message().toLowerCase().contains("controlled list")));
    }

    @Test
    void itemCreateMutation_settingAnInvalidValueForAControlledListBackedSetProperty_reportsAValidationError() {
        String typeName = createControlledListBackedItemType(PropertyCardinality.SET);

        assertThatThrownBy(() -> processor.process(new MutationRequest(
                List.of(new ItemCreateMutation(null, typeName, Map.of("choice", List.of("A", "Z")))), List.of()), SOME_PRINCIPAL))
                .isInstanceOf(MutationValidationException.class)
                .satisfies(e -> assertThat(((MutationValidationException) e).errors())
                        .anyMatch(err -> err.path().equals("items[0].properties.choice[1]") && err.message().toLowerCase().contains("controlled list")));
    }

    @Test
    void itemUpdateMutation_settingAnInvalidValueForAControlledListBackedSingleProperty_reportsAValidationError() {
        String typeName = createControlledListBackedItemType(PropertyCardinality.SINGLE);
        UUID itemId = createItem(typeName);

        assertThatThrownBy(() -> processor.process(new MutationRequest(
                List.of(new ItemUpdateMutation(itemId, Map.of("choice", "Z"))), List.of()), SOME_PRINCIPAL))
                .isInstanceOf(MutationValidationException.class)
                .satisfies(e -> assertThat(((MutationValidationException) e).errors())
                        .anyMatch(err -> err.path().equals("items[0].properties.choice") && err.message().toLowerCase().contains("controlled list")));
    }

    @Test
    void itemUpdateMutation_settingAnInvalidValueForAControlledListBackedListProperty_reportsAValidationError() {
        String typeName = createControlledListBackedItemType(PropertyCardinality.LIST);
        UUID itemId = createItem(typeName);

        assertThatThrownBy(() -> processor.process(new MutationRequest(
                List.of(new ItemUpdateMutation(itemId, Map.of("choice", List.of("A", "Z")))), List.of()), SOME_PRINCIPAL))
                .isInstanceOf(MutationValidationException.class)
                .satisfies(e -> assertThat(((MutationValidationException) e).errors())
                        .anyMatch(err -> err.path().equals("items[0].properties.choice[1]") && err.message().toLowerCase().contains("controlled list")));
    }

    @Test
    void itemUpdateMutation_settingAnInvalidValueForAControlledListBackedSetProperty_reportsAValidationError() {
        String typeName = createControlledListBackedItemType(PropertyCardinality.SET);
        UUID itemId = createItem(typeName);

        assertThatThrownBy(() -> processor.process(new MutationRequest(
                List.of(new ItemUpdateMutation(itemId, Map.of("choice", List.of("A", "Z")))), List.of()), SOME_PRINCIPAL))
                .isInstanceOf(MutationValidationException.class)
                .satisfies(e -> assertThat(((MutationValidationException) e).errors())
                        .anyMatch(err -> err.path().equals("items[0].properties.choice[1]") && err.message().toLowerCase().contains("controlled list")));
    }

    @Test
    void linkCreateMutation_settingAnInvalidValueForAControlledListBackedSingleProperty_reportsAValidationError() {
        String propertyName = "choice-" + UUID.randomUUID();
        FreshLinkFixture link = createControlledListBackedLinkType(PropertyCardinality.SINGLE, propertyName);

        assertThatThrownBy(() -> processor.process(new MutationRequest(List.of(),
                List.of(link.create(Map.of(propertyName, "Z")))), SOME_PRINCIPAL))
                .isInstanceOf(MutationValidationException.class)
                .satisfies(e -> assertThat(((MutationValidationException) e).errors())
                        .anyMatch(err -> err.path().equals("links[0].properties." + propertyName) && err.message().toLowerCase().contains("controlled list")));
    }

    @Test
    void linkCreateMutation_settingAnInvalidValueForAControlledListBackedListProperty_reportsAValidationError() {
        String propertyName = "choice-" + UUID.randomUUID();
        FreshLinkFixture link = createControlledListBackedLinkType(PropertyCardinality.LIST, propertyName);

        assertThatThrownBy(() -> processor.process(new MutationRequest(List.of(),
                List.of(link.create(Map.of(propertyName, List.of("A", "Z"))))), SOME_PRINCIPAL))
                .isInstanceOf(MutationValidationException.class)
                .satisfies(e -> assertThat(((MutationValidationException) e).errors())
                        .anyMatch(err -> err.path().equals("links[0].properties." + propertyName + "[1]") && err.message().toLowerCase().contains("controlled list")));
    }

    @Test
    void linkCreateMutation_settingAnInvalidValueForAControlledListBackedSetProperty_reportsAValidationError() {
        String propertyName = "choice-" + UUID.randomUUID();
        FreshLinkFixture link = createControlledListBackedLinkType(PropertyCardinality.SET, propertyName);

        assertThatThrownBy(() -> processor.process(new MutationRequest(List.of(),
                List.of(link.create(Map.of(propertyName, List.of("A", "Z"))))), SOME_PRINCIPAL))
                .isInstanceOf(MutationValidationException.class)
                .satisfies(e -> assertThat(((MutationValidationException) e).errors())
                        .anyMatch(err -> err.path().equals("links[0].properties." + propertyName + "[1]") && err.message().toLowerCase().contains("controlled list")));
    }

    @Test
    void linkUpdateMutation_settingAnInvalidValueForAControlledListBackedSingleProperty_reportsAValidationError() {
        String propertyName = "choice-" + UUID.randomUUID();
        FreshLinkFixture link = createControlledListBackedLinkType(PropertyCardinality.SINGLE, propertyName);
        UUID linkId = processor.process(new MutationRequest(List.of(),
                List.of(link.create(Map.of()))), SOME_PRINCIPAL)
                .links().get(0).linkId();

        assertThatThrownBy(() -> processor.process(new MutationRequest(List.of(),
                List.of(new LinkUpdateMutation(linkId, Map.of(propertyName, "Z")))), SOME_PRINCIPAL))
                .isInstanceOf(MutationValidationException.class)
                .satisfies(e -> assertThat(((MutationValidationException) e).errors())
                        .anyMatch(err -> err.path().equals("links[0].properties." + propertyName) && err.message().toLowerCase().contains("controlled list")));
    }

    @Test
    void linkUpdateMutation_settingAnInvalidValueForAControlledListBackedListProperty_reportsAValidationError() {
        String propertyName = "choice-" + UUID.randomUUID();
        FreshLinkFixture link = createControlledListBackedLinkType(PropertyCardinality.LIST, propertyName);
        UUID linkId = processor.process(new MutationRequest(List.of(),
                List.of(link.create(Map.of()))), SOME_PRINCIPAL)
                .links().get(0).linkId();

        assertThatThrownBy(() -> processor.process(new MutationRequest(List.of(),
                List.of(new LinkUpdateMutation(linkId, Map.of(propertyName, List.of("A", "Z"))))), SOME_PRINCIPAL))
                .isInstanceOf(MutationValidationException.class)
                .satisfies(e -> assertThat(((MutationValidationException) e).errors())
                        .anyMatch(err -> err.path().equals("links[0].properties." + propertyName + "[1]") && err.message().toLowerCase().contains("controlled list")));
    }

    @Test
    void linkUpdateMutation_settingAnInvalidValueForAControlledListBackedSetProperty_reportsAValidationError() {
        String propertyName = "choice-" + UUID.randomUUID();
        FreshLinkFixture link = createControlledListBackedLinkType(PropertyCardinality.SET, propertyName);
        UUID linkId = processor.process(new MutationRequest(List.of(),
                List.of(link.create(Map.of()))), SOME_PRINCIPAL)
                .links().get(0).linkId();

        assertThatThrownBy(() -> processor.process(new MutationRequest(List.of(),
                List.of(new LinkUpdateMutation(linkId, Map.of(propertyName, List.of("A", "Z"))))), SOME_PRINCIPAL))
                .isInstanceOf(MutationValidationException.class)
                .satisfies(e -> assertThat(((MutationValidationException) e).errors())
                        .anyMatch(err -> err.path().equals("links[0].properties." + propertyName + "[1]") && err.message().toLowerCase().contains("controlled list")));
    }

    // --- Link perspective cardinality (minCardinality/maxCardinality) ---
    //
    // Not yet enforced anywhere -- CreatePerspectiveDefinitionMutation records min/maxCardinality
    // and NoOpMutationFilter reads maxCardinality for its own, unrelated to-one dedup special case,
    // but nothing in MutationRequestProcessor checks a link's actual count against either bound, so
    // these are written TDD-first against the intended behavior (fail red until that enforcement
    // exists). Each schema here is a fresh pair of UUID-suffixed item types joined by one fresh
    // link, with the cardinality bound under test on side A's own perspective only -- side B's is
    // left unconstrained (0, null) so a test only ever exercises the one bound it names.

    private record CardinalityLinkSchema(String perspectiveA, String perspectiveB, String aTypeName, String bTypeName) {}

    // minA is NOT NULL at the schema level (minimum_cardinality has a CHECK >= 0, no null case) --
    // pass 0, not null, for "no minimum." maxA may genuinely be null for "no maximum."
    private CardinalityLinkSchema createCardinalityLinkSchema(Integer minA, Integer maxA) {
        String aName = "MutReqProcCardLinkA-" + UUID.randomUUID();
        String bName = "MutReqProcCardLinkB-" + UUID.randomUUID();
        schemaManager.applyMutations(List.of(new CreateItemDefinitionMutation(aName, "d", List.of(), null, false, null)));
        schemaManager.applyMutations(List.of(new CreateItemDefinitionMutation(bName, "d", List.of(), null, false, null)));
        UUID aTypeId = schemaManager.getAdminSchema().items().stream().filter(i -> i.name().equals(aName)).findFirst().orElseThrow().id();
        UUID bTypeId = schemaManager.getAdminSchema().items().stream().filter(i -> i.name().equals(bName)).findFirst().orElseThrow().id();
        schemaManager.applyMutations(List.of(new CreateLinkDefinitionMutation(List.of(), List.of(
                new CreatePerspectiveDefinitionMutation(aTypeId, "toB", "d", minA, maxA),
                new CreatePerspectiveDefinitionMutation(bTypeId, "toA", "d", 0, null)))));
        return new CardinalityLinkSchema("toB", "toA", aName, bName);
    }

    @Test
    void mutationRequest_creatingAnItemWithFewerLinksThanItsPerspectivesMinimumCardinality_reportsAValidationError() {
        var schema = createCardinalityLinkSchema(1, null);

        assertThatThrownBy(() -> processor.process(new MutationRequest(
                List.of(new ItemCreateMutation(null, schema.aTypeName(), Map.of())), List.of()), SOME_PRINCIPAL))
                .isInstanceOf(MutationValidationException.class)
                .satisfies(e -> assertThat(((MutationValidationException) e).errors())
                        .anyMatch(err -> err.path().equals("items[0].links.toB") && err.message().toLowerCase().contains("minimum")));
    }

    @Test
    void mutationRequest_creatingAnItemWithMoreLinksThanItsPerspectivesMaximumCardinality_reportsAValidationError() {
        var schema = createCardinalityLinkSchema(0, 1);
        UUID b1 = createItem(schema.bTypeName());
        UUID b2 = createItem(schema.bTypeName());

        assertThatThrownBy(() -> processor.process(new MutationRequest(
                List.of(new ItemCreateMutation("newA", schema.aTypeName(), Map.of())),
                List.of(
                        new LinkCreateMutation(
                                new LinkEndpointReference(schema.perspectiveA(), new NewItemReference("newA")),
                                new LinkEndpointReference(schema.perspectiveB(), new ExistingItemReference(b1)),
                                Map.of()),
                        new LinkCreateMutation(
                                new LinkEndpointReference(schema.perspectiveA(), new NewItemReference("newA")),
                                new LinkEndpointReference(schema.perspectiveB(), new ExistingItemReference(b2)),
                                Map.of()))), SOME_PRINCIPAL))
                .isInstanceOf(MutationValidationException.class)
                .satisfies(e -> assertThat(((MutationValidationException) e).errors())
                        .anyMatch(err -> err.path().equals("items[0].links.toB") && err.message().toLowerCase().contains("maximum")));
    }

    @Test
    void linkCreateMutation_addingALinkThatWouldExceedThePerspectivesMaximumCardinality_reportsAValidationError() {
        var schema = createCardinalityLinkSchema(0, 1);
        UUID aItemId = createItem(schema.aTypeName());
        UUID b1 = createItem(schema.bTypeName());
        UUID b2 = createItem(schema.bTypeName());
        processor.process(new MutationRequest(List.of(), List.of(
                new LinkCreateMutation(
                        new LinkEndpointReference(schema.perspectiveA(), new ExistingItemReference(aItemId)),
                        new LinkEndpointReference(schema.perspectiveB(), new ExistingItemReference(b1)),
                        Map.of()))), SOME_PRINCIPAL);

        assertThatThrownBy(() -> processor.process(new MutationRequest(List.of(), List.of(
                new LinkCreateMutation(
                        new LinkEndpointReference(schema.perspectiveA(), new ExistingItemReference(aItemId)),
                        new LinkEndpointReference(schema.perspectiveB(), new ExistingItemReference(b2)),
                        Map.of()))), SOME_PRINCIPAL))
                .isInstanceOf(MutationValidationException.class)
                .satisfies(e -> assertThat(((MutationValidationException) e).errors())
                        .anyMatch(err -> err.path().equals("links[0]") && err.message().toLowerCase().contains("maximum")));
    }

    @Test
    void linkDeleteMutation_removingALinkThatWouldDropBelowThePerspectivesMinimumCardinality_reportsAValidationError() {
        var schema = createCardinalityLinkSchema(1, null);
        UUID bItemId = createItem(schema.bTypeName());
        // The new A item's own minCardinality=1 has to be satisfied at creation time -- same
        // atomic-request shape as the create-above-maximum test above -- so there's a real link to
        // remove afterward without the setup itself tripping the very check under test here.
        MutationResponse createResponse = processor.process(new MutationRequest(
                List.of(new ItemCreateMutation("newA", schema.aTypeName(), Map.of())),
                List.of(new LinkCreateMutation(
                        new LinkEndpointReference(schema.perspectiveA(), new NewItemReference("newA")),
                        new LinkEndpointReference(schema.perspectiveB(), new ExistingItemReference(bItemId)),
                        Map.of()))), SOME_PRINCIPAL);
        UUID linkId = createResponse.links().get(0).linkId();

        assertThatThrownBy(() -> processor.process(new MutationRequest(List.of(),
                List.of(new LinkDeleteMutation(linkId))), SOME_PRINCIPAL))
                .isInstanceOf(MutationValidationException.class)
                .satisfies(e -> assertThat(((MutationValidationException) e).errors())
                        .anyMatch(err -> err.path().equals("links[0]") && err.message().toLowerCase().contains("minimum")));
    }

    // --- validateBinaryValue/validateScalar: BINARY, LONG, DOUBLE, DATETIME ---

    @Test
    void binaryProperty_acceptsAnIdFromAnEarlierUpload() {
        UUID binaryId = storeBinary("some file content".getBytes());

        MutationResponse response = processor.process(new MutationRequest(
                List.of(new ItemCreateMutation(null, "MutReqProcA", Map.of("attachment", binaryId.toString()))), List.of()),
                SOME_PRINCIPAL);

        UUID itemId = response.items().get(0).itemId();
        var item = registerPartitionManager.projectOne(fixture.aTypeId(), itemId, "http://binary").orElseThrow();
        @SuppressWarnings("unchecked")
        var attachment = (Map<String, Object>) item.properties().get("attachment");
        assertThat(attachment).containsEntry("id", binaryId.toString())
                .containsEntry("url", "http://binary/api/binary/" + binaryId);
    }

    @Test
    void binaryProperty_rejectsAnIdThatWasNeverUploaded() {
        assertThatThrownBy(() -> processor.process(new MutationRequest(
                List.of(new ItemCreateMutation(null, "MutReqProcA", Map.of("attachment", UUID.randomUUID().toString()))), List.of()),
                SOME_PRINCIPAL))
                .isInstanceOf(MutationValidationException.class)
                .satisfies(e -> assertThat(((MutationValidationException) e).errors())
                        .anyMatch(err -> err.message().contains("does not exist")));
    }

    @Test
    void binaryProperty_rejectsAValueThatIsNotAValidId() {
        assertThatThrownBy(() -> processor.process(new MutationRequest(
                List.of(new ItemCreateMutation(null, "MutReqProcA", Map.of("attachment", "not-a-uuid"))), List.of()),
                SOME_PRINCIPAL))
                .isInstanceOf(MutationValidationException.class)
                .satisfies(e -> assertThat(((MutationValidationException) e).errors())
                        .anyMatch(err -> err.message().contains("is not a valid binary id")));
    }

    // A dedicated item type, not MutReqProcA -- this test deletes the property itself, which would
    // break every other test in this class still relying on MutReqProcA.attachment existing.
    @Test
    void deletePropertyDefinitionMutation_onABinaryPropertyWithCommittedData_succeedsAndClearsItsRegisterBinaryPropertyRows() {
        // Regression test: register_binary_property's FK to schema_property.id was missing ON
        // DELETE CASCADE (unlike schema_item_property/schema_trait_property/schema_link_property/
        // schema_property_property, which all cascade) -- see
        // V1_0_2_7__register_binary_property_cascade_delete.sql. Deleting a BINARY property that
        // any item had ever set used to 500 on a raw Postgres FK violation instead of succeeding
        // the way deleting a scalar property (no such FK) already does.
        String itemTypeName = "DeletePropTest-" + UUID.randomUUID();
        schemaManager.applyMutations(List.of(new CreateItemDefinitionMutation(itemTypeName, "d", List.of(
                new CreatePropertyDefinitionMutation("attachment", "d", PropertyType.BINARY, PropertyCardinality.SINGLE, PropertyUsage.OPTIONAL, false)),
                null, false, null)));
        UUID propertyId = schemaManager.getAdminSchema().items().stream()
                .filter(i -> i.name().equals(itemTypeName)).findFirst().orElseThrow()
                .properties().stream().filter(p -> p.name().equals("attachment")).findFirst().orElseThrow().id();

        UUID binaryId = storeBinary("delete-property regression content".getBytes());
        processor.process(new MutationRequest(
                List.of(new ItemCreateMutation(null, itemTypeName, Map.of("attachment", binaryId.toString()))), List.of()),
                SOME_PRINCIPAL);

        long before = jdbcClient.sql("SELECT COUNT(*) FROM register_binary_property WHERE property_id = :id")
                .param("id", propertyId).query(Long.class).single();
        assertThat(before).isEqualTo(1L);

        schemaManager.applyMutations(List.of(new DeletePropertyDefinitionMutation(propertyId)));

        long after = jdbcClient.sql("SELECT COUNT(*) FROM register_binary_property WHERE property_id = :id")
                .param("id", propertyId).query(Long.class).single();
        assertThat(after).isEqualTo(0L);
        assertThat(schemaManager.getAdminSchema().items().stream()
                .filter(i -> i.name().equals(itemTypeName)).findFirst().orElseThrow().properties())
                .extracting(p -> p.name()).doesNotContain("attachment");
    }

    // Regression test for the reported symptom exactly: a BINARY property nested inside a property
    // group (e.g. file.content) came back as a top-level sibling of its container on
    // projection ("content" alongside "file", not nested inside it) instead of at its actual
    // schema-nested location -- assembleProjectedItem's binary-value merge flattened to the leaf's
    // own bare name instead of walking its schema path the way every other property type's merge
    // (namesForIds) already does.
    @Test
    void nestedBinaryProperty_projectsAtItsSchemaNestedLocation_notFlattenedToTopLevel() {
        String itemTypeName = "NestedBinaryTest-" + UUID.randomUUID();
        schemaManager.applyMutations(List.of(new CreateItemDefinitionMutation(itemTypeName, "d", List.of(
                new CreatePropertyDefinitionMutation("placeholder", "d", PropertyType.STRING, PropertyCardinality.SINGLE, PropertyUsage.OPTIONAL, false)),
                null, false, null, List.of(), List.of(
                new CreatePropertyGroupDefinitionMutation("file", "d", List.of(
                        new CreatePropertyDefinitionMutation("name", "d", PropertyType.STRING, PropertyCardinality.SINGLE, PropertyUsage.OPTIONAL, false),
                        new CreatePropertyDefinitionMutation("content", "d", PropertyType.BINARY, PropertyCardinality.SINGLE, PropertyUsage.OPTIONAL, false)), List.of())))));
        UUID itemTypeId = schemaManager.getAdminSchema().items().stream()
                .filter(i -> i.name().equals(itemTypeName)).findFirst().orElseThrow().id();

        UUID binaryId = storeBinary("nested binary regression content".getBytes());
        MutationResponse response = processor.process(new MutationRequest(
                List.of(new ItemCreateMutation(null, itemTypeName,
                        Map.of("file", Map.of("name", "photo.jpg", "content", binaryId.toString())))), List.of()),
                SOME_PRINCIPAL);
        UUID itemId = response.items().get(0).itemId();

        var item = registerPartitionManager.projectOne(itemTypeId, itemId, "http://binary").orElseThrow();
        assertThat(item.properties()).doesNotContainKey("content");
        @SuppressWarnings("unchecked")
        var file = (Map<String, Object>) item.properties().get("file");
        assertThat(file).containsEntry("name", "photo.jpg");
        @SuppressWarnings("unchecked")
        var content = (Map<String, Object>) file.get("content");
        assertThat(content).containsEntry("id", binaryId.toString())
                .containsEntry("url", "http://binary/api/binary/" + binaryId);
    }

    @Test
    void longProperty_acceptsAnIntegerValue() {
        MutationResponse response = processor.process(new MutationRequest(
                List.of(new ItemCreateMutation(null, "MutReqProcA", Map.of("count", 42))), List.of()), SOME_PRINCIPAL);

        UUID itemId = response.items().get(0).itemId();
        var item = registerPartitionManager.projectOne(fixture.aTypeId(), itemId, "http://binary").orElseThrow();
        assertThat(item.properties()).containsEntry("count", 42);
    }

    @Test
    void longProperty_rejectsANonNumericValue() {
        assertThatThrownBy(() -> processor.process(new MutationRequest(
                List.of(new ItemCreateMutation(null, "MutReqProcA", Map.of("count", "not-a-number"))), List.of()), SOME_PRINCIPAL))
                .isInstanceOf(MutationValidationException.class)
                .satisfies(e -> assertThat(((MutationValidationException) e).errors())
                        .anyMatch(err -> err.path().equals("items[0].properties.count")));
    }

    @Test
    void longProperty_acceptsAGenuineLongValue_notJustIntegerAutoboxing() {
        MutationResponse response = processor.process(new MutationRequest(
                List.of(new ItemCreateMutation(null, "MutReqProcA", Map.of("count", 9_000_000_000L))), List.of()), SOME_PRINCIPAL);

        assertThat(response.items()).hasSize(1);
    }

    @Test
    void doubleProperty_acceptsADecimalValue() {
        MutationResponse response = processor.process(new MutationRequest(
                List.of(new ItemCreateMutation(null, "MutReqProcA", Map.of("price", 19.99))), List.of()), SOME_PRINCIPAL);

        UUID itemId = response.items().get(0).itemId();
        var item = registerPartitionManager.projectOne(fixture.aTypeId(), itemId, "http://binary").orElseThrow();
        assertThat(item.properties()).containsEntry("price", 19.99);
    }

    @Test
    void doubleProperty_acceptsAWholeNumberValue() {
        MutationResponse response = processor.process(new MutationRequest(
                List.of(new ItemCreateMutation(null, "MutReqProcA", Map.of("price", 20))), List.of()), SOME_PRINCIPAL);

        assertThat(response.items()).hasSize(1);
    }

    @Test
    void doubleProperty_rejectsANonNumericValue() {
        assertThatThrownBy(() -> processor.process(new MutationRequest(
                List.of(new ItemCreateMutation(null, "MutReqProcA", Map.of("price", "not-a-number"))), List.of()), SOME_PRINCIPAL))
                .isInstanceOf(MutationValidationException.class)
                .satisfies(e -> assertThat(((MutationValidationException) e).errors())
                        .anyMatch(err -> err.path().equals("items[0].properties.price")));
    }

    @Test
    void objectProperty_acceptsAMapValue() {
        MutationResponse response = processor.process(new MutationRequest(
                List.of(new ItemCreateMutation(null, "MutReqProcA", Map.of("extra", Map.of("nested", "value")))), List.of()),
                SOME_PRINCIPAL);

        UUID itemId = response.items().get(0).itemId();
        var item = registerPartitionManager.projectOne(fixture.aTypeId(), itemId, "http://binary").orElseThrow();
        assertThat(item.properties()).containsKey("extra");
    }

    @Test
    void objectProperty_rejectsANonMapValue() {
        assertThatThrownBy(() -> processor.process(new MutationRequest(
                List.of(new ItemCreateMutation(null, "MutReqProcA", Map.of("extra", "not-a-map"))), List.of()), SOME_PRINCIPAL))
                .isInstanceOf(MutationValidationException.class)
                .satisfies(e -> assertThat(((MutationValidationException) e).errors())
                        .anyMatch(err -> err.path().equals("items[0].properties.extra")));
    }

    @Test
    void objectProperty_nestedValueRoundTripsThroughCreateAndRead() {
        MutationResponse response = processor.process(new MutationRequest(
                List.of(new ItemCreateMutation(null, "MutReqProcA",
                        Map.of("extra", Map.of("nested", "hello", "second", "world")))), List.of()),
                SOME_PRINCIPAL);

        UUID itemId = response.items().get(0).itemId();
        var item = registerPartitionManager.projectOne(fixture.aTypeId(), itemId, "http://binary").orElseThrow();
        assertThat(item.properties().get("extra")).isEqualTo(Map.of("nested", "hello", "second", "world"));
    }

    @Test
    void objectProperty_unknownNestedPropertyName_throws() {
        assertThatThrownBy(() -> processor.process(new MutationRequest(
                List.of(new ItemCreateMutation(null, "MutReqProcA", Map.of("extra", Map.of("bogus", "x")))), List.of()),
                SOME_PRINCIPAL))
                .isInstanceOf(MutationValidationException.class)
                .satisfies(e -> assertThat(((MutationValidationException) e).errors())
                        .anyMatch(err -> err.path().equals("items[0].properties.extra.bogus")));
    }

    // "extra" and "extra2" both have a leaf named "nested" -- they must resolve to distinct
    // property ids and never collide in storage, since the JSON nesting itself scopes the lookup,
    // not a global name (see MutationRequestProcessor.resolveObjectPropertyValue's own comment).
    @Test
    void objectProperty_sameLeafNameUnderDifferentContainers_resolvesIndependently() {
        MutationResponse response = processor.process(new MutationRequest(
                List.of(new ItemCreateMutation(null, "MutReqProcA", Map.of(
                        "extra", Map.of("nested", "extra-value"),
                        "extra2", Map.of("nested", "extra2-value")))), List.of()),
                SOME_PRINCIPAL);

        UUID itemId = response.items().get(0).itemId();
        var item = registerPartitionManager.projectOne(fixture.aTypeId(), itemId, "http://binary").orElseThrow();
        assertThat(item.properties().get("extra")).isEqualTo(Map.of("nested", "extra-value"));
        assertThat(item.properties().get("extra2")).isEqualTo(Map.of("nested", "extra2-value"));
    }

    @Test
    void objectProperty_update_partialNestedDiffLeavesSiblingsUntouched() {
        MutationResponse createResponse = processor.process(new MutationRequest(
                List.of(new ItemCreateMutation(null, "MutReqProcA",
                        Map.of("extra", Map.of("nested", "original", "second", "keep-me")))), List.of()),
                SOME_PRINCIPAL);
        UUID itemId = createResponse.items().get(0).itemId();

        processor.process(new MutationRequest(
                List.of(new ItemUpdateMutation(itemId, Map.of("extra", Map.of("nested", "changed")))), List.of()),
                SOME_PRINCIPAL);

        var item = registerPartitionManager.projectOne(fixture.aTypeId(), itemId, "http://binary").orElseThrow();
        assertThat(item.properties().get("extra")).isEqualTo(Map.of("nested", "changed", "second", "keep-me"));
    }

    // A group is structure with no value of its own, so there is nothing for null to clear -- it is
    // a validation error, and no bulk-clear primitive exists. Clearing means naming each leaf.
    @Test
    void propertyUserGroup_updateWithNull_isAValidationError() {
        MutationResponse createResponse = processor.process(new MutationRequest(
                List.of(new ItemCreateMutation(null, "MutReqProcA",
                        Map.of("extra", Map.of("nested", "a", "second", "b")))), List.of()),
                SOME_PRINCIPAL);
        UUID itemId = createResponse.items().get(0).itemId();

        Map<String, Object> clearDiff = new HashMap<>();
        clearDiff.put("extra", null);
        assertThatThrownBy(() -> processor.process(new MutationRequest(
                List.of(new ItemUpdateMutation(itemId, clearDiff)), List.of()), SOME_PRINCIPAL))
                .isInstanceOf(MutationValidationException.class)
                .hasMessageContaining("Mutation request failed validation");
    }

    @Test
    void propertyUserGroup_updateWithEachLeafSetToNull_clearsThem() {
        MutationResponse createResponse = processor.process(new MutationRequest(
                List.of(new ItemCreateMutation(null, "MutReqProcA",
                        Map.of("extra", Map.of("nested", "a", "second", "b")))), List.of()),
                SOME_PRINCIPAL);
        UUID itemId = createResponse.items().get(0).itemId();

        Map<String, Object> leaves = new HashMap<>();
        leaves.put("nested", null);
        leaves.put("second", null);
        processor.process(new MutationRequest(
                List.of(new ItemUpdateMutation(itemId, Map.of("extra", leaves))), List.of()),
                SOME_PRINCIPAL);

        var item = registerPartitionManager.projectOne(fixture.aTypeId(), itemId, "http://binary").orElseThrow();
        assertThat(item.properties()).doesNotContainKey("extra");
    }

    // --- Link OBJECT properties (mirrors the item-side "extra"/"extra2" coverage above, proving
    // the same flat-storage/nested-reconstruction model applies identically to links) ---

    private ProjectedLink createCDLinkAndFindIt(Map<String, Object> properties) {
        UUID cId = createItem("MutReqProcC");
        UUID dId = createItem("MutReqProcD");
        MutationResponse response = processor.process(new MutationRequest(List.of(), List.of(
                new LinkCreateMutation(
                        new LinkEndpointReference("onlyC", new ExistingItemReference(cId)),
                        new LinkEndpointReference("onlyD", new ExistingItemReference(dId)),
                        properties))), SOME_PRINCIPAL);
        UUID linkId = response.links().get(0).linkId();

        var item = registerPartitionManager.projectOne(fixture.cTypeId(), cId, "http://binary").orElseThrow();
        return item.links().values().stream().flatMap(List::stream)
                .filter(link -> link.linkId().equals(linkId))
                .findFirst().orElseThrow();
    }

    @Test
    void linkObjectProperty_acceptsAMapValue() {
        var link = createCDLinkAndFindIt(Map.of("linkExtra", Map.of("nested", "value")));

        assertThat(link.properties()).containsKey("linkExtra");
    }

    @Test
    void linkObjectProperty_rejectsANonMapValue() {
        UUID cId = createItem("MutReqProcC");
        UUID dId = createItem("MutReqProcD");

        assertThatThrownBy(() -> processor.process(new MutationRequest(List.of(), List.of(
                new LinkCreateMutation(
                        new LinkEndpointReference("onlyC", new ExistingItemReference(cId)),
                        new LinkEndpointReference("onlyD", new ExistingItemReference(dId)),
                        Map.of("linkExtra", "not-a-map")))), SOME_PRINCIPAL))
                .isInstanceOf(MutationValidationException.class)
                .satisfies(e -> assertThat(((MutationValidationException) e).errors())
                        .anyMatch(err -> err.path().equals("links[0].properties.linkExtra")));
    }

    @Test
    void linkObjectProperty_nestedValueRoundTripsThroughCreateAndRead() {
        var link = createCDLinkAndFindIt(Map.of("linkExtra", Map.of("nested", "hello", "second", "world")));

        assertThat(link.properties().get("linkExtra")).isEqualTo(Map.of("nested", "hello", "second", "world"));
    }

    @Test
    void linkObjectProperty_unknownNestedPropertyName_throws() {
        UUID cId = createItem("MutReqProcC");
        UUID dId = createItem("MutReqProcD");

        assertThatThrownBy(() -> processor.process(new MutationRequest(List.of(), List.of(
                new LinkCreateMutation(
                        new LinkEndpointReference("onlyC", new ExistingItemReference(cId)),
                        new LinkEndpointReference("onlyD", new ExistingItemReference(dId)),
                        Map.of("linkExtra", Map.of("bogus", "x"))))), SOME_PRINCIPAL))
                .isInstanceOf(MutationValidationException.class)
                .satisfies(e -> assertThat(((MutationValidationException) e).errors())
                        .anyMatch(err -> err.path().equals("links[0].properties.linkExtra.bogus")));
    }

    // MutReqProcA's own "extra" object property and link3's "linkExtra" both have a leaf named
    // "nested" -- proves resolveObjectPropertyValue's container-scoped resolution holds across the
    // item/link boundary specifically, not just between two containers on the same item (see the
    // item-only version of this test above).
    @Test
    void linkObjectProperty_sameLeafNameAsAnItemsObjectProperty_resolvesIndependently() {
        MutationResponse itemResponse = processor.process(new MutationRequest(
                List.of(new ItemCreateMutation(null, "MutReqProcA", Map.of("extra", Map.of("nested", "item-value")))), List.of()),
                SOME_PRINCIPAL);
        UUID itemId = itemResponse.items().get(0).itemId();

        var link = createCDLinkAndFindIt(Map.of("linkExtra", Map.of("nested", "link-value")));

        var item = registerPartitionManager.projectOne(fixture.aTypeId(), itemId, "http://binary").orElseThrow();
        assertThat(item.properties().get("extra")).isEqualTo(Map.of("nested", "item-value"));
        assertThat(link.properties().get("linkExtra")).isEqualTo(Map.of("nested", "link-value"));
    }

    @Test
    void linkUpdateMutation_withObjectProperty_partialNestedDiffLeavesSiblingsUntouched() {
        UUID cId = createItem("MutReqProcC");
        UUID dId = createItem("MutReqProcD");
        MutationResponse createResponse = processor.process(new MutationRequest(List.of(), List.of(
                new LinkCreateMutation(
                        new LinkEndpointReference("onlyC", new ExistingItemReference(cId)),
                        new LinkEndpointReference("onlyD", new ExistingItemReference(dId)),
                        Map.of("linkExtra", Map.of("nested", "original", "second", "keep-me"))))), SOME_PRINCIPAL);
        UUID linkId = createResponse.links().get(0).linkId();

        processor.process(new MutationRequest(List.of(),
                List.of(new LinkUpdateMutation(linkId, Map.of("linkExtra", Map.of("nested", "changed"))))), SOME_PRINCIPAL);

        var item = registerPartitionManager.projectOne(fixture.cTypeId(), cId, "http://binary").orElseThrow();
        var link = item.links().values().stream().flatMap(List::stream)
                .filter(l -> l.linkId().equals(linkId)).findFirst().orElseThrow();
        assertThat(link.properties().get("linkExtra")).isEqualTo(Map.of("nested", "changed", "second", "keep-me"));
    }

    @Test
    void linkUpdateMutation_withPropertyGroupSetToNull_isAValidationError() {
        UUID cId = createItem("MutReqProcC");
        UUID dId = createItem("MutReqProcD");
        MutationResponse createResponse = processor.process(new MutationRequest(List.of(), List.of(
                new LinkCreateMutation(
                        new LinkEndpointReference("onlyC", new ExistingItemReference(cId)),
                        new LinkEndpointReference("onlyD", new ExistingItemReference(dId)),
                        Map.of("linkExtra", Map.of("nested", "a", "second", "b"))))), SOME_PRINCIPAL);
        UUID linkId = createResponse.links().get(0).linkId();

        Map<String, Object> clearDiff = new HashMap<>();
        clearDiff.put("linkExtra", null);
        assertThatThrownBy(() -> processor.process(new MutationRequest(List.of(), List.of(new LinkUpdateMutation(linkId, clearDiff))), SOME_PRINCIPAL))
                .isInstanceOf(MutationValidationException.class);
    }

    @Test
    void datetimeProperty_acceptsAValidOffsetDateTimeString() {
        MutationResponse response = processor.process(new MutationRequest(
                List.of(new ItemCreateMutation(null, "MutReqProcA", Map.of("createdAt", "2026-01-15T10:30:00Z"))), List.of()),
                SOME_PRINCIPAL);

        assertThat(response.items()).hasSize(1);
    }

    @Test
    void datetimeProperty_rejectsAnInvalidString() {
        assertThatThrownBy(() -> processor.process(new MutationRequest(
                List.of(new ItemCreateMutation(null, "MutReqProcA", Map.of("createdAt", "not-a-datetime"))), List.of()),
                SOME_PRINCIPAL))
                .isInstanceOf(MutationValidationException.class)
                .satisfies(e -> assertThat(((MutationValidationException) e).errors())
                        .anyMatch(err -> err.path().equals("items[0].properties.createdAt")));
    }

    // --- Item type inheritance ---

    @Test
    void itemCreateMutation_forAnAbstractItemType_reportsAValidationError() {
        String typeName = "MutReqProcAbstract-" + UUID.randomUUID();
        schemaManager.applyMutations(List.of(new CreateItemDefinitionMutation(typeName, "d", List.of(), null, true, null)));

        assertThatThrownBy(() -> processor.process(new MutationRequest(
                List.of(new ItemCreateMutation(null, typeName, Map.of())), List.of()), SOME_PRINCIPAL))
                .isInstanceOf(MutationValidationException.class)
                .satisfies(e -> assertThat(((MutationValidationException) e).errors())
                        .anyMatch(err -> err.message().contains("abstract")));
    }

    @Test
    void itemCreateMutation_forASubtype_canSetAnInheritedPropertyFromItsSupertype() {
        String supertypeName = "MutReqProcSuper-" + UUID.randomUUID();
        schemaManager.applyMutations(List.of(new CreateItemDefinitionMutation(supertypeName, "d", List.of(
                new CreatePropertyDefinitionMutation("inheritedProp", "d", PropertyType.STRING, PropertyCardinality.SINGLE, PropertyUsage.OPTIONAL, false)), null, false, null)));
        UUID supertypeId = schemaManager.getAdminSchema().items().stream()
                .filter(i -> i.name().equals(supertypeName)).findFirst().orElseThrow().id();

        String subtypeName = "MutReqProcSub-" + UUID.randomUUID();
        schemaManager.applyMutations(List.of(new CreateItemDefinitionMutation(subtypeName, "d", List.of(), supertypeId, false, null)));
        UUID subtypeId = schemaManager.getAdminSchema().items().stream()
                .filter(i -> i.name().equals(subtypeName)).findFirst().orElseThrow().id();

        MutationResponse response = processor.process(new MutationRequest(
                List.of(new ItemCreateMutation(null, subtypeName, Map.of("inheritedProp", "value-from-supertype"))), List.of()),
                SOME_PRINCIPAL);
        UUID itemId = response.items().get(0).itemId();

        // Write-path validation resolved "inheritedProp" against the subtype's merged property set
        // (own + supertype chain, via SchemaViewBuilder) with zero changes needed to
        // resolveItemPropertyIds itself -- this proves that "falls out for free" claim, not just
        // assumes it.
        var item = registerPartitionManager.projectOne(subtypeId, itemId, "http://binary").orElseThrow();
        assertThat(item.properties()).containsEntry("inheritedProp", "value-from-supertype");

        // Leaf-only storage: the item's actual stored type is the subtype it was created as, not
        // the supertype the property came from. By construction there is exactly one register_item
        // row and exactly one physical table it can live in, so this is sufficient proof no second
        // copy exists anywhere in the ancestor chain.
        assertThat(registerPartitionManager.findItemTypeId(itemId)).contains(subtypeId);
    }

    // requireItemTypeNotInUse's actually-in-use branch needs a real, committed register_item row
    // to trigger -- SchemaManagerIntegrationTest (schema-mutations only, no item CRUD) can't
    // produce that, so this lives here instead, alongside the other real-instance-creating tests.
    @Test
    void deleteItemDefinitionMutation_forATypeWithAnExistingInstance_throwsWithItsName() {
        String typeName = "MutReqProcInUse-" + UUID.randomUUID();
        schemaManager.applyMutations(List.of(new CreateItemDefinitionMutation(typeName, "d", List.of(), null, false, null)));
        UUID typeId = schemaManager.getAdminSchema().items().stream()
                .filter(i -> i.name().equals(typeName)).findFirst().orElseThrow().id();
        createItem(typeName);

        assertThatThrownBy(() -> schemaManager.applyMutations(List.of(new DeleteItemDefinitionMutation(typeId))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("items of this type still exist")
                .hasMessageContaining(typeName);
    }

    // Phase 2: confirms a link perspective declared on a supertype is usable by a subtype
    // instance -- researched and expected to already work with zero production-code changes,
    // since resolveEndpoint looks up perspectives via AdminItemDefinitionView.links(), which
    // SchemaViewBuilder.effectiveLinksAdmin already walks the supertype chain to build (Phase 1).
    // This test is the actual proof, not an assumption.
    @Test
    void linkCreateMutation_perspectiveDeclaredOnASupertype_isUsableByASubtypeInstance() {
        String vehicleName = "MutReqProcVehicle-" + UUID.randomUUID();
        schemaManager.applyMutations(List.of(new CreateItemDefinitionMutation(vehicleName, "d", List.of(), null, false, null)));
        UUID vehicleId = schemaManager.getAdminSchema().items().stream()
                .filter(i -> i.name().equals(vehicleName)).findFirst().orElseThrow().id();

        String carName = "MutReqProcCar-" + UUID.randomUUID();
        schemaManager.applyMutations(List.of(new CreateItemDefinitionMutation(carName, "d", List.of(), vehicleId, false, null)));

        String ownerName = "MutReqProcOwner-" + UUID.randomUUID();
        schemaManager.applyMutations(List.of(new CreateItemDefinitionMutation(ownerName, "d", List.of(), null, false, null)));
        UUID ownerTypeId = schemaManager.getAdminSchema().items().stream()
                .filter(i -> i.name().equals(ownerName)).findFirst().orElseThrow().id();

        // The perspective is declared against Vehicle, never against Car directly.
        schemaManager.applyMutations(List.of(new CreateLinkDefinitionMutation(List.of(), List.of(
                new CreatePerspectiveDefinitionMutation(vehicleId, "ownedBy", "d", 0, 1),
                new CreatePerspectiveDefinitionMutation(ownerTypeId, "owns", "d", 0, null)))));

        UUID carItemId = createItem(carName);
        UUID ownerItemId = createItem(ownerName);

        MutationResponse response = processor.process(new MutationRequest(List.of(), List.of(
                new LinkCreateMutation(
                        new LinkEndpointReference("ownedBy", new ExistingItemReference(carItemId)),
                        new LinkEndpointReference("owns", new ExistingItemReference(ownerItemId)),
                        Map.of()))), SOME_PRINCIPAL);

        assertThat(response.links()).extracting(r -> r.operation().name()).containsExactly("CREATE");
    }

    @Test
    void markingAnItemTypeAbstract_doesNotAffectExistingInstances_onlyBlocksNewOnes() {
        String typeName = "MutReqProcRetro-" + UUID.randomUUID();
        schemaManager.applyMutations(List.of(new CreateItemDefinitionMutation(typeName, "d", List.of(), null, false, null)));
        UUID typeId = schemaManager.getAdminSchema().items().stream()
                .filter(i -> i.name().equals(typeName)).findFirst().orElseThrow().id();
        UUID existingItemId = createItem(typeName);

        schemaManager.applyMutations(List.of(new UpdateItemDefinitionMutation(typeId, typeName, "d", null, true, null)));

        // Existing instance is untouched -- still readable, not retroactively invalidated.
        assertThat(registerPartitionManager.projectOne(typeId, existingItemId, "http://binary")).isPresent();

        // New instances are rejected going forward.
        assertThatThrownBy(() -> processor.process(new MutationRequest(
                List.of(new ItemCreateMutation(null, typeName, Map.of())), List.of()), SOME_PRINCIPAL))
                .isInstanceOf(MutationValidationException.class)
                .satisfies(e -> assertThat(((MutationValidationException) e).errors())
                        .anyMatch(err -> err.message().contains("abstract")));
    }

    // --- Item-create permission (item-type:create) ---
    //
    // Independent of item-type:read (SchemaManagerIntegrationTest's own schema-view test) -- a user
    // can see a type exists without being allowed to create instances of it, and vice versa. A null
    // principal fails closed, same as one with no grants at all -- see this class's own doc comment.

    @Test
    void itemCreateMutation_byAUserLackingItemTypeCreatePermission_reportsAValidationError() {
        String typeName = "MutReqProcCreatePerm-" + UUID.randomUUID();
        schemaManager.applyMutations(List.of(new CreateItemDefinitionMutation(typeName, "d", List.of(), null, false, null)));
        var user = securityRepo.createUser("sme-" + UUID.randomUUID(), "Restricted", null, false);
        var principal = new ResolvedPrincipal(user.id(), user.externalId(), user.displayName(), null, Set.of(), false);

        assertThatThrownBy(() -> processor.process(new MutationRequest(
                List.of(new ItemCreateMutation(null, typeName, Map.of())), List.of()), principal))
                .isInstanceOf(MutationValidationException.class)
                .satisfies(e -> assertThat(((MutationValidationException) e).errors())
                        .anyMatch(err -> err.message().toLowerCase().contains("not permitted")));
    }

    @Test
    void itemCreateMutation_byAUserGrantedItemTypeCreatePermission_succeeds() {
        String typeName = "MutReqProcCreatePermOk-" + UUID.randomUUID();
        schemaManager.applyMutations(List.of(new CreateItemDefinitionMutation(typeName, "d", List.of(), null, false, null)));
        UUID typeId = schemaManager.getAdminSchema().items().stream()
                .filter(i -> i.name().equals(typeName)).findFirst().orElseThrow().id();
        var user = securityRepo.createUser("sme-" + UUID.randomUUID(), "Restricted", null, false);
        var principal = new ResolvedPrincipal(user.id(), user.externalId(), user.displayName(), null, Set.of(), false);
        authRepo.grantItemType(typeId, "USER", user.id(), PermissionService.ITEM_TYPE_CREATE);

        MutationResponse response = processor.process(new MutationRequest(
                List.of(new ItemCreateMutation(null, typeName, Map.of())), List.of()), principal);

        assertThat(response.items()).hasSize(1);
    }

    @Test
    void itemCreateMutation_withANullPrincipal_reportsAValidationError() {
        String typeName = "MutReqProcCreatePermNull-" + UUID.randomUUID();
        schemaManager.applyMutations(List.of(new CreateItemDefinitionMutation(typeName, "d", List.of(), null, false, null)));

        assertThatThrownBy(() -> processor.process(new MutationRequest(
                List.of(new ItemCreateMutation(null, typeName, Map.of())), List.of()), null))
                .isInstanceOf(MutationValidationException.class)
                .satisfies(e -> assertThat(((MutationValidationException) e).errors())
                        .anyMatch(err -> err.message().toLowerCase().contains("not permitted")));
    }

    // --- Item property-write permission (property:write, marker-mediated) ---
    //
    // Update only -- see checkCanWriteItemProperties' own comment on why create is out of scope
    // (property:write is marker-mediated, and a brand-new item has no markers yet to grant against).

    @Test
    void itemUpdateMutation_byAUserLackingPropertyWritePermission_reportsAValidationError() {
        String typeName = "MutReqProcWritePerm-" + UUID.randomUUID();
        schemaManager.applyMutations(List.of(new CreateItemDefinitionMutation(typeName, "d", List.of(
                new CreatePropertyDefinitionMutation("notes", "d", PropertyType.STRING, PropertyCardinality.SINGLE, PropertyUsage.OPTIONAL, false)),
                null, false, null)));
        UUID typeId = schemaManager.getAdminSchema().items().stream()
                .filter(i -> i.name().equals(typeName)).findFirst().orElseThrow().id();
        UUID itemId = processor.process(new MutationRequest(
                List.of(new ItemCreateMutation(null, typeName, Map.of())), List.of()), SOME_PRINCIPAL)
                .items().get(0).itemId();

        var user = securityRepo.createUser("sme-" + UUID.randomUUID(), "Restricted", null, false);
        var principal = new ResolvedPrincipal(user.id(), user.externalId(), user.displayName(), null, Set.of(), false);
        var marker = authRepo.createMarker("sme-" + UUID.randomUUID(), "test", "ITEM_TYPE", typeId);
        markerAssignmentService.addItemMarker(itemId, marker.id(), "test", "test");
        authRepo.ensureMarkerGrant(marker.id(), "USER", user.id());
        // Deliberately no property:write grant for "notes".

        assertThatThrownBy(() -> processor.process(new MutationRequest(
                List.of(new ItemUpdateMutation(itemId, Map.of("notes", "hello"))), List.of()), principal))
                .isInstanceOf(MutationValidationException.class)
                .satisfies(e -> assertThat(((MutationValidationException) e).errors())
                        .anyMatch(err -> err.message().toLowerCase().contains("not permitted")));
    }

    @Test
    void itemUpdateMutation_byAUserGrantedPropertyWritePermission_succeeds() {
        String typeName = "MutReqProcWritePermOk-" + UUID.randomUUID();
        schemaManager.applyMutations(List.of(new CreateItemDefinitionMutation(typeName, "d", List.of(
                new CreatePropertyDefinitionMutation("notes", "d", PropertyType.STRING, PropertyCardinality.SINGLE, PropertyUsage.OPTIONAL, false)),
                null, false, null)));
        UUID typeId = schemaManager.getAdminSchema().items().stream()
                .filter(i -> i.name().equals(typeName)).findFirst().orElseThrow().id();
        UUID notesPropertyId = schemaManager.getAdminSchema().items().stream()
                .filter(i -> i.id().equals(typeId)).findFirst().orElseThrow()
                .properties().stream().filter(p -> p.name().equals("notes")).findFirst().orElseThrow().id();
        UUID itemId = processor.process(new MutationRequest(
                List.of(new ItemCreateMutation(null, typeName, Map.of())), List.of()), SOME_PRINCIPAL)
                .items().get(0).itemId();

        var user = securityRepo.createUser("sme-" + UUID.randomUUID(), "Restricted", null, false);
        var principal = new ResolvedPrincipal(user.id(), user.externalId(), user.displayName(), null, Set.of(), false);
        var marker = authRepo.createMarker("sme-" + UUID.randomUUID(), "test", "ITEM_TYPE", typeId);
        markerAssignmentService.addItemMarker(itemId, marker.id(), "test", "test");
        UUID grantId = authRepo.ensureMarkerGrant(marker.id(), "USER", user.id());
        authRepo.grantPropertyAccess(grantId, notesPropertyId, false, true);

        MutationResponse response = processor.process(new MutationRequest(
                List.of(new ItemUpdateMutation(itemId, Map.of("notes", "hello"))), List.of()), principal);

        assertThat(response.items()).hasSize(1);
    }

    @Test
    void itemUpdateMutation_withANullPrincipal_reportsAValidationError() {
        String typeName = "MutReqProcWritePermNull-" + UUID.randomUUID();
        schemaManager.applyMutations(List.of(new CreateItemDefinitionMutation(typeName, "d", List.of(
                new CreatePropertyDefinitionMutation("notes", "d", PropertyType.STRING, PropertyCardinality.SINGLE, PropertyUsage.OPTIONAL, false)),
                null, false, null)));
        UUID itemId = processor.process(new MutationRequest(
                List.of(new ItemCreateMutation(null, typeName, Map.of())), List.of()), SOME_PRINCIPAL)
                .items().get(0).itemId();

        assertThatThrownBy(() -> processor.process(new MutationRequest(
                List.of(new ItemUpdateMutation(itemId, Map.of("notes", "hello"))), List.of()), null))
                .isInstanceOf(MutationValidationException.class)
                .satisfies(e -> assertThat(((MutationValidationException) e).errors())
                        .anyMatch(err -> err.message().toLowerCase().contains("not permitted")));
    }

    // --- Item-delete permission (item:delete, marker-mediated) ---

    @Test
    void itemDeleteMutation_byAUserLackingItemDeletePermission_reportsAValidationError() {
        String typeName = "MutReqProcDeletePerm-" + UUID.randomUUID();
        schemaManager.applyMutations(List.of(new CreateItemDefinitionMutation(typeName, "d", List.of(), null, false, null)));
        UUID typeId = schemaManager.getAdminSchema().items().stream()
                .filter(i -> i.name().equals(typeName)).findFirst().orElseThrow().id();
        UUID itemId = processor.process(new MutationRequest(
                List.of(new ItemCreateMutation(null, typeName, Map.of())), List.of()), SOME_PRINCIPAL)
                .items().get(0).itemId();

        var user = securityRepo.createUser("sme-" + UUID.randomUUID(), "Restricted", null, false);
        var principal = new ResolvedPrincipal(user.id(), user.externalId(), user.displayName(), null, Set.of(), false);
        var marker = authRepo.createMarker("sme-" + UUID.randomUUID(), "test", "ITEM_TYPE", typeId);
        markerAssignmentService.addItemMarker(itemId, marker.id(), "test", "test");
        UUID grantId = authRepo.ensureMarkerGrant(marker.id(), "USER", user.id());
        authRepo.setItemPermissions(grantId, true, false); // item:read only, no item:delete

        assertThatThrownBy(() -> processor.process(new MutationRequest(
                List.of(new ItemDeleteMutation(itemId)), List.of()), principal))
                .isInstanceOf(MutationValidationException.class)
                .satisfies(e -> assertThat(((MutationValidationException) e).errors())
                        .anyMatch(err -> err.message().toLowerCase().contains("not permitted")));
    }

    @Test
    void itemDeleteMutation_byAUserGrantedItemDeletePermission_succeeds() {
        String typeName = "MutReqProcDeletePermOk-" + UUID.randomUUID();
        schemaManager.applyMutations(List.of(new CreateItemDefinitionMutation(typeName, "d", List.of(), null, false, null)));
        UUID typeId = schemaManager.getAdminSchema().items().stream()
                .filter(i -> i.name().equals(typeName)).findFirst().orElseThrow().id();
        UUID itemId = processor.process(new MutationRequest(
                List.of(new ItemCreateMutation(null, typeName, Map.of())), List.of()), SOME_PRINCIPAL)
                .items().get(0).itemId();

        var user = securityRepo.createUser("sme-" + UUID.randomUUID(), "Restricted", null, false);
        var principal = new ResolvedPrincipal(user.id(), user.externalId(), user.displayName(), null, Set.of(), false);
        var marker = authRepo.createMarker("sme-" + UUID.randomUUID(), "test", "ITEM_TYPE", typeId);
        markerAssignmentService.addItemMarker(itemId, marker.id(), "test", "test");
        UUID grantId = authRepo.ensureMarkerGrant(marker.id(), "USER", user.id());
        authRepo.setItemPermissions(grantId, true, true); // item:read + item:delete

        MutationResponse response = processor.process(new MutationRequest(
                List.of(new ItemDeleteMutation(itemId)), List.of()), principal);

        assertThat(response.items()).extracting(r -> r.operation().name()).containsExactly("DELETE");
    }

    @Test
    void itemDeleteMutation_withANullPrincipal_reportsAValidationError() {
        String typeName = "MutReqProcDeletePermNull-" + UUID.randomUUID();
        schemaManager.applyMutations(List.of(new CreateItemDefinitionMutation(typeName, "d", List.of(), null, false, null)));
        UUID itemId = processor.process(new MutationRequest(
                List.of(new ItemCreateMutation(null, typeName, Map.of())), List.of()), SOME_PRINCIPAL)
                .items().get(0).itemId();

        assertThatThrownBy(() -> processor.process(new MutationRequest(
                List.of(new ItemDeleteMutation(itemId)), List.of()), null))
                .isInstanceOf(MutationValidationException.class)
                .satisfies(e -> assertThat(((MutationValidationException) e).errors())
                        .anyMatch(err -> err.message().toLowerCase().contains("not permitted")));
    }

    // --- Link-create permission (link:create, marker-mediated, per perspective) ---
    //
    // Both endpoints' own link:create grant are required -- see processLinkCreate's own comment.

    private UUID perspectiveIdFor(UUID itemId, String perspectiveName) {
        UUID typeId = registerPartitionManager.findItemTypeId(itemId).orElseThrow();
        return schemaManager.getAdminSchema().items().stream()
                .filter(i -> i.id().equals(typeId)).findFirst().orElseThrow()
                .links().get(perspectiveName).get(0).id();
    }

    @Test
    void linkCreateMutation_byAUserLackingLinkCreatePermissionOnOneSide_reportsAValidationError() {
        FreshLinkFixture link = createFreshLinkType(List.of());
        UUID perspectiveAId = perspectiveIdFor(link.aItemId(), link.perspectiveA());

        var user = securityRepo.createUser("sme-" + UUID.randomUUID(), "Restricted", null, false);
        var principal = new ResolvedPrincipal(user.id(), user.externalId(), user.displayName(), null, Set.of(), false);
        var marker = authRepo.createMarker("sme-" + UUID.randomUUID(), "test", "ITEM_TYPE", registerPartitionManager.findItemTypeId(link.aItemId()).orElseThrow());
        markerAssignmentService.addItemMarker(link.aItemId(), marker.id(), "test", "test");
        UUID grantId = authRepo.ensureMarkerGrant(marker.id(), "USER", user.id());
        authRepo.grantLinkPerspectiveAccess(grantId, perspectiveAId, true, false, false); // link:create on side A only

        assertThatThrownBy(() -> processor.process(new MutationRequest(List.of(),
                List.of(link.create(Map.of()))), principal))
                .isInstanceOf(MutationValidationException.class)
                .satisfies(e -> assertThat(((MutationValidationException) e).errors())
                        .anyMatch(err -> err.message().toLowerCase().contains("not permitted")));
    }

    @Test
    void linkCreateMutation_byAUserGrantedLinkCreatePermissionOnBothSides_succeeds() {
        FreshLinkFixture link = createFreshLinkType(List.of());
        UUID perspectiveAId = perspectiveIdFor(link.aItemId(), link.perspectiveA());
        UUID perspectiveBId = perspectiveIdFor(link.bItemId(), link.perspectiveB());

        var user = securityRepo.createUser("sme-" + UUID.randomUUID(), "Restricted", null, false);
        var principal = new ResolvedPrincipal(user.id(), user.externalId(), user.displayName(), null, Set.of(), false);
        var markerA = authRepo.createMarker("sme-" + UUID.randomUUID(), "test", "ITEM_TYPE", registerPartitionManager.findItemTypeId(link.aItemId()).orElseThrow());
        markerAssignmentService.addItemMarker(link.aItemId(), markerA.id(), "test", "test");
        UUID grantIdA = authRepo.ensureMarkerGrant(markerA.id(), "USER", user.id());
        authRepo.grantLinkPerspectiveAccess(grantIdA, perspectiveAId, true, false, false);
        var markerB = authRepo.createMarker("sme-" + UUID.randomUUID(), "test", "ITEM_TYPE", registerPartitionManager.findItemTypeId(link.bItemId()).orElseThrow());
        markerAssignmentService.addItemMarker(link.bItemId(), markerB.id(), "test", "test");
        UUID grantIdB = authRepo.ensureMarkerGrant(markerB.id(), "USER", user.id());
        authRepo.grantLinkPerspectiveAccess(grantIdB, perspectiveBId, true, false, false);

        MutationResponse response = processor.process(new MutationRequest(List.of(),
                List.of(link.create(Map.of()))), principal);

        assertThat(response.links()).hasSize(1);
    }

    @Test
    void linkCreateMutation_withANullPrincipal_reportsAValidationError() {
        FreshLinkFixture link = createFreshLinkType(List.of());

        assertThatThrownBy(() -> processor.process(new MutationRequest(List.of(),
                List.of(link.create(Map.of()))), null))
                .isInstanceOf(MutationValidationException.class)
                .satisfies(e -> assertThat(((MutationValidationException) e).errors())
                        .anyMatch(err -> err.message().toLowerCase().contains("not permitted")));
    }

    // --- Link-delete permission (link:delete, marker-mediated, per perspective) ---
    //
    // Both endpoints' own link:delete grant are required, same symmetric reasoning as link:create.

    @Test
    void linkDeleteMutation_byAUserLackingLinkDeletePermissionOnOneSide_reportsAValidationError() {
        FreshLinkFixture link = createFreshLinkType(List.of());
        UUID linkId = processor.process(new MutationRequest(List.of(),
                List.of(link.create(Map.of()))), SOME_PRINCIPAL)
                .links().get(0).linkId();
        UUID perspectiveAId = perspectiveIdFor(link.aItemId(), link.perspectiveA());

        var user = securityRepo.createUser("sme-" + UUID.randomUUID(), "Restricted", null, false);
        var principal = new ResolvedPrincipal(user.id(), user.externalId(), user.displayName(), null, Set.of(), false);
        var marker = authRepo.createMarker("sme-" + UUID.randomUUID(), "test", "ITEM_TYPE", registerPartitionManager.findItemTypeId(link.aItemId()).orElseThrow());
        markerAssignmentService.addItemMarker(link.aItemId(), marker.id(), "test", "test");
        UUID grantId = authRepo.ensureMarkerGrant(marker.id(), "USER", user.id());
        authRepo.grantLinkPerspectiveAccess(grantId, perspectiveAId, false, false, true); // link:delete on side A only

        assertThatThrownBy(() -> processor.process(new MutationRequest(List.of(),
                List.of(new LinkDeleteMutation(linkId))), principal))
                .isInstanceOf(MutationValidationException.class)
                .satisfies(e -> assertThat(((MutationValidationException) e).errors())
                        .anyMatch(err -> err.message().toLowerCase().contains("not permitted")));
    }

    @Test
    void linkDeleteMutation_byAUserGrantedLinkDeletePermissionOnBothSides_succeeds() {
        FreshLinkFixture link = createFreshLinkType(List.of());
        UUID linkId = processor.process(new MutationRequest(List.of(),
                List.of(link.create(Map.of()))), SOME_PRINCIPAL)
                .links().get(0).linkId();
        UUID perspectiveAId = perspectiveIdFor(link.aItemId(), link.perspectiveA());
        UUID perspectiveBId = perspectiveIdFor(link.bItemId(), link.perspectiveB());

        var user = securityRepo.createUser("sme-" + UUID.randomUUID(), "Restricted", null, false);
        var principal = new ResolvedPrincipal(user.id(), user.externalId(), user.displayName(), null, Set.of(), false);
        var markerA = authRepo.createMarker("sme-" + UUID.randomUUID(), "test", "ITEM_TYPE", registerPartitionManager.findItemTypeId(link.aItemId()).orElseThrow());
        markerAssignmentService.addItemMarker(link.aItemId(), markerA.id(), "test", "test");
        UUID grantIdA = authRepo.ensureMarkerGrant(markerA.id(), "USER", user.id());
        authRepo.grantLinkPerspectiveAccess(grantIdA, perspectiveAId, false, false, true);
        var markerB = authRepo.createMarker("sme-" + UUID.randomUUID(), "test", "ITEM_TYPE", registerPartitionManager.findItemTypeId(link.bItemId()).orElseThrow());
        markerAssignmentService.addItemMarker(link.bItemId(), markerB.id(), "test", "test");
        UUID grantIdB = authRepo.ensureMarkerGrant(markerB.id(), "USER", user.id());
        authRepo.grantLinkPerspectiveAccess(grantIdB, perspectiveBId, false, false, true);

        MutationResponse response = processor.process(new MutationRequest(List.of(),
                List.of(new LinkDeleteMutation(linkId))), principal);

        assertThat(response.links()).extracting(r -> r.operation().name()).containsExactly("DELETE");
    }

    @Test
    void linkDeleteMutation_withANullPrincipal_reportsAValidationError() {
        FreshLinkFixture link = createFreshLinkType(List.of());
        UUID linkId = processor.process(new MutationRequest(List.of(),
                List.of(link.create(Map.of()))), SOME_PRINCIPAL)
                .links().get(0).linkId();

        assertThatThrownBy(() -> processor.process(new MutationRequest(List.of(),
                List.of(new LinkDeleteMutation(linkId))), null))
                .isInstanceOf(MutationValidationException.class)
                .satisfies(e -> assertThat(((MutationValidationException) e).errors())
                        .anyMatch(err -> err.message().toLowerCase().contains("not permitted")));
    }

    // --- Link property-write permission (link_property:write, marker-mediated) ---
    //
    // Applies to both create and update -- see checkCanWriteLinkProperties' own comment on why
    // link properties don't share item properties' create-time exemption. Both endpoints' own
    // grant are required, same symmetric reasoning as link:create/delete.

    // AdminLinkView has no name of its own to filter links by first, so the property name has to
    // be globally unique to find this exact property back afterward -- same reasoning as
    // linkUpdateMutation_omittingALinkPropertyThatBecameRequiredAfterTheLinkWasCreated's own
    // notesPropertyName.
    private FreshLinkFixture createFreshLinkTypeWithProperty(String propertyName) {
        return createFreshLinkType(List.of(
                new CreatePropertyDefinitionMutation(propertyName, "d", PropertyType.STRING, PropertyCardinality.SINGLE, PropertyUsage.OPTIONAL, false)));
    }

    private UUID linkPropertyId(String propertyName) {
        return schemaManager.getAdminSchema().links().stream()
                .flatMap(l -> l.properties().stream())
                .filter(p -> p.name().equals(propertyName))
                .findFirst().orElseThrow().id();
    }

    @Test
    void linkUpdateMutation_byAUserLackingLinkPropertyWritePermissionOnOneSide_reportsAValidationError() {
        String propertyName = "note-" + UUID.randomUUID();
        FreshLinkFixture link = createFreshLinkTypeWithProperty(propertyName);
        UUID notePropertyId = linkPropertyId(propertyName);
        UUID linkId = processor.process(new MutationRequest(List.of(),
                List.of(link.create(Map.of()))), SOME_PRINCIPAL)
                .links().get(0).linkId();

        var user = securityRepo.createUser("sme-" + UUID.randomUUID(), "Restricted", null, false);
        var principal = new ResolvedPrincipal(user.id(), user.externalId(), user.displayName(), null, Set.of(), false);
        var markerA = authRepo.createMarker("sme-" + UUID.randomUUID(), "test", "ITEM_TYPE", registerPartitionManager.findItemTypeId(link.aItemId()).orElseThrow());
        markerAssignmentService.addItemMarker(link.aItemId(), markerA.id(), "test", "test");
        UUID grantIdA = authRepo.ensureMarkerGrant(markerA.id(), "USER", user.id());
        authRepo.grantLinkPropertyAccess(grantIdA, notePropertyId, false, true); // write on side A only

        assertThatThrownBy(() -> processor.process(new MutationRequest(List.of(),
                List.of(new LinkUpdateMutation(linkId, Map.of(propertyName, "hello")))), principal))
                .isInstanceOf(MutationValidationException.class)
                .satisfies(e -> assertThat(((MutationValidationException) e).errors())
                        .anyMatch(err -> err.message().toLowerCase().contains("not permitted")));
    }

    @Test
    void linkUpdateMutation_byAUserGrantedLinkPropertyWritePermissionOnBothSides_succeeds() {
        String propertyName = "note-" + UUID.randomUUID();
        FreshLinkFixture link = createFreshLinkTypeWithProperty(propertyName);
        UUID notePropertyId = linkPropertyId(propertyName);
        UUID linkId = processor.process(new MutationRequest(List.of(),
                List.of(link.create(Map.of()))), SOME_PRINCIPAL)
                .links().get(0).linkId();

        var user = securityRepo.createUser("sme-" + UUID.randomUUID(), "Restricted", null, false);
        var principal = new ResolvedPrincipal(user.id(), user.externalId(), user.displayName(), null, Set.of(), false);
        var markerA = authRepo.createMarker("sme-" + UUID.randomUUID(), "test", "ITEM_TYPE", registerPartitionManager.findItemTypeId(link.aItemId()).orElseThrow());
        markerAssignmentService.addItemMarker(link.aItemId(), markerA.id(), "test", "test");
        UUID grantIdA = authRepo.ensureMarkerGrant(markerA.id(), "USER", user.id());
        authRepo.grantLinkPropertyAccess(grantIdA, notePropertyId, false, true);
        var markerB = authRepo.createMarker("sme-" + UUID.randomUUID(), "test", "ITEM_TYPE", registerPartitionManager.findItemTypeId(link.bItemId()).orElseThrow());
        markerAssignmentService.addItemMarker(link.bItemId(), markerB.id(), "test", "test");
        UUID grantIdB = authRepo.ensureMarkerGrant(markerB.id(), "USER", user.id());
        authRepo.grantLinkPropertyAccess(grantIdB, notePropertyId, false, true);

        MutationResponse response = processor.process(new MutationRequest(List.of(),
                List.of(new LinkUpdateMutation(linkId, Map.of(propertyName, "hello")))), principal);

        assertThat(response.links()).extracting(r -> r.operation().name()).containsExactly("UPDATE");
    }

    @Test
    void linkUpdateMutation_withANullPrincipal_reportsAValidationError() {
        String propertyName = "note-" + UUID.randomUUID();
        FreshLinkFixture link = createFreshLinkTypeWithProperty(propertyName);
        UUID linkId = processor.process(new MutationRequest(List.of(),
                List.of(link.create(Map.of()))), SOME_PRINCIPAL)
                .links().get(0).linkId();

        assertThatThrownBy(() -> processor.process(new MutationRequest(List.of(),
                List.of(new LinkUpdateMutation(linkId, Map.of(propertyName, "hello")))), null))
                .isInstanceOf(MutationValidationException.class)
                .satisfies(e -> assertThat(((MutationValidationException) e).errors())
                        .anyMatch(err -> err.message().toLowerCase().contains("not permitted")));
    }

    // Isolates the NEW create-time link-property-write check from the already-covered
    // link:create check above -- link:create is granted on both sides here, so the only thing
    // that can still fail is the property-write grant itself (deliberately absent).
    @Test
    void linkCreateMutation_withLinkCreateGrantedButLinkPropertyWriteNotGranted_reportsAValidationError() {
        String propertyName = "note-" + UUID.randomUUID();
        FreshLinkFixture link = createFreshLinkTypeWithProperty(propertyName);
        UUID perspectiveAId = perspectiveIdFor(link.aItemId(), link.perspectiveA());
        UUID perspectiveBId = perspectiveIdFor(link.bItemId(), link.perspectiveB());

        var user = securityRepo.createUser("sme-" + UUID.randomUUID(), "Restricted", null, false);
        var principal = new ResolvedPrincipal(user.id(), user.externalId(), user.displayName(), null, Set.of(), false);
        var markerA = authRepo.createMarker("sme-" + UUID.randomUUID(), "test", "ITEM_TYPE", registerPartitionManager.findItemTypeId(link.aItemId()).orElseThrow());
        markerAssignmentService.addItemMarker(link.aItemId(), markerA.id(), "test", "test");
        UUID grantIdA = authRepo.ensureMarkerGrant(markerA.id(), "USER", user.id());
        authRepo.grantLinkPerspectiveAccess(grantIdA, perspectiveAId, true, false, false);
        var markerB = authRepo.createMarker("sme-" + UUID.randomUUID(), "test", "ITEM_TYPE", registerPartitionManager.findItemTypeId(link.bItemId()).orElseThrow());
        markerAssignmentService.addItemMarker(link.bItemId(), markerB.id(), "test", "test");
        UUID grantIdB = authRepo.ensureMarkerGrant(markerB.id(), "USER", user.id());
        authRepo.grantLinkPerspectiveAccess(grantIdB, perspectiveBId, true, false, false);
        // Deliberately no link_property:write grant for this property on either side.

        assertThatThrownBy(() -> processor.process(new MutationRequest(List.of(),
                List.of(link.create(Map.of(propertyName, "hello")))), principal))
                .isInstanceOf(MutationValidationException.class)
                .satisfies(e -> assertThat(((MutationValidationException) e).errors())
                        .anyMatch(err -> err.message().toLowerCase().contains("not permitted") && err.message().toLowerCase().contains("link property")));
    }
}
