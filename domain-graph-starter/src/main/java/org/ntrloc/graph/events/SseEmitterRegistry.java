package org.ntrloc.graph.events;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import java.util.function.Predicate;

// Shared fanout mechanism for ClusterEventBroadcaster/TaskEventBroadcaster/SchemaChangeBroadcaster,
// all three of which follow the identical shape: some upstream source (a Hazelcast topic listener,
// a direct publish() call) feeds every currently-connected SSE client the same event. Each
// broadcaster owns one instance of this, parameterized by how it turns its own event type into an
// SSE payload -- the registry itself only knows about delivery and cleanup, never event shape.
//
// Direct delivery, not a buffered queue: a slow/dead client must never grow an unbounded backlog
// for everyone else, matching the directBestEffort() semantics the old Sinks.Many-based
// broadcasters used deliberately (see either class's own comment on why onBackpressureBuffer()
// silently killed the whole stream under sustained volume with no draining subscriber). A
// registration that fails to send is torn down immediately, not retried.
public class SseEmitterRegistry<T> {

    private static final Logger LOG = LoggerFactory.getLogger(SseEmitterRegistry.class);

    private record Registration<T>(SseEmitter emitter, Predicate<T> filter) {}

    private final Function<T, SseEmitter.SseEventBuilder> toEvent;
    private final List<Registration<T>> registrations = new CopyOnWriteArrayList<>();

    public SseEmitterRegistry(Function<T, SseEmitter.SseEventBuilder> toEvent) {
        this.toEvent = toEvent;
    }

    // No timeout (0L): these streams are meant to stay open until the client disconnects, same as
    // the Flux-based versions they replace, which only ever completed on cancellation.
    public SseEmitter register() {
        return register(event -> true);
    }

    public SseEmitter register(Predicate<T> filter) {
        SseEmitter emitter = new SseEmitter(0L);
        Registration<T> registration = new Registration<>(emitter, filter);
        registrations.add(registration);
        emitter.onCompletion(() -> registrations.remove(registration));
        emitter.onTimeout(() -> registrations.remove(registration));
        emitter.onError(e -> registrations.remove(registration));
        return emitter;
    }

    public void publish(T event) {
        for (Registration<T> registration : registrations) {
            if (!registration.filter().test(event)) {
                continue;
            }
            send(registration, toEvent.apply(event));
        }
    }

    // For a shared heartbeat: a comment line is ignored by EventSource's message/named-event
    // handling, so it never reaches application listeners, but it's enough to keep a reverse
    // proxy/load balancer from treating an otherwise-idle connection as dead.
    public void broadcastComment(String comment) {
        for (Registration<T> registration : registrations) {
            send(registration, SseEmitter.event().comment(comment));
        }
    }

    private void send(Registration<T> registration, SseEmitter.SseEventBuilder event) {
        try {
            registration.emitter().send(event);
        } catch (IOException | IllegalStateException e) {
            LOG.debug("Dropping a dead SSE connection", e);
            registration.emitter().completeWithError(e);
            registrations.remove(registration);
        }
    }
}
