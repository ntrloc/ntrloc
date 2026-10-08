package org.ntrloc.graph.access;

import java.util.List;
import java.util.Set;
import java.util.UUID;

// Where marker decision pipelines bound to a given item type come from. Deliberately separate from
// Pipeline itself, which carries no item-type binding of its own -- the binding lives wherever a
// pipeline is stored, not in the pipeline graph.
//
// Scoped by watched facts, not just item type: a pipeline bound to an item type that doesn't watch
// anything in changedFactIds has no reason to run (see WatchDerivation) -- pushing that filter into
// the source itself (a single indexed lookup against each pipeline's precomputed watch set, for the
// one real implementation, MarkerDecisionPipelineRepository) means a mutation never pays to walk,
// deserialize, or evaluate a pipeline it was never going to fire.
public interface PipelineSource {

    List<Pipeline> pipelinesWatching(UUID itemTypeId, Set<UUID> changedFactIds);
}
