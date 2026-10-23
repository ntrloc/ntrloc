package org.ntrloc.graph.db.coordinator;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.ntrloc.graph.AbstractIntegrationTest;
import org.ntrloc.graph.access.AddMarkers;
import org.ntrloc.graph.access.ComparisonOperator;
import org.ntrloc.graph.access.Condition;
import org.ntrloc.graph.access.CountedPredicate;
import org.ntrloc.graph.access.End;
import org.ntrloc.graph.access.Gate;
import org.ntrloc.graph.access.IsTruePredicate;
import org.ntrloc.graph.access.NumberPredicate;
import org.ntrloc.graph.access.Pipeline;
import org.ntrloc.graph.access.PipelineLayout;
import org.ntrloc.graph.access.Predicate;
import org.ntrloc.graph.access.Route;
import org.ntrloc.graph.access.Self;
import org.ntrloc.graph.access.StateEqualsPredicate;
import org.ntrloc.graph.access.StepToItem;
import org.ntrloc.graph.access.StepToProperty;
import org.ntrloc.graph.access.StepToStateMachine;
import org.ntrloc.graph.access.Start;
import org.ntrloc.graph.db.partition.authorization.repository.AuthorizationRepository;
import org.ntrloc.graph.db.partition.authorization.repository.MarkerDecisionPipelineRepository;
import org.ntrloc.graph.db.partition.ledger.ItemUpdateEntry;
import org.ntrloc.graph.mutation.ExistingItemReference;
import org.ntrloc.graph.mutation.ItemCreateMutation;
import org.ntrloc.graph.mutation.ItemUpdateMutation;
import org.ntrloc.graph.mutation.LinkCreateMutation;
import org.ntrloc.graph.mutation.LinkEndpointReference;
import org.ntrloc.graph.mutation.MutationRequest;
import org.ntrloc.graph.mutation.MutationResponse;
import org.ntrloc.graph.db.partition.security.repository.SecurityRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

// Proves the exact live bug this round of work fixed, end to end through the real coordinator and a
// real register (not a fake FactResolver): a CountedPredicate whose subject steps through two levels
// of to-many links (self -> photos (each) -> portfolios (each) -> state machine) used to throw
// UnsupportedOperationException the moment PipelineMarkerDecisionService ever evaluated it --
// SignalEvaluatorTest already covers the pure evaluation logic with an in-memory FactResolver; this
// covers RegisterPartitionManager's own FactResolver implementation (linkedItemIds/currentStateId)
// against real register_item_link_perspective/register_link rows.
class CountedPredicateMarkerDecisionIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private LedgerRegisterCoordinator coordinator;

    @Autowired
    private MarkerDecisionPipelineRepository pipelineRepository;

    @Autowired
    private AuthorizationRepository authRepo;

    @Autowired
    private CountedPredicateTestDomainInitializer fixture;

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private SecurityRepository securityRepo;

    // ledger_entry.created_by/register_item.created_by (Part E) are NOT NULL FKs to
    // security_user.id -- setState's direct coordinator.prepare call needs a real, backed actor.
    private SecurityRepository.UserRow actor;

    @BeforeEach
    void setUp() {
        // Same "root" as MutationControllerIntegrationTest -- see that class's own comment.
        webTestClient = webTestClient.mutate().defaultHeader(HttpHeaders.AUTHORIZATION, bearerTokenFor("root")).build();
        fixture.removeAutoCreatedPipeline();
        actor = securityRepo.createUser("cpmd-actor-" + UUID.randomUUID(), "Counted Predicate Test Actor", null, true);
    }

    // self -> photos (each) -> portfolios (each) -> state machine, subPredicate STATE_EQUALS with no
    // subject of its own (inherits each (photo,portfolio) pair's own resolved state directly),
    // countPredicate "count > 0" -- the exact shape the live Photographer pipeline was authored with.
    // The AddMarkers block is gated on this condition alone; a second, unrelated condition
    // ("active") exists purely so the pipeline is ever fetched at all -- see WatchDerivation's own
    // comment: a change to a link perspective id (what the counted condition's own subject
    // references) is never itself a "changed fact" on the item it's declared against, since a link
    // perspective id can never appear in an ItemUpdateEntry's own properties/stateChanges keys. The
    // real pipeline this reproduces was, identically, only ever re-evaluated because it *also*
    // watched an ordinary property (genres) on the same item.
    private Pipeline publishedPortfolioPipeline(UUID markerId) {
        Condition trigger = new Condition(UUID.randomUUID(),
                new StepToProperty(fixture.activePropertyId(), false, new Self()), new IsTruePredicate(null));

        Predicate counted = new CountedPredicate(
                new StepToItem(fixture.photosPerspectiveId(), true,
                        new StepToItem(fixture.portfoliosPerspectiveId(), true,
                                new StepToStateMachine(fixture.publicationMachineId()))),
                new StateEqualsPredicate(null, fixture.publishedStateId()),
                new NumberPredicate(null, ComparisonOperator.GREATER_THAN, 0, null));
        Condition countedCondition = new Condition(UUID.randomUUID(), null, counted);

        AddMarkers add = new AddMarkers(UUID.randomUUID(), Gate.of(countedCondition.id()), List.of(markerId));
        Start start = new Start(UUID.randomUUID());
        End end = new End(UUID.randomUUID());
        return new Pipeline(UUID.randomUUID(), List.of(trigger, countedCondition), List.of(add), start, end, List.of(
                new Route(start.id(), List.of(add.id())),
                new Route(add.id(), List.of(end.id()))));
    }

    private UUID createItem(String itemTypeName, Map<String, Object> properties) {
        MutationResponse response = webTestClient.post().uri("/api/mutation")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new MutationRequest(List.of(new ItemCreateMutation(null, itemTypeName, properties)), List.of()))
                .exchange()
                .expectStatus().isOk()
                .expectBody(MutationResponse.class)
                .returnResult().getResponseBody();
        return response.items().get(0).itemId();
    }

    private void link(String perspectiveFromFirst, UUID firstItemId, String perspectiveFromSecond, UUID secondItemId) {
        webTestClient.post().uri("/api/mutation")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new MutationRequest(List.of(), List.of(new LinkCreateMutation(
                        new LinkEndpointReference(perspectiveFromFirst, new ExistingItemReference(firstItemId)),
                        new LinkEndpointReference(perspectiveFromSecond, new ExistingItemReference(secondItemId)),
                        Map.of()))))
                .exchange()
                .expectStatus().isOk();
    }

    private void updateActive(UUID photographerId, boolean active) {
        webTestClient.post().uri("/api/mutation")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new MutationRequest(List.of(new ItemUpdateMutation(photographerId, Map.of("active", active))), List.of()))
                .exchange()
                .expectStatus().isOk();
    }

    // Sets a Portfolio's current state directly, bypassing transition execution -- the same
    // low-level mechanism RegisterPartitionManagerProjectionIntegrationTest's own setState helper
    // uses; this test only needs a recorded current state to read back via the CountedPredicate, not
    // real START/transition wiring.
    private void setState(UUID itemId, UUID stateMachineId, UUID stateId) {
        UUID txn = UUID.randomUUID();
        coordinator.prepare(List.of(new ItemUpdateEntry(itemId, Map.of(), Map.of(stateMachineId, stateId), Set.of(), Set.of(), Set.of())), txn, actor.id(), OffsetDateTime.now());
        coordinator.commit(txn, UUID.randomUUID());
    }

    @Test
    void countedPredicate_overTwoLevelsOfLinkedItems_reflectsTheLinkedPortfoliosCurrentState() {
        UUID markerId = authRepo.createMarker("cp-published-" + UUID.randomUUID(), "d", "ITEM_TYPE", fixture.photographerTypeId()).id();
        UUID pipelineId = pipelineRepository.createPipeline(fixture.photographerTypeId(),
                publishedPortfolioPipeline(markerId), new PipelineLayout(Map.of(), Map.of())).id();

        try {
            UUID photographerId = createItem("CountedPredicateTestPhotographer", Map.of());
            UUID photoId = createItem("CountedPredicateTestPhoto", Map.of());
            link("photos", photographerId, "photographer", photoId);
            UUID portfolioId = createItem("CountedPredicateTestPortfolio", Map.of());
            link("portfolios", photoId, "photo", portfolioId);
            setState(portfolioId, fixture.publicationMachineId(), fixture.draftStateId());

            // First real trigger on the Photographer itself -- before this round of work, this
            // single mutation would have thrown UnsupportedOperationException and failed outright
            // the moment the counted condition was evaluated, regardless of the portfolio's state.
            updateActive(photographerId, true);
            assertThat(authRepo.getMarkerIdsForItem(photographerId)).doesNotContain(markerId);

            // Publishing the portfolio is an update to a *different* item's own type -- the
            // Photographer's pipeline is never re-evaluated by this alone.
            setState(portfolioId, fixture.publicationMachineId(), fixture.publishedStateId());
            assertThat(authRepo.getMarkerIdsForItem(photographerId)).doesNotContain(markerId);

            // Re-triggering the Photographer's own pipeline now correctly sees the portfolio's
            // *current* published state, reached by walking real register_item_link_perspective rows
            // two levels deep, and applies the marker.
            updateActive(photographerId, false);
            assertThat(authRepo.getMarkerIdsForItem(photographerId)).contains(markerId);
        } finally {
            pipelineRepository.deletePipeline(pipelineId);
        }
    }
}
