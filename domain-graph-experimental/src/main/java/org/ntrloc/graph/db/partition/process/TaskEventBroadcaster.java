package org.ntrloc.graph.db.partition.process;

import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

// A trivial broadcast signal, not a data channel: every connected SSE client (TaskAdminController)
// gets every published event and just re-fetches GET /api/admin/process/tasks, which already does
// the correct per-principal (assignee/candidate) filtering (see TaskAdminController.listTasks).
// Replicating that filtering here, per-connection, would be a second copy of the same logic with
// real risk of drifting from the first -- not worth it for what's ultimately just a "something
// changed, go check" tap on the shoulder.
@Component
public class TaskEventBroadcaster {

    // directBestEffort(), not onBackpressureBuffer(): this is a live tap, not a history replay --
    // clients always do their own fetch on connect/mount (ntrloc-nav.js's fetchTaskCount(),
    // ntrloc-tasks.js's load()), so there's nothing to gain from buffering events for a subscriber
    // that hasn't connected yet, and real cost to doing so. onBackpressureBuffer() queues events
    // for exactly that "replay to future subscribers" case, with a bounded default capacity --
    // confirmed empirically: after a few hundred published events (this session's own testing)
    // with the buffer never fully drained by a live subscriber, tryEmitNext started silently
    // failing (ignored return value) and the stream went dead for every subscriber, old and new.
    // directBestEffort() delivers only to whichever subscribers are live *right now* and drops the
    // rest -- exactly the semantics this signal actually needs, and not something that can overflow.
    private final Sinks.Many<String> sink = Sinks.many().multicast().directBestEffort();

    public void publish(String eventType) {
        sink.tryEmitNext(eventType);
    }

    public Flux<String> events() {
        return sink.asFlux();
    }
}
