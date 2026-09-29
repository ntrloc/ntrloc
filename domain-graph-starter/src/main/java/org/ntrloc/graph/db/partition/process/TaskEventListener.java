package org.ntrloc.graph.db.partition.process;

import org.flowable.common.engine.api.delegate.event.AbstractFlowableEventListener;
import org.flowable.common.engine.api.delegate.event.FlowableEngineEventType;
import org.flowable.common.engine.api.delegate.event.FlowableEvent;
import org.flowable.common.engine.api.delegate.event.FlowableEventType;
import org.ntrloc.graph.events.ClusterEventBroadcaster;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.EnumSet;

// Registered on SpringProcessEngineConfiguration (see ProcessEngineConfig) for these three event
// types. getTypes scopes the listener; onEvent double-checks the type as a backstop.
@Component
public class TaskEventListener extends AbstractFlowableEventListener {

    private static final Collection<FlowableEngineEventType> TYPES = EnumSet.of(
            FlowableEngineEventType.TASK_CREATED,
            FlowableEngineEventType.TASK_ASSIGNED,
            FlowableEngineEventType.TASK_COMPLETED);

    private final TaskEventBroadcaster broadcaster;
    // New, parallel signal -- see ClusterEventBroadcaster's own comment. Published alongside
    // broadcaster above, not instead of it, while the new SSE relay is proven out (mirrors
    // SchemaManager.refreshCache()'s same additive change for schemaChanged).
    private final ClusterEventBroadcaster clusterEventBroadcaster;

    public TaskEventListener(TaskEventBroadcaster broadcaster, ClusterEventBroadcaster clusterEventBroadcaster) {
        this.broadcaster = broadcaster;
        this.clusterEventBroadcaster = clusterEventBroadcaster;
    }

    @Override
    public Collection<? extends FlowableEventType> getTypes() {
        return TYPES;
    }

    @Override
    public void onEvent(FlowableEvent event) {
        if (TYPES.contains(event.getType())) {
            broadcaster.publish(event.getType().toString());
            clusterEventBroadcaster.publish("taskChanged", event.getType().toString());
        }
    }

    // A broadcast hiccup (e.g. no subscribers, buffer pressure) must never fail the actual task
    // operation that triggered it -- the whole point of a signal that clients merely react to by
    // re-fetching is that it's allowed to be best-effort.
    @Override
    public boolean isFailOnException() {
        return false;
    }
}
