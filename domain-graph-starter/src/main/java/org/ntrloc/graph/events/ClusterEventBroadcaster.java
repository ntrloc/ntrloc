package org.ntrloc.graph.events;

import com.hazelcast.topic.ITopic;
import jakarta.annotation.PreDestroy;
import org.ntrloc.graph.cluster.ClusterService;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

// The one standard mechanism for publishing app-wide change signals across the cluster, for
// anything that wants to notify connected UIs -- built directly on a Hazelcast ITopic so publishing
// here is unconditionally cluster-wide, with no local-only variant to accidentally reach for
// instead (see TaskEventBroadcaster: it only ever delivers within its own JVM, so a client
// connected to a different node never hears about a change made on this one -- exactly the gap
// this class exists to not repeat).
//
// SseEmitterRegistry's own direct-delivery, drop-on-failure semantics are what
// Sinks.Many.directBestEffort() used to provide here -- see SseEmitterRegistry's own comment for
// why this is a live tap, and TaskEventBroadcaster's for the specific failure mode that rules out
// a buffered alternative.
@Component
public class ClusterEventBroadcaster {

    private static final String EVENTS_TOPIC = "ntrloc.events";

    // Comfortably under the common ~60s default idle-connection timeout used by most reverse
    // proxies/load balancers/Kubernetes Ingress controllers -- an SSE comment line (ignored by
    // EventSource's message/named-event handling, so it never reaches application listeners) is
    // enough to keep those from treating this connection as idle and killing it. One shared timer
    // for every connected client, not one per connection (ClusterEventController used to merge a
    // fresh Flux.interval(...) into each individual subscriber's own stream) -- this broadcaster is
    // a singleton, so one scheduled task reaching every current registration is strictly less
    // overhead for the same effect.
    private static final Duration HEARTBEAT_INTERVAL = Duration.ofSeconds(30);

    private final ITopic<ClusterEvent> topic;
    private final SseEmitterRegistry<ClusterEvent> registry =
            new SseEmitterRegistry<>(event -> SseEmitter.event().name(event.type()).data(event.payload()));
    private final ScheduledExecutorService heartbeatExecutor =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "cluster-event-heartbeat");
                t.setDaemon(true);
                return t;
            });

    public ClusterEventBroadcaster(ClusterService clusterService) {
        this.topic = clusterService.getTopic(EVENTS_TOPIC);
        topic.addMessageListener(message -> registry.publish(message.getMessageObject()));
        heartbeatExecutor.scheduleAtFixedRate(() -> registry.broadcastComment("heartbeat"),
                HEARTBEAT_INTERVAL.toSeconds(), HEARTBEAT_INTERVAL.toSeconds(), TimeUnit.SECONDS);
    }

    public void publish(String type, String payload) {
        topic.publish(new ClusterEvent(type, payload));
    }

    public SseEmitter register(Predicate<ClusterEvent> filter) {
        return registry.register(filter);
    }

    @PreDestroy
    void shutdown() {
        heartbeatExecutor.shutdownNow();
    }
}
