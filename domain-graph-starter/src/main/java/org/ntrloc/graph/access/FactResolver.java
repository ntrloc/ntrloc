package org.ntrloc.graph.access;

import java.util.List;
import java.util.Map;
import java.util.UUID;

// The seam between this package's pure, dependency-free predicate/condition model and real register
// data. ContextResolver.resolveInstances calls back into this for everything it can't answer from
// the single properties map a caller already has in hand: every other item reached by stepping off
// a link perspective, that item's own properties, and its current state in a given state machine.
// Implemented by a real, DB-backed adapter in production (RegisterPartitionManager itself) and by a
// simple in-memory fake in tests -- this package itself never talks to a database, same as every
// other class in here.
//
// Deliberately item-only for now, mirroring ContextResolver's own current scope: StepToLink (reading
// a link's own properties, rather than stepping past it to the item on the far end) isn't resolvable
// through this interface yet -- same "not yet supported" gap it always had, just not the one this
// round of work closes. See StepToItem's own comment: stepping directly from one item to another,
// "skipping the link," is the one traversal shape actually needed here.
public interface FactResolver {

    // Every item on the other end of linkPerspectiveId, as seen from itemId's own side -- 0, 1, or
    // many results regardless of the step's own `each` flag; it's ContextResolver, not this method,
    // that decides whether more than one result is allowed for a given step.
    List<UUID> linkedItemIds(UUID itemId, UUID linkPerspectiveId);

    // An item's own current properties, keyed by property id -- the same shape a pipeline's root
    // item's properties already arrive in, for any OTHER item reached by traversal.
    Map<UUID, Object> propertiesOfItem(UUID itemId);

    // An item's current state in the given state machine, or null if that machine has never been
    // started on this item.
    UUID currentStateId(UUID itemId, UUID stateMachineId);

    // What every legacy (pre-multi-instance-traversal) call site effectively had: no way to answer
    // any of the above. Used by SignalEvaluator/PipelineEvaluator's own legacy overloads so a caller
    // that never supplies a real FactResolver keeps throwing UnsupportedOperationException for
    // exactly the contexts that always threw, rather than silently returning nothing.
    static FactResolver unsupported() {
        return new FactResolver() {
            public List<UUID> linkedItemIds(UUID itemId, UUID linkPerspectiveId) {
                throw new UnsupportedOperationException("No FactResolver supplied for link traversal");
            }

            public Map<UUID, Object> propertiesOfItem(UUID itemId) {
                throw new UnsupportedOperationException("No FactResolver supplied for cross-item property lookup");
            }

            public UUID currentStateId(UUID itemId, UUID stateMachineId) {
                throw new UnsupportedOperationException("No FactResolver supplied for state machine lookup");
            }
        };
    }
}
