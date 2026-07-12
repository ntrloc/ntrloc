package org.ntrloc.graph.db.mutation;

import org.junit.jupiter.api.Test;
import org.ntrloc.graph.AbstractIntegrationTest;
import org.ntrloc.graph.db.coordinator.CoordinatorTestDomainInitializer;
import org.ntrloc.graph.db.partition.register.RegisterPartitionManager;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class MutationControllerIntegrationTest extends AbstractIntegrationTest {

    private static final String PRODUCT_TYPE = "CoordinatorTestProduct";
    private static final String CONTRIBUTOR_TYPE = "CoordinatorTestContributor";
    private static final String PRODUCT_PERSPECTIVE = "products";
    private static final String CONTRIBUTOR_PERSPECTIVE = "contributors";

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private RegisterPartitionManager registerPartitionManager;

    @Autowired
    private CoordinatorTestDomainInitializer fixture;

    @Test
    void createTwoItemsAndLinkThemInOneRequest_usingRefIdNewReferences() {
        MutationRequest request = new MutationRequest(
                List.of(
                        new ItemCreateMutation("product-1", PRODUCT_TYPE, Map.of("name", "Widget")),
                        new ItemCreateMutation("contributor-1", CONTRIBUTOR_TYPE, Map.of("name", "Ada"))
                ),
                List.of(
                        new LinkCreateMutation(
                                new LinkEndpointReference(PRODUCT_PERSPECTIVE, new NewItemReference("product-1")),
                                new LinkEndpointReference(CONTRIBUTOR_PERSPECTIVE, new NewItemReference("contributor-1")),
                                Map.of("role", "author"))
                ));

        MutationResponse response = webTestClient.post().uri("/mutation")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .exchange()
                .expectStatus().isOk()
                .expectBody(MutationResponse.class)
                .returnResult()
                .getResponseBody();

        assertThat(response.items()).hasSize(2);
        assertThat(response.links()).hasSize(1);

        UUID productId = response.items().stream().filter(r -> "product-1".equals(r.refId())).findFirst().orElseThrow().itemId();
        UUID contributorId = response.items().stream().filter(r -> "contributor-1".equals(r.refId())).findFirst().orElseThrow().itemId();

        var product = registerPartitionManager.projectOne(fixture.productTypeId(), productId, "http://binary").orElseThrow();
        assertThat(product.links().values().stream().flatMap(List::stream))
                .anyMatch(link -> link.item().itemId().equals(contributorId) && link.properties().get("role").equals("author"));
    }

    @Test
    void rawJsonWithTypeDiscriminators_deserializesCorrectlyOverHttp() {
        String rawJson = """
                {
                  "items": [
                    { "type": "CREATE", "refId": null, "itemTypeName": "%s", "properties": { "name": "Raw Widget" } }
                  ],
                  "links": []
                }
                """.formatted(PRODUCT_TYPE);

        webTestClient.post().uri("/mutation")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(rawJson)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.items[0].operation").isEqualTo("CREATE")
                .jsonPath("$.items[0].itemId").exists();
    }

    @Test
    void updateThenDeleteThroughEndpoint_appliesAndCascades() {
        MutationResponse createResponse = webTestClient.post().uri("/mutation")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new MutationRequest(
                        List.of(new ItemCreateMutation("p", PRODUCT_TYPE, Map.of("name", "Widget")),
                                new ItemCreateMutation("c", CONTRIBUTOR_TYPE, Map.of("name", "Ada"))),
                        List.of()))
                .exchange()
                .expectStatus().isOk()
                .expectBody(MutationResponse.class)
                .returnResult().getResponseBody();

        UUID realProductId = createResponse.items().stream().filter(r -> "p".equals(r.refId())).findFirst().orElseThrow().itemId();
        UUID realContributorId = createResponse.items().stream().filter(r -> "c".equals(r.refId())).findFirst().orElseThrow().itemId();

        webTestClient.post().uri("/mutation")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new MutationRequest(List.of(), List.of(
                        new LinkCreateMutation(
                                new LinkEndpointReference(PRODUCT_PERSPECTIVE, new ExistingItemReference(realProductId)),
                                new LinkEndpointReference(CONTRIBUTOR_PERSPECTIVE, new ExistingItemReference(realContributorId)),
                                Map.of())
                )))
                .exchange()
                .expectStatus().isOk();

        webTestClient.post().uri("/mutation")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new MutationRequest(List.of(new ItemUpdateMutation(realProductId, Map.of("name", "Widget Pro"))), List.of()))
                .exchange()
                .expectStatus().isOk();

        var updated = registerPartitionManager.projectOne(fixture.productTypeId(), realProductId, "http://binary").orElseThrow();
        assertThat(updated.properties()).containsEntry("name", "Widget Pro");

        // Deleting the product alone should auto-cascade the link, per ItemDeleteCascadeExpander.
        webTestClient.post().uri("/mutation")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new MutationRequest(List.of(new ItemDeleteMutation(realProductId)), List.of()))
                .exchange()
                .expectStatus().isOk();

        assertThat(registerPartitionManager.projectOne(fixture.productTypeId(), realProductId, "http://binary")).isEmpty();
        var survivingContributor = registerPartitionManager.projectOne(fixture.contributorTypeId(), realContributorId, "http://binary").orElseThrow();
        assertThat(survivingContributor.links().values().stream().flatMap(List::stream)).isEmpty();
    }

    @Test
    void unknownRefId_returnsBadRequest() {
        MutationRequest request = new MutationRequest(List.of(), List.of(
                new LinkCreateMutation(
                        new LinkEndpointReference(PRODUCT_PERSPECTIVE, new NewItemReference("does-not-exist")),
                        new LinkEndpointReference(CONTRIBUTOR_PERSPECTIVE, new ExistingItemReference(UUID.randomUUID())),
                        Map.of())
        ));

        webTestClient.post().uri("/mutation")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .exchange()
                .expectStatus().isBadRequest();
    }

    @Test
    void unknownItemTypeName_returnsNotFound() {
        MutationRequest request = new MutationRequest(
                List.of(new ItemCreateMutation(null, "NoSuchItemType", Map.of())), List.of());

        webTestClient.post().uri("/mutation")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .exchange()
                .expectStatus().isNotFound();
    }
}
