package org.ntrloc.graph.db.mutation;

import org.junit.jupiter.api.Test;
import org.ntrloc.graph.AbstractIntegrationTest;
import org.ntrloc.graph.db.coordinator.CoordinatorTestDomainInitializer;
import org.ntrloc.graph.db.partition.register.RegisterPartitionManager;
import org.ntrloc.graph.db.partition.schema.SchemaManager;
import org.ntrloc.graph.db.partition.schema.definition.PropertyCardinality;
import org.ntrloc.graph.db.partition.schema.definition.PropertyType;
import org.ntrloc.graph.db.partition.schema.definition.PropertyUsage;
import org.ntrloc.graph.db.partition.schema.definition.mutation.CreateItemDefinitionMutation;
import org.ntrloc.graph.db.partition.schema.definition.mutation.CreateLinkDefinitionMutation;
import org.ntrloc.graph.db.partition.schema.definition.mutation.CreatePerspectiveDefinitionMutation;
import org.ntrloc.graph.db.partition.schema.definition.mutation.CreatePropertyDefinitionMutation;
import org.ntrloc.graph.db.partition.schema.definition.mutation.DeleteItemDefinitionMutation;
import org.ntrloc.graph.db.partition.schema.definition.mutation.UpdateItemDefinitionMutation;
import org.ntrloc.graph.db.partition.security.ResolvedPrincipal;
import org.ntrloc.graph.db.projection.ProjectedLink;
import org.springframework.beans.factory.annotation.Autowired;

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

    // --- validateScalar: BINARY rejection, LONG, DOUBLE, DATETIME ---

    @Test
    void binaryProperty_isRejected_binaryPropertiesCannotBeSetViaMutation() {
        assertThatThrownBy(() -> processor.process(new MutationRequest(
                List.of(new ItemCreateMutation(null, "MutReqProcA", Map.of("attachment", "some-value"))), List.of()),
                SOME_PRINCIPAL))
                .isInstanceOf(MutationValidationException.class)
                .satisfies(e -> assertThat(((MutationValidationException) e).errors())
                        .anyMatch(err -> err.message().contains("binary-typed")));
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

    @Test
    void objectProperty_updateWithNull_clearsEntireSubtree() {
        MutationResponse createResponse = processor.process(new MutationRequest(
                List.of(new ItemCreateMutation(null, "MutReqProcA",
                        Map.of("extra", Map.of("nested", "a", "second", "b")))), List.of()),
                SOME_PRINCIPAL);
        UUID itemId = createResponse.items().get(0).itemId();

        Map<String, Object> clearDiff = new HashMap<>();
        clearDiff.put("extra", null);
        processor.process(new MutationRequest(
                List.of(new ItemUpdateMutation(itemId, clearDiff)), List.of()),
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
    void linkUpdateMutation_withObjectPropertySetToNull_clearsEntireSubtree() {
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
        processor.process(new MutationRequest(List.of(), List.of(new LinkUpdateMutation(linkId, clearDiff))), SOME_PRINCIPAL);

        var item = registerPartitionManager.projectOne(fixture.cTypeId(), cId, "http://binary").orElseThrow();
        var link = item.links().values().stream().flatMap(List::stream)
                .filter(l -> l.linkId().equals(linkId)).findFirst().orElseThrow();
        assertThat(link.properties()).doesNotContainKey("linkExtra");
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
                new CreatePropertyDefinitionMutation("inheritedProp", "d", PropertyType.STRING, PropertyCardinality.SINGLE, PropertyUsage.OPTIONAL, false, java.util.List.of())), null, false, null)));
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
}
