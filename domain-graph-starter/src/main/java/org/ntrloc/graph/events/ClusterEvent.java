package org.ntrloc.graph.events;

import java.io.Serializable;

// The one message shape carried over ClusterEventBroadcaster's Hazelcast topic, cluster-wide, and
// out to browsers over SSE. payload is a small opaque string (JSON, a UUID, whatever the publisher
// wants) -- ClusterEventBroadcaster and its SSE relay never interpret it, only route on type.
public record ClusterEvent(String type, String payload) implements Serializable {
}
