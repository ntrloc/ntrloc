package org.ntrloc.graph.db.partition.authorization.repository;

import org.junit.jupiter.api.Test;
import org.ntrloc.graph.AbstractIntegrationTest;
import org.ntrloc.graph.access.AddMarkers;
import org.ntrloc.graph.access.Condition;
import org.ntrloc.graph.access.End;
import org.ntrloc.graph.access.Filter;
import org.ntrloc.graph.access.Gate;
import org.ntrloc.graph.access.IsTruePredicate;
import org.ntrloc.graph.access.Pipeline;
import org.ntrloc.graph.access.PipelineLayout;
import org.ntrloc.graph.access.Quantity;
import org.ntrloc.graph.access.RemoveMarkers;
import org.ntrloc.graph.access.Route;
import org.ntrloc.graph.access.Self;
import org.ntrloc.graph.access.Start;
import org.ntrloc.graph.access.StepToProperty;
import org.ntrloc.graph.access.StringEqualsPredicate;
import org.ntrloc.graph.access.TotalityPredicate;
import org.ntrloc.graph.db.coordinator.CoordinatorTestDomainInitializer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// Proves the JSONB round trip itself -- a pipeline exercising every structural shape the model
// offers (a TotalityPredicate over two value predicates, AddMarkers/RemoveMarkers/Filter, multi-step
// routing) has to come back bit-for-bit equal, not just "close enough", since prepare()'s evaluation
// depends on exact ids surviving the trip (a gate/signal UUID that silently changed would resolve to
// nothing and throw).
class MarkerDecisionPipelineRepositoryIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private MarkerDecisionPipelineRepository repository;

    @Autowired
    private CoordinatorTestDomainInitializer fixture;

    private Pipeline buildStructurallyRichPipeline() {
        var colorIsRed = new StringEqualsPredicate(new StepToProperty(fixture.colorPropertyId(), false, new Self()), "red");
        var nameIsSet = new IsTruePredicate(new StepToProperty(fixture.namePropertyId(), false, new Self()));
        Condition either = new Condition(UUID.randomUUID(), null, new TotalityPredicate(null, Quantity.ANY, List.of(colorIsRed, nameIsSet)));
        Condition colorGate = new Condition(UUID.randomUUID(), null, colorIsRed);

        UUID markerA = UUID.randomUUID();
        UUID markerB = UUID.randomUUID();
        AddMarkers add = new AddMarkers(UUID.randomUUID(), Gate.of(either.id()), List.of(markerA, markerB));
        RemoveMarkers remove = new RemoveMarkers(UUID.randomUUID(), null, List.of(markerB));
        Filter filter = new Filter(UUID.randomUUID(), Gate.of(colorGate.id()));
        Start start = new Start(UUID.randomUUID());
        End end = new End(UUID.randomUUID());

        return new Pipeline(UUID.randomUUID(), List.of(either, colorGate), List.of(add, remove, filter), start, end, List.of(
                new Route(start.id(), List.of(add.id())),
                new Route(add.id(), List.of(remove.id())),
                new Route(remove.id(), List.of(filter.id())),
                new Route(filter.id(), List.of(end.id()))));
    }

    @Test
    void createPipeline_roundTripsTheFullGraphStructureThroughJsonb() {
        Pipeline original = buildStructurallyRichPipeline();
        var created = repository.createPipeline(fixture.productTypeId(), original, new PipelineLayout(Map.of(), Map.of()));

        try {
            assertThat(created.pipeline()).isEqualTo(original);
            Pipeline reloaded = repository.findById(created.id()).orElseThrow().pipeline();
            assertThat(reloaded).isEqualTo(original);
        } finally {
            repository.deletePipeline(created.id());
        }
    }

    @Test
    void pipelinesWatching_matchesOnAnyWatchedFactForTheRightItemType() {
        // buildStructurallyRichPipeline watches both colorPropertyId (colorIsRed) and
        // namePropertyId (nameIsSet) -- either one changing is independently a reason to run it.
        Pipeline pipeline = buildStructurallyRichPipeline();
        var created = repository.createPipeline(fixture.productTypeId(), pipeline, new PipelineLayout(Map.of(), Map.of()));

        try {
            assertThat(repository.pipelinesWatching(fixture.productTypeId(), Set.of(fixture.colorPropertyId())))
                    .contains(pipeline);
            assertThat(repository.pipelinesWatching(fixture.productTypeId(), Set.of(fixture.namePropertyId())))
                    .contains(pipeline);
        } finally {
            repository.deletePipeline(created.id());
        }
    }

    @Test
    void pipelinesWatching_excludesAPipelineThatDoesNotWatchAnyOfTheChangedFacts() {
        Pipeline pipeline = buildStructurallyRichPipeline();
        var created = repository.createPipeline(fixture.productTypeId(), pipeline, new PipelineLayout(Map.of(), Map.of()));

        try {
            assertThat(repository.pipelinesWatching(fixture.productTypeId(), Set.of(fixture.dimensionsWidthPropertyId())))
                    .doesNotContain(pipeline);
        } finally {
            repository.deletePipeline(created.id());
        }
    }

    @Test
    void pipelinesWatching_excludesAPipelineBoundToADifferentItemType() {
        Pipeline pipeline = buildStructurallyRichPipeline();
        var created = repository.createPipeline(fixture.productTypeId(), pipeline, new PipelineLayout(Map.of(), Map.of()));

        try {
            assertThat(repository.pipelinesWatching(fixture.contributorTypeId(), Set.of(fixture.colorPropertyId())))
                    .doesNotContain(pipeline);
        } finally {
            repository.deletePipeline(created.id());
        }
    }

    @Test
    void pipelinesWatching_excludesADisabledPipeline() {
        Pipeline pipeline = buildStructurallyRichPipeline();
        var created = repository.createPipeline(fixture.productTypeId(), pipeline, new PipelineLayout(Map.of(), Map.of()));

        try {
            repository.updatePipeline(created.id(), pipeline, false, new PipelineLayout(Map.of(), Map.of()));
            assertThat(repository.pipelinesWatching(fixture.productTypeId(), Set.of(fixture.colorPropertyId())))
                    .doesNotContain(pipeline);
        } finally {
            repository.deletePipeline(created.id());
        }
    }

    @Test
    void updatePipeline_replacesTheWatchSetToMatchTheNewGraph() {
        // Starts watching only colorPropertyId; updated to a pipeline watching only
        // namePropertyId instead -- the old watch row must be gone, not just the new one added.
        UUID markerId = UUID.randomUUID();
        Condition colorGate = new Condition(UUID.randomUUID(), new StepToProperty(fixture.colorPropertyId(), false, new Self()),
                new StringEqualsPredicate(null, "red"));
        AddMarkers colorAdd = new AddMarkers(UUID.randomUUID(), Gate.of(colorGate.id()), List.of(markerId));
        Start start = new Start(UUID.randomUUID());
        End end = new End(UUID.randomUUID());
        Pipeline colorPipeline = new Pipeline(UUID.randomUUID(), List.of(colorGate), List.of(colorAdd), start, end, List.of(
                new Route(start.id(), List.of(colorAdd.id())),
                new Route(colorAdd.id(), List.of(end.id()))));

        var created = repository.createPipeline(fixture.productTypeId(), colorPipeline, new PipelineLayout(Map.of(), Map.of()));
        try {
            assertThat(repository.pipelinesWatching(fixture.productTypeId(), Set.of(fixture.colorPropertyId())))
                    .contains(colorPipeline);

            Condition nameGate = new Condition(UUID.randomUUID(), new StepToProperty(fixture.namePropertyId(), false, new Self()),
                    new StringEqualsPredicate(null, "Widget"));
            AddMarkers nameAdd = new AddMarkers(UUID.randomUUID(), Gate.of(nameGate.id()), List.of(markerId));
            Pipeline namePipeline = new Pipeline(colorPipeline.id(), List.of(nameGate), List.of(nameAdd), start, end, List.of(
                    new Route(start.id(), List.of(nameAdd.id())),
                    new Route(nameAdd.id(), List.of(end.id()))));
            repository.updatePipeline(created.id(), namePipeline, true, new PipelineLayout(Map.of(), Map.of()));

            assertThat(repository.pipelinesWatching(fixture.productTypeId(), Set.of(fixture.colorPropertyId())))
                    .isEmpty();
            assertThat(repository.pipelinesWatching(fixture.productTypeId(), Set.of(fixture.namePropertyId())))
                    .contains(namePipeline);
        } finally {
            repository.deletePipeline(created.id());
        }
    }

    @Test
    void getAllPipelines_includesDisabledOnes() {
        Pipeline pipeline = buildStructurallyRichPipeline();
        var created = repository.createPipeline(fixture.productTypeId(), pipeline, new PipelineLayout(Map.of(), Map.of()));

        try {
            repository.updatePipeline(created.id(), pipeline, false, new PipelineLayout(Map.of(), Map.of()));

            assertThat(repository.getAllPipelines()).extracting(MarkerDecisionPipelineRepository.PipelineRow::id)
                    .contains(created.id());
        } finally {
            repository.deletePipeline(created.id());
        }
    }

    @Test
    void deletePipeline_removesIt() {
        Pipeline pipeline = buildStructurallyRichPipeline();
        var created = repository.createPipeline(fixture.productTypeId(), pipeline, new PipelineLayout(Map.of(), Map.of()));

        repository.deletePipeline(created.id());

        assertThat(repository.findById(created.id())).isEmpty();
    }

    @Test
    void findByItemTypeId_returnsThePipelineBoundToThatType() {
        Pipeline pipeline = buildStructurallyRichPipeline();
        var created = repository.createPipeline(fixture.productTypeId(), pipeline, new PipelineLayout(Map.of(), Map.of()));

        try {
            assertThat(repository.findByItemTypeId(fixture.productTypeId())).contains(created);
            assertThat(repository.findByItemTypeId(fixture.contributorTypeId())).isEmpty();
        } finally {
            repository.deletePipeline(created.id());
        }
    }

    // One pipeline per item type, enforced by a unique index (see the baseline migration), not just
    // a convention a caller could accidentally violate.
    @Test
    void createPipeline_rejectsASecondPipelineForAnItemTypeThatAlreadyHasOne() {
        Pipeline pipeline = buildStructurallyRichPipeline();
        var created = repository.createPipeline(fixture.productTypeId(), pipeline, new PipelineLayout(Map.of(), Map.of()));

        try {
            assertThatThrownBy(() -> repository.createPipeline(fixture.productTypeId(), buildStructurallyRichPipeline(), new PipelineLayout(Map.of(), Map.of())))
                    .isInstanceOf(DuplicateKeyException.class);
        } finally {
            repository.deletePipeline(created.id());
        }
    }
}
