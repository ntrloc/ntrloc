package org.ntrloc.graph.db.partition.authorization.repository;

import org.junit.jupiter.api.Test;
import org.ntrloc.graph.AbstractIntegrationTest;
import org.ntrloc.graph.access.AddMarkers;
import org.ntrloc.graph.access.Combinator;
import org.ntrloc.graph.access.CompoundSignal;
import org.ntrloc.graph.access.End;
import org.ntrloc.graph.access.Filter;
import org.ntrloc.graph.access.IsTruePredicate;
import org.ntrloc.graph.access.Pipeline;
import org.ntrloc.graph.access.RemoveMarkers;
import org.ntrloc.graph.access.Route;
import org.ntrloc.graph.access.Self;
import org.ntrloc.graph.access.Signal;
import org.ntrloc.graph.access.Start;
import org.ntrloc.graph.access.StepToProperty;
import org.ntrloc.graph.access.StringEqualsPredicate;
import org.ntrloc.graph.db.coordinator.CoordinatorTestDomainInitializer;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

// Proves the JSONB round trip itself -- a pipeline exercising every structural shape the model
// offers (a CompoundSignal over two Signals, AddMarkers/RemoveMarkers/Filter, multi-step routing)
// has to come back bit-for-bit equal, not just "close enough", since prepare()'s evaluation depends
// on exact ids surviving the trip (a gate/signal UUID that silently changed would resolve to
// nothing and throw).
class MarkerDecisionPipelineRepositoryIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private MarkerDecisionPipelineRepository repository;

    @Autowired
    private CoordinatorTestDomainInitializer fixture;

    private Pipeline buildStructurallyRichPipeline() {
        Signal colorIsRed = new Signal(UUID.randomUUID(), new StepToProperty(fixture.colorPropertyId(), false, new Self()),
                "color", new StringEqualsPredicate("red"));
        Signal nameIsSet = new Signal(UUID.randomUUID(), new StepToProperty(fixture.namePropertyId(), false, new Self()),
                null, new IsTruePredicate());
        CompoundSignal either = new CompoundSignal(UUID.randomUUID(), Combinator.ANY, List.of(colorIsRed, nameIsSet));

        UUID markerA = UUID.randomUUID();
        UUID markerB = UUID.randomUUID();
        AddMarkers add = new AddMarkers(UUID.randomUUID(), either.id(), List.of(markerA, markerB));
        RemoveMarkers remove = new RemoveMarkers(UUID.randomUUID(), null, List.of(markerB));
        Filter filter = new Filter(UUID.randomUUID(), colorIsRed.id());
        Start start = new Start(UUID.randomUUID());
        End end = new End(UUID.randomUUID());

        return new Pipeline(UUID.randomUUID(), List.of(either), List.of(add, remove, filter), start, end, List.of(
                new Route(start.id(), List.of(add.id())),
                new Route(add.id(), List.of(remove.id())),
                new Route(remove.id(), List.of(filter.id())),
                new Route(filter.id(), List.of(end.id()))));
    }

    @Test
    void createPipeline_roundTripsTheFullGraphStructureThroughJsonb() {
        Pipeline original = buildStructurallyRichPipeline();
        var created = repository.createPipeline("round-trip-test", fixture.productTypeId(), original);

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
        var created = repository.createPipeline("watch-lookup-test", fixture.productTypeId(), pipeline);

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
        var created = repository.createPipeline("watch-exclude-test", fixture.productTypeId(), pipeline);

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
        var created = repository.createPipeline("watch-wrong-type-test", fixture.productTypeId(), pipeline);

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
        var created = repository.createPipeline("watch-disabled-test", fixture.productTypeId(), pipeline);

        try {
            repository.updatePipeline(created.id(), created.name(), pipeline, false);
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
        Signal colorGate = new Signal(UUID.randomUUID(), new StepToProperty(fixture.colorPropertyId(), false, new Self()),
                null, new StringEqualsPredicate("red"));
        AddMarkers colorAdd = new AddMarkers(UUID.randomUUID(), colorGate.id(), List.of(markerId));
        Start start = new Start(UUID.randomUUID());
        End end = new End(UUID.randomUUID());
        Pipeline colorPipeline = new Pipeline(UUID.randomUUID(), List.of(colorGate), List.of(colorAdd), start, end, List.of(
                new Route(start.id(), List.of(colorAdd.id())),
                new Route(colorAdd.id(), List.of(end.id()))));

        var created = repository.createPipeline("watch-update-test", fixture.productTypeId(), colorPipeline);
        try {
            assertThat(repository.pipelinesWatching(fixture.productTypeId(), Set.of(fixture.colorPropertyId())))
                    .contains(colorPipeline);

            Signal nameGate = new Signal(UUID.randomUUID(), new StepToProperty(fixture.namePropertyId(), false, new Self()),
                    null, new StringEqualsPredicate("Widget"));
            AddMarkers nameAdd = new AddMarkers(UUID.randomUUID(), nameGate.id(), List.of(markerId));
            Pipeline namePipeline = new Pipeline(colorPipeline.id(), List.of(nameGate), List.of(nameAdd), start, end, List.of(
                    new Route(start.id(), List.of(nameAdd.id())),
                    new Route(nameAdd.id(), List.of(end.id()))));
            repository.updatePipeline(created.id(), created.name(), namePipeline, true);

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
        var created = repository.createPipeline("list-all-test", fixture.productTypeId(), pipeline);

        try {
            repository.updatePipeline(created.id(), created.name(), pipeline, false);

            assertThat(repository.getAllPipelines()).extracting(MarkerDecisionPipelineRepository.PipelineRow::id)
                    .contains(created.id());
        } finally {
            repository.deletePipeline(created.id());
        }
    }

    @Test
    void deletePipeline_removesIt() {
        Pipeline pipeline = buildStructurallyRichPipeline();
        var created = repository.createPipeline("delete-test", fixture.productTypeId(), pipeline);

        repository.deletePipeline(created.id());

        assertThat(repository.findById(created.id())).isEmpty();
    }
}
