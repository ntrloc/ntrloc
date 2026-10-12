package org.ntrloc.graph.db.partition.authorization;

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
        PipelineLayout layout = new PipelineLayout(Map.of(pipeline.start().id(), new Point(10, 20), pipeline.end().id(), new Point(300, 20)));

        PipelineView created = webTestClient.post().uri("/api/admin/marker-decision-pipelines")
                .header("X-Ntrloc-User", "root")
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
                    .header("X-Ntrloc-User", "root")
                    .exchange()
                    .expectStatus().isOk()
                    .expectBody(PipelineView.class)
                    .returnResult().getResponseBody();

            assertThat(fetched).isNotNull();
            assertThat(fetched.pipeline()).isEqualTo(pipeline);
            assertThat(fetched.layout()).isEqualTo(layout);
        } finally {
            webTestClient.delete().uri("/api/admin/marker-decision-pipelines/{id}", created.id())
                    .header("X-Ntrloc-User", "root")
                    .exchange()
                    .expectStatus().isNoContent();
        }
    }

    @Test
    void listIncludesACreatedPipeline() {
        Pipeline pipeline = simplePipeline(UUID.randomUUID());
        PipelineView created = webTestClient.post().uri("/api/admin/marker-decision-pipelines")
                .header("X-Ntrloc-User", "root")
                .bodyValue(new CreatePipelineRequest(fixture.productTypeId(), pipeline, null))
                .exchange()
                .expectStatus().isOk()
                .expectBody(PipelineView.class)
                .returnResult().getResponseBody();

        try {
            List<PipelineView> all = webTestClient.get().uri("/api/admin/marker-decision-pipelines")
                    .header("X-Ntrloc-User", "root")
                    .exchange()
                    .expectStatus().isOk()
                    .expectBodyList(PipelineView.class)
                    .returnResult().getResponseBody();

            assertThat(all).extracting(PipelineView::id).contains(created.id());
        } finally {
            webTestClient.delete().uri("/api/admin/marker-decision-pipelines/{id}", created.id())
                    .header("X-Ntrloc-User", "root")
                    .exchange()
                    .expectStatus().isNoContent();
        }
    }

    @Test
    void getByItemType_returnsThePipelineBoundToThatType() {
        Pipeline pipeline = simplePipeline(UUID.randomUUID());
        PipelineView created = webTestClient.post().uri("/api/admin/marker-decision-pipelines")
                .header("X-Ntrloc-User", "root")
                .bodyValue(new CreatePipelineRequest(fixture.productTypeId(), pipeline, null))
                .exchange()
                .expectStatus().isOk()
                .expectBody(PipelineView.class)
                .returnResult().getResponseBody();

        try {
            PipelineView fetched = webTestClient.get().uri("/api/admin/marker-decision-pipelines/by-item-type/{itemTypeId}", fixture.productTypeId())
                    .header("X-Ntrloc-User", "root")
                    .exchange()
                    .expectStatus().isOk()
                    .expectBody(PipelineView.class)
                    .returnResult().getResponseBody();

            assertThat(fetched).isNotNull();
            assertThat(fetched.id()).isEqualTo(created.id());

            webTestClient.get().uri("/api/admin/marker-decision-pipelines/by-item-type/{itemTypeId}", fixture.contributorTypeId())
                    .header("X-Ntrloc-User", "root")
                    .exchange()
                    .expectStatus().isNotFound();
        } finally {
            webTestClient.delete().uri("/api/admin/marker-decision-pipelines/{id}", created.id())
                    .header("X-Ntrloc-User", "root")
                    .exchange()
                    .expectStatus().isNoContent();
        }
    }

    // One pipeline per item type, enforced by a unique index the repository surfaces as
    // DuplicateKeyException -- the controller turns that into a 409, not a 500.
    @Test
    void creatingASecondPipelineForTheSameItemType_isConflict() {
        PipelineView created = webTestClient.post().uri("/api/admin/marker-decision-pipelines")
                .header("X-Ntrloc-User", "root")
                .bodyValue(new CreatePipelineRequest(fixture.productTypeId(), simplePipeline(UUID.randomUUID()), null))
                .exchange()
                .expectStatus().isOk()
                .expectBody(PipelineView.class)
                .returnResult().getResponseBody();

        try {
            webTestClient.post().uri("/api/admin/marker-decision-pipelines")
                    .header("X-Ntrloc-User", "root")
                    .bodyValue(new CreatePipelineRequest(fixture.productTypeId(), simplePipeline(UUID.randomUUID()), null))
                    .exchange()
                    .expectStatus().isEqualTo(409);
        } finally {
            webTestClient.delete().uri("/api/admin/marker-decision-pipelines/{id}", created.id())
                    .header("X-Ntrloc-User", "root")
                    .exchange()
                    .expectStatus().isNoContent();
        }
    }

    @Test
    void update_changesEnabledFlagAndLayout() {
        Pipeline pipeline = simplePipeline(UUID.randomUUID());
        PipelineView created = webTestClient.post().uri("/api/admin/marker-decision-pipelines")
                .header("X-Ntrloc-User", "root")
                .bodyValue(new CreatePipelineRequest(fixture.productTypeId(), pipeline, null))
                .exchange()
                .expectStatus().isOk()
                .expectBody(PipelineView.class)
                .returnResult().getResponseBody();

        try {
            PipelineLayout newLayout = new PipelineLayout(Map.of(pipeline.start().id(), new Point(5, 5)));
            PipelineView updated = webTestClient.put().uri("/api/admin/marker-decision-pipelines/{id}", created.id())
                    .header("X-Ntrloc-User", "root")
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
                    .header("X-Ntrloc-User", "root")
                    .exchange()
                    .expectStatus().isNoContent();
        }
    }

    @Test
    void deletingAPipeline_removesIt() {
        Pipeline pipeline = simplePipeline(UUID.randomUUID());
        PipelineView created = webTestClient.post().uri("/api/admin/marker-decision-pipelines")
                .header("X-Ntrloc-User", "root")
                .bodyValue(new CreatePipelineRequest(fixture.productTypeId(), pipeline, null))
                .exchange()
                .expectStatus().isOk()
                .expectBody(PipelineView.class)
                .returnResult().getResponseBody();

        webTestClient.delete().uri("/api/admin/marker-decision-pipelines/{id}", created.id())
                .header("X-Ntrloc-User", "root")
                .exchange()
                .expectStatus().isNoContent();

        webTestClient.get().uri("/api/admin/marker-decision-pipelines/{id}", created.id())
                .header("X-Ntrloc-User", "root")
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    void creatingWithoutRequiredFields_isBadRequest() {
        webTestClient.post().uri("/api/admin/marker-decision-pipelines")
                .header("X-Ntrloc-User", "root")
                .bodyValue(new CreatePipelineRequest(null, simplePipeline(UUID.randomUUID()), null))
                .exchange()
                .expectStatus().isBadRequest();
    }

    @Test
    void creatingWithNoLayoutAtAll_defaultsToEmptyPositions() {
        Pipeline pipeline = simplePipeline(UUID.randomUUID());

        PipelineView created = webTestClient.post().uri("/api/admin/marker-decision-pipelines")
                .header("X-Ntrloc-User", "root")
                .bodyValue(new CreatePipelineRequest(fixture.productTypeId(), pipeline, null))
                .exchange()
                .expectStatus().isOk()
                .expectBody(PipelineView.class)
                .returnResult().getResponseBody();

        try {
            assertThat(created).isNotNull();
            assertThat(created.layout()).isEqualTo(new PipelineLayout(Map.of()));
        } finally {
            webTestClient.delete().uri("/api/admin/marker-decision-pipelines/{id}", created.id())
                    .header("X-Ntrloc-User", "root")
                    .exchange()
                    .expectStatus().isNoContent();
        }
    }
}
