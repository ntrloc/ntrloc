package org.ntrloc.graph.events;

import com.hazelcast.topic.ITopic;
import org.ntrloc.graph.cluster.ClusterService;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

// The one standard mechanism for publishing app-wide change signals across the cluster, for
// anything that wants to notify connected UIs -- built directly on a Hazelcast ITopic so publishing
// here is unconditionally cluster-wide, with no local-only variant to accidentally reach for
// instead (see TaskEventBroadcaster: it only ever publishes into its own JVM's local Sinks.Many, so
// a client connected to a different node never hears about a change made on this one -- exactly the
// gap this class exists to not repeat).
//
// Parallel infrastructure, not yet replacing anything: SchemaManager publishes here in addition to
// its own pre-existing schemaChangedTopic, and nothing currently consumes this except the new
// ClusterEventController SSE relay. Existing admin-ui SSE wiring (task-events.js, schema-events.js)
// is untouched.
//
// directBestEffort(), not onBackpressureBuffer(): same reasoning as TaskEventBroadcaster/
// SchemaChangeBroadcaster -- this is a live tap, and a slow/dead subscriber must never grow an
// unbounded queue for everyone else.
@Component
public class ClusterEventBroadcaster {

    private static final String EVENTS_TOPIC = "ntrloc.events";

    private final ITopic<ClusterEvent> topic;
    private final Sinks.Many<ClusterEvent> sink = Sinks.many().multicast().directBestEffort();

    public ClusterEventBroadcaster(ClusterService clusterService) {
        this.topic = clusterService.getTopic(EVENTS_TOPIC);
        topic.addMessageListener(message -> sink.tryEmitNext(message.getMessageObject()));
    }

    public void publish(String type, String payload) {
        topic.publish(new ClusterEvent(type, payload));
    }

    public Flux<ClusterEvent> events() {
        return sink.asFlux();
    }
}
