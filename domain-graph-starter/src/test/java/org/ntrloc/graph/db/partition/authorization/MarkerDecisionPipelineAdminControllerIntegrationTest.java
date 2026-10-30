package org.ntrloc.graph.db.partition.authorization;

import org.springframework.http.HttpHeaders;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.ntrloc.graph.AbstractIntegrationTest;
import org.ntrloc.graph.access.AddMarkers;
import org.ntrloc.graph.access.Condition;
import org.ntrloc.graph.access.End;
import org.ntrloc.graph.access.Gate;
import org.ntrloc.graph.access.Pipeline;
import org.ntrloc.graph.access.PipelineLayout;
import org.ntrloc.graph.access.Point;
import org.ntrloc.graph.access.Route;
import org.ntrloc.graph.access.Self;
import org.ntrloc.graph.access.Start;
import org.ntrloc.graph.access.StepToProperty;
import org.ntrloc.graph.access.StringEqualsPredicate;
import org.ntrloc.graph.db.coordinator.CoordinatorTestDomainInitializer;
import org.ntrloc.graph.db.partition.authorization.MarkerDecisionPipelineAdminController.CreatePipelineRequest;
import org.ntrloc.graph.db.partition.authorization.MarkerDecisionPipelineAdminController.PipelineView;
import org.ntrloc.graph.db.partition.authorization.MarkerDecisionPipelineAdminController.UpdatePipelineRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.reactive.server.WebTestClient;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

// Proves the admin HTTP surface, not just the repository directly -- in particular that WebFlux's
// own Jackson codec round-trips a Pipeline (sealed interfaces, nested graphs) correctly through a
// real request/response body, the same way MarkerDecisionPipelineRepositoryIntegrationTest already
// proved for the JSONB storage path.
class MarkerDecisionPipelineAdminControllerIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private CoordinatorTestDomainInitializer fixture;

    @Autowired
    private ObjectMapper objectMapper;

    // DefaultPipelineInitializer auto-creates a no-op pipeline for every item type the moment it's
    // created, including fixture.productTypeId() -- every test below assumes a pristine, pipeline-
    // less item type to POST its own pipeline against (see CoordinatorTestDomainInitializer's own
    // comment on removeAutoCreatedPipelines).
    @BeforeEach
    void removeAutoCreatedPipelines() {
        fixture.removeAutoCreatedPipelines();
    }

    private Pipeline simplePipeline(UUID markerId) {
        Condition gate = new Condition(UUID.randomUUID(), new StepToProperty(fixture.colorPropertyId(), false, new Self()),
                new StringEqualsPredicate(null, "red"));
        AddMarkers add = new AddMarkers(UUID.randomUUID(), Gate.of(gate.id()), List.of(markerId));
        Start start = new Start(UUID.randomUUID());
        End end = new End(UUID.randomUUID());
        return new Pipeline(UUID.randomUUID(), List.of(gate), List.of(add), start, end, List.of(
                new Route(start.id(), List.of(add.id())),
                new Route(add.id(), List.of(end.id()))));
    }

    @Test
    void createThenGet_roundTripsThePipelineAndLayoutThroughHttpJson() {
        Pipeline pipeline = simplePipeline(UUID.randomUUID());
        // A condition group has no id; the editor keys its box "group-<first condition id>".
        PipelineLayout layout = new PipelineLayout(Map.of(pipeline.start().id().toString(), new Point(10, 20),
                pipeline.end().id().toString(), new Point(300, 20), "group-" + UUID.randomUUID(), new Point(150, 80)), Map.of());

        PipelineView created = webTestClient.post().uri("/api/admin/marker-decision-pipelines")
                .header(HttpHeaders.AUTHORIZATION, bearerTokenFor("root@example.com"))
                .bodyValue(new CreatePipelineRequest(fixture.productTypeId(), pipeline, layout))
                .exchange()
                .expectStatus().isOk()
                .expectBody(PipelineView.class)
                .returnResult().getResponseBody();

        try {
            assertThat(created).isNotNull();
            assertThat(created.pipeline()).isEqualTo(pipeline);
            assertThat(created.layout()).isEqualTo(layout);

            PipelineView fetched = webTestClient.get().uri("/api/admin/marker-decision-pipelines/{id}", created.id())
                    .header(HttpHeaders.AUTHORIZATION, bearerTokenFor("root@example.com"))
                    .exchange()
                    .expectStatus().isOk()
                    .expectBody(PipelineView.class)
                    .returnResult().getResponseBody();

            assertThat(fetched).isNotNull();
            assertThat(fetched.pipeline()).isEqualTo(pipeline);
            assertThat(fetched.layout()).isEqualTo(layout);
        } finally {
            webTestClient.delete().uri("/api/admin/marker-decision-pipelines/{id}", created.id())
                    .header(HttpHeaders.AUTHORIZATION, bearerTokenFor("root@example.com"))
                    .exchange()
                    .expectStatus().isNoContent();
        }
    }

    @Test
    void listIncludesACreatedPipeline() {
        Pipeline pipeline = simplePipeline(UUID.randomUUID());
        PipelineView created = webTestClient.post().uri("/api/admin/marker-decision-pipelines")
                .header(HttpHeaders.AUTHORIZATION, bearerTokenFor("root@example.com"))
                .bodyValue(new CreatePipelineRequest(fixture.productTypeId(), pipeline, null))
                .exchange()
                .expectStatus().isOk()
                .expectBody(PipelineView.class)
                .returnResult().getResponseBody();

        try {
            List<PipelineView> all = webTestClient.get().uri("/api/admin/marker-decision-pipelines")
                    .header(HttpHeaders.AUTHORIZATION, bearerTokenFor("root@example.com"))
                    .exchange()
                    .expectStatus().isOk()
                    .expectBodyList(PipelineView.class)
                    .returnResult().getResponseBody();

            assertThat(all).extracting(PipelineView::id).contains(created.id());
        } finally {
            webTestClient.delete().uri("/api/admin/marker-decision-pipelines/{id}", created.id())
                    .header(HttpHeaders.AUTHORIZATION, bearerTokenFor("root@example.com"))
                    .exchange()
                    .expectStatus().isNoContent();
        }
    }

    @Test
    void getByItemType_returnsThePipelineBoundToThatType() {
        Pipeline pipeline = simplePipeline(UUID.randomUUID());
        PipelineView created = webTestClient.post().uri("/api/admin/marker-decision-pipelines")
                .header(HttpHeaders.AUTHORIZATION, bearerTokenFor("root@example.com"))
                .bodyValue(new CreatePipelineRequest(fixture.productTypeId(), pipeline, null))
                .exchange()
                .expectStatus().isOk()
                .expectBody(PipelineView.class)
                .returnResult().getResponseBody();

        try {
            PipelineView fetched = webTestClient.get().uri("/api/admin/marker-decision-pipelines/by-item-type/{itemTypeId}", fixture.productTypeId())
                    .header(HttpHeaders.AUTHORIZATION, bearerTokenFor("root@example.com"))
                    .exchange()
                    .expectStatus().isOk()
                    .expectBody(PipelineView.class)
                    .returnResult().getResponseBody();

            assertThat(fetched).isNotNull();
            assertThat(fetched.id()).isEqualTo(created.id());

            webTestClient.get().uri("/api/admin/marker-decision-pipelines/by-item-type/{itemTypeId}", fixture.contributorTypeId())
                    .header(HttpHeaders.AUTHORIZATION, bearerTokenFor("root@example.com"))
                    .exchange()
                    .expectStatus().isNotFound();
        } finally {
            webTestClient.delete().uri("/api/admin/marker-decision-pipelines/{id}", created.id())
                    .header(HttpHeaders.AUTHORIZATION, bearerTokenFor("root@example.com"))
                    .exchange()
                    .expectStatus().isNoContent();
        }
    }

    // One pipeline per item type, enforced by a unique index the repository surfaces as
    // DuplicateKeyException -- the controller turns that into a 409, not a 500.
    @Test
    void creatingASecondPipelineForTheSameItemType_isConflict() {
        PipelineView created = webTestClient.post().uri("/api/admin/marker-decision-pipelines")
                .header(HttpHeaders.AUTHORIZATION, bearerTokenFor("root@example.com"))
                .bodyValue(new CreatePipelineRequest(fixture.productTypeId(), simplePipeline(UUID.randomUUID()), null))
                .exchange()
                .expectStatus().isOk()
                .expectBody(PipelineView.class)
                .returnResult().getResponseBody();

        try {
            webTestClient.post().uri("/api/admin/marker-decision-pipelines")
                    .header(HttpHeaders.AUTHORIZATION, bearerTokenFor("root@example.com"))
                    .bodyValue(new CreatePipelineRequest(fixture.productTypeId(), simplePipeline(UUID.randomUUID()), null))
                    .exchange()
                    .expectStatus().isEqualTo(409);
        } finally {
            webTestClient.delete().uri("/api/admin/marker-decision-pipelines/{id}", created.id())
                    .header(HttpHeaders.AUTHORIZATION, bearerTokenFor("root@example.com"))
                    .exchange()
                    .expectStatus().isNoContent();
        }
    }

    @Test
    void update_changesEnabledFlagAndLayout() {
        Pipeline pipeline = simplePipeline(UUID.randomUUID());
        PipelineView created = webTestClient.post().uri("/api/admin/marker-decision-pipelines")
                .header(HttpHeaders.AUTHORIZATION, bearerTokenFor("root@example.com"))
                .bodyValue(new CreatePipelineRequest(fixture.productTypeId(), pipeline, null))
                .exchange()
                .expectStatus().isOk()
                .expectBody(PipelineView.class)
                .returnResult().getResponseBody();

        try {
            PipelineLayout newLayout = new PipelineLayout(Map.of(pipeline.start().id().toString(), new Point(5, 5)), Map.of());
            PipelineView updated = webTestClient.put().uri("/api/admin/marker-decision-pipelines/{id}", created.id())
                    .header(HttpHeaders.AUTHORIZATION, bearerTokenFor("root@example.com"))
                    .bodyValue(new UpdatePipelineRequest(pipeline, false, newLayout))
                    .exchange()
                    .expectStatus().isOk()
                    .expectBody(PipelineView.class)
                    .returnResult().getResponseBody();

            assertThat(updated).isNotNull();
            assertThat(updated.enabled()).isFalse();
            assertThat(updated.layout()).isEqualTo(newLayout);
        } finally {
            webTestClient.delete().uri("/api/admin/marker-decision-pipelines/{id}", created.id())
                    .header(HttpHeaders.AUTHORIZATION, bearerTokenFor("root@example.com"))
                    .exchange()
                    .expectStatus().isNoContent();
        }
    }

    // As the editor saves a block it's added: by a refId, everywhere it's mentioned -- the block, the
    // routes into and out of it, and the layout's keys.
    @Test
    void update_givesANewBlockAnId_everywhereItsRefIdIsUsed() {
        Pipeline pipeline = simplePipeline(UUID.randomUUID());
        PipelineView created = webTestClient.post().uri("/api/admin/marker-decision-pipelines")
                .header(HttpHeaders.AUTHORIZATION, bearerTokenFor("root@example.com"))
                .bodyValue(new CreatePipelineRequest(fixture.productTypeId(), pipeline, null))
                .exchange()
                .expectStatus().isOk()
                .expectBody(PipelineView.class)
                .returnResult().getResponseBody();
        UUID add = pipeline.carrierBlocks().get(0).id();
        UUID end = pipeline.end().id();

        try {
            ObjectNode json = (ObjectNode) objectMapper.valueToTree(pipeline);
            ArrayNode blocks = (ArrayNode) json.get("carrierBlocks");
            blocks.addObject().put("type", "ADD_MARKERS").put("id", "new-block-1")
                    .putPOJO("markerIds", List.of(UUID.randomUUID()));
            json.putPOJO("routes", List.of(
                    Map.of("outputPinId", pipeline.start().id(), "inputPinIds", List.of(add)),
                    Map.of("outputPinId", add, "inputPinIds", List.of("new-block-1")),
                    Map.of("outputPinId", "new-block-1", "inputPinIds", List.of(end))));
            Map<String, Object> body = Map.of("pipeline", json, "enabled", true, "layout", Map.of(
                    "positions", Map.of("new-block-1", Map.of("x", 40, "y", 80)),
                    "edgeBends", Map.of("new-block-1->" + end, 12.0)));

            PipelineView updated = webTestClient.put().uri("/api/admin/marker-decision-pipelines/{id}", created.id())
                    .header(HttpHeaders.AUTHORIZATION, bearerTokenFor("root@example.com"))
                    .bodyValue(body)
                    .exchange()
                    .expectStatus().isOk()
                    .expectBody(PipelineView.class)
                    .returnResult().getResponseBody();

            UUID newBlock = updated.pipeline().carrierBlocks().get(1).id();
            assertThat(updated.pipeline().routes()).containsExactlyInAnyOrder(
                    new Route(pipeline.start().id(), List.of(add)),
                    new Route(add, List.of(newBlock)),
                    new Route(newBlock, List.of(end)));
            assertThat(updated.layout().positions()).containsOnlyKeys(newBlock.toString());
            assertThat(updated.layout().edgeBends()).containsOnlyKeys(newBlock + "->" + end);
        } finally {
            webTestClient.delete().uri("/api/admin/marker-decision-pipelines/{id}", created.id())
                    .header(HttpHeaders.AUTHORIZATION, bearerTokenFor("root@example.com"))
                    .exchange()
                    .expectStatus().isNoContent();
        }
    }

    @Test
    void deletingAPipeline_removesIt() {
        Pipeline pipeline = simplePipeline(UUID.randomUUID());
        PipelineView created = webTestClient.post().uri("/api/admin/marker-decision-pipelines")
                .header(HttpHeaders.AUTHORIZATION, bearerTokenFor("root@example.com"))
                .bodyValue(new CreatePipelineRequest(fixture.productTypeId(), pipeline, null))
                .exchange()
                .expectStatus().isOk()
                .expectBody(PipelineView.class)
                .returnResult().getResponseBody();

        webTestClient.delete().uri("/api/admin/marker-decision-pipelines/{id}", created.id())
                .header(HttpHeaders.AUTHORIZATION, bearerTokenFor("root@example.com"))
                .exchange()
                .expectStatus().isNoContent();

        webTestClient.get().uri("/api/admin/marker-decision-pipelines/{id}", created.id())
                .header(HttpHeaders.AUTHORIZATION, bearerTokenFor("root@example.com"))
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    void updatingAPipelineSomeoneElseDeleted_isNotFound() {
        Pipeline pipeline = simplePipeline(UUID.randomUUID());
        PipelineView created = webTestClient.post().uri("/api/admin/marker-decision-pipelines")
                .header(HttpHeaders.AUTHORIZATION, bearerTokenFor("root@example.com"))
                .bodyValue(new CreatePipelineRequest(fixture.productTypeId(), pipeline, null))
                .exchange()
                .expectStatus().isOk()
                .expectBody(PipelineView.class)
                .returnResult().getResponseBody();
        webTestClient.delete().uri("/api/admin/marker-decision-pipelines/{id}", created.id())
                .header(HttpHeaders.AUTHORIZATION, bearerTokenFor("root@example.com"))
                .exchange()
                .expectStatus().isNoContent();

        webTestClient.put().uri("/api/admin/marker-decision-pipelines/{id}", created.id())
                .header(HttpHeaders.AUTHORIZATION, bearerTokenFor("root@example.com"))
                .bodyValue(new UpdatePipelineRequest(pipeline, true, new PipelineLayout(Map.of(), Map.of())))
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    void creatingWithoutRequiredFields_isBadRequest() {
        webTestClient.post().uri("/api/admin/marker-decision-pipelines")
                .header(HttpHeaders.AUTHORIZATION, bearerTokenFor("root@example.com"))
                .bodyValue(new CreatePipelineRequest(null, simplePipeline(UUID.randomUUID()), null))
                .exchange()
                .expectStatus().isBadRequest();
    }

    @Test
    void creatingWithNoLayoutAtAll_defaultsToEmptyPositions() {
        Pipeline pipeline = simplePipeline(UUID.randomUUID());

        PipelineView created = webTestClient.post().uri("/api/admin/marker-decision-pipelines")
                .header(HttpHeaders.AUTHORIZATION, bearerTokenFor("root@example.com"))
                .bodyValue(new CreatePipelineRequest(fixture.productTypeId(), pipeline, null))
                .exchange()
                .expectStatus().isOk()
                .expectBody(PipelineView.class)
                .returnResult().getResponseBody();

        try {
            assertThat(created).isNotNull();
            assertThat(created.layout()).isEqualTo(new PipelineLayout(Map.of(), Map.of()));
        } finally {
            webTestClient.delete().uri("/api/admin/marker-decision-pipelines/{id}", created.id())
                    .header(HttpHeaders.AUTHORIZATION, bearerTokenFor("root@example.com"))
                    .exchange()
                    .expectStatus().isNoContent();
        }
    }
}
