package org.ntrloc.graph.db.partition.process;

import org.flowable.common.engine.api.delegate.event.AbstractFlowableEventListener;
import org.flowable.common.engine.api.delegate.event.FlowableEngineEventType;
import org.flowable.common.engine.api.delegate.event.FlowableEvent;
import org.flowable.common.engine.api.delegate.event.FlowableEventType;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.EnumSet;

// Registered on SpringProcessEngineConfiguration (see ProcessEngineConfig) so the engine calls
// onEvent() for these three event types as they happen -- independent of this app's custom
// DataManager persistence layer, since Flowable's event dispatch operates on the in-memory command
// execution, not the DB. getTypes() is the documented way to scope a listener to specific types;
// onEvent() also double-checks the type itself so this is correct regardless of exactly how the
// engine's dispatch honors getTypes() for a given registration path.
@Component
public class TaskEventListener extends AbstractFlowableEventListener {

    private static final Collection<FlowableEngineEventType> TYPES = EnumSet.of(
            FlowableEngineEventType.TASK_CREATED,
            FlowableEngineEventType.TASK_ASSIGNED,
            FlowableEngineEventType.TASK_COMPLETED);

    private final TaskEventBroadcaster broadcaster;

    public TaskEventListener(TaskEventBroadcaster broadcaster) {
        this.broadcaster = broadcaster;
    }

    @Override
    public Collection<? extends FlowableEventType> getTypes() {
        return TYPES;
    }

    @Override
    public void onEvent(FlowableEvent event) {
        if (TYPES.contains(event.getType())) {
            broadcaster.publish(event.getType().toString());
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
