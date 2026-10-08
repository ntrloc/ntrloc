package org.ntrloc.graph.access;

import java.util.Map;
import java.util.UUID;

// Resolves a Context chain to the value a Predicate should test, against the item currently being
// evaluated. Only "one of this item's own scalar properties" (a StepToProperty terminating in
// Self, no `each`) is implemented so far -- link traversal, nested object properties, state
// machine lookups, and each-iteration all need real register reads and are built scenario by
// scenario as the evaluator grows; see the marker decision pipeline design notes.
public final class ContextResolver {

    private ContextResolver() {
    }

    public static Object resolve(Context context, Map<UUID, Object> selfProperties) {
        if (context instanceof StepToProperty step && !step.each() && step.next() instanceof Self) {
            return selfProperties.get(step.propertyId());
        }
        throw new UnsupportedOperationException("ContextResolver does not yet support " + context);
    }
}
