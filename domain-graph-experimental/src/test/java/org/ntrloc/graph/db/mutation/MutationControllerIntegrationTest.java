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

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private RegisterPartitionManager registerPartitionManager;

    @Autowired
    private CoordinatorTestDomainInitializer fixture;

    @Test
    void createTwoItemsAndLinkThemInOneRequest_usingRefIdLocalReferences() {
        MutationRequest request = new MutationRequest(
                List.of(
                        new ItemCreateMutation("product-1", fixture.productTypeId(), Map.of("name", "Widget")),
                        new ItemCreateMutation("contributor-1", fixture.contributorTypeId(), Map.of("name", "Ada"))
                ),
                List.of(
                        new LinkCreateMutation(fixture.linkTypeId(),
                                List.of(new LinkEndpointReference(fixture.productPerspectiveId(), new NewItemReference("product-1")),
                                        new LinkEndpointReference(fixture.contributorPerspectiveId(), new NewItemReference("contributor-1"))),
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
                    { "type": "CREATE", "refId": null, "itemTypeId": "%s", "properties": { "name": "Raw Widget" } }
                  ],
                  "links": []
                }
                """.formatted(fixture.productTypeId());

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
                        List.of(new ItemCreateMutation("p", fixture.productTypeId(), Map.of("name", "Widget")),
                                new ItemCreateMutation("c", fixture.contributorTypeId(), Map.of("name", "Ada"))),
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
                        new LinkCreateMutation(fixture.linkTypeId(),
                                List.of(new LinkEndpointReference(fixture.productPerspectiveId(), new ExistingItemReference(realProductId)),
                                        new LinkEndpointReference(fixture.contributorPerspectiveId(), new ExistingItemReference(realContributorId))),
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
                new LinkCreateMutation(fixture.linkTypeId(),
                        List.of(new LinkEndpointReference(fixture.productPerspectiveId(), new NewItemReference("does-not-exist")),
                                new LinkEndpointReference(fixture.contributorPerspectiveId(), new ExistingItemReference(UUID.randomUUID()))),
                        Map.of())
        ));

        webTestClient.post().uri("/mutation")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .exchange()
                .expectStatus().isBadRequest();
    }
}
