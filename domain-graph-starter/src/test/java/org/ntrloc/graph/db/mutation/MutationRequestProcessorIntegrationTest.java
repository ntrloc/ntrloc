package org.ntrloc.graph.db.mutation;

import org.junit.jupiter.api.Test;
import org.ntrloc.graph.AbstractIntegrationTest;
import org.ntrloc.graph.db.coordinator.CoordinatorTestDomainInitializer;
import org.ntrloc.graph.db.partition.register.RegisterPartitionManager;
import org.ntrloc.graph.db.partition.security.ResolvedPrincipal;
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

    // --- validateScalar: BINARY rejection, LONG, DATETIME ---

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
}
