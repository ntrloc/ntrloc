package org.ntrloc.graph.db.partition.authorization;

import org.ntrloc.graph.access.End;
import org.ntrloc.graph.access.Pipeline;
import org.ntrloc.graph.access.PipelineLayout;
import org.ntrloc.graph.access.Route;
import org.ntrloc.graph.access.Start;
import org.ntrloc.graph.db.partition.authorization.repository.MarkerDecisionPipelineRepository;
import org.ntrloc.graph.db.partition.schema.event.SchemaChangeEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

// Every item type gets a marker decision pipeline the moment it exists -- a no-op shape (Start routed
// straight to End, nothing else) rather than no pipeline at all, so "what decides this type's access
// markers" always has an answer an admin can open and build on, instead of a missing-pipeline special
// case every reader would otherwise need to handle. Mirrors DefaultUserGroupInitializer's own
// onItemTypeCreated -- same event, same synchronous @EventListener -- but a separate concern from it
// (pipeline existence, not type-visibility grants).
@Component
public class DefaultPipelineInitializer {

    private final MarkerDecisionPipelineRepository pipelineRepo;

    public DefaultPipelineInitializer(MarkerDecisionPipelineRepository pipelineRepo) {
        this.pipelineRepo = pipelineRepo;
    }

    @EventListener
    public void onItemTypeCreated(SchemaChangeEvent.ItemTypeCreated event) {
        Start start = new Start(UUID.randomUUID());
        End end = new End(UUID.randomUUID());
        Pipeline pipeline = new Pipeline(UUID.randomUUID(), List.of(), List.of(), start, end,
                List.of(new Route(start.id(), List.of(end.id()))));
        pipelineRepo.createPipeline(event.itemTypeId(), pipeline, new PipelineLayout(null, null));
    }
}
