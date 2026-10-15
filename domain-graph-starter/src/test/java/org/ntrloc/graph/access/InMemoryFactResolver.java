package org.ntrloc.graph.access;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

// A simple in-memory FactResolver fake -- a small graph of items, each with its own properties and
// current states, connected by named link perspectives. Pure, no database, used by every test in
// this package that needs multi-instance traversal (ContextResolverTest, SignalEvaluatorTest,
// PipelineEvaluatorTest) without paying for a real register. Production traversal is
// RegisterPartitionManager's own job; this exists purely to keep this package's own tests as fast
// and dependency-free as the rest of it.
class InMemoryFactResolver implements FactResolver {

    private final Map<UUID, Map<UUID, List<UUID>>> linksByItemAndPerspective = new HashMap<>();
    private final Map<UUID, Map<UUID, Object>> propertiesByItem = new HashMap<>();
    private final Map<UUID, Map<UUID, UUID>> statesByItem = new HashMap<>();

    InMemoryFactResolver link(UUID fromItemId, UUID linkPerspectiveId, UUID toItemId) {
        linksByItemAndPerspective
                .computeIfAbsent(fromItemId, k -> new HashMap<>())
                .computeIfAbsent(linkPerspectiveId, k -> new ArrayList<>())
                .add(toItemId);
        return this;
    }

    InMemoryFactResolver properties(UUID itemId, Map<UUID, Object> properties) {
        propertiesByItem.put(itemId, properties);
        return this;
    }

    InMemoryFactResolver state(UUID itemId, UUID stateMachineId, UUID stateId) {
        statesByItem.computeIfAbsent(itemId, k -> new HashMap<>()).put(stateMachineId, stateId);
        return this;
    }

    @Override
    public List<UUID> linkedItemIds(UUID itemId, UUID linkPerspectiveId) {
        return linksByItemAndPerspective.getOrDefault(itemId, Map.of()).getOrDefault(linkPerspectiveId, List.of());
    }

    @Override
    public Map<UUID, Object> propertiesOfItem(UUID itemId) {
        return propertiesByItem.getOrDefault(itemId, Map.of());
    }

    @Override
    public UUID currentStateId(UUID itemId, UUID stateMachineId) {
        return statesByItem.getOrDefault(itemId, Map.of()).get(stateMachineId);
    }
}
