package org.ntrloc.graph.db.partition.process;

import org.ntrloc.graph.events.SseEmitterRegistry;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

// A trivial broadcast signal, not a data channel: every connected SSE client (TaskAdminController)
// gets every published event and just re-fetches GET /api/admin/process/tasks, which already does
// the correct per-principal (assignee/candidate) filtering (see TaskAdminController.listTasks).
// Replicating that filtering here, per-connection, would be a second copy of the same logic with
// real risk of drifting from the first -- not worth it for what's ultimately just a "something
// changed, go check" tap on the shoulder.
//
// DEPRECATED, pending removal: superseded by org.ntrloc.graph.events.ClusterEventBroadcaster, which
// fixes this class's real gap (local-JVM-only delivery -- a client connected to a different node
// in a multi-pod deployment never hears about a change made on this one). Left alone deliberately
// for now, unmodified in behavior (still converted off WebFlux below, same as everything else in
// this app), alongside its existing caller (TaskEventListener) and consumer
// (TaskAdminController.taskEvents) -- TaskEventListener now ALSO publishes to ClusterEventBroadcaster
// in parallel (see its own comment). Remove this class and its /tasks/events endpoint once the admin
// UI(s) have fully moved onto the new shared /api/events endpoint; see SchemaChangeBroadcaster for
// the same deprecation, same reasoning.
@Component
public class TaskEventBroadcaster {

    // SseEmitterRegistry's own direct-delivery, drop-on-failure semantics are what
    // Sinks.Many.directBestEffort() used to provide here: a live tap, not a history replay --
    // clients always do their own fetch on connect/mount, so there's nothing to gain from
    // buffering events for a subscriber that hasn't connected yet, and real cost to doing so (see
    // SseEmitterRegistry's own comment on the old onBackpressureBuffer() failure mode this avoids).
    private final SseEmitterRegistry<String> registry = new SseEmitterRegistry<>(type -> SseEmitter.event().data(type));

    public void publish(String eventType) {
        registry.publish(eventType);
    }

    public SseEmitter register() {
        return registry.register();
    }
}
