package org.ntrloc.graph.db.partition.schema;

import org.ntrloc.graph.cluster.ClusterService;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

// A trivial broadcast signal, not a data channel -- mirrors TaskEventBroadcaster exactly. Every
// connected SSE client (SchemaAdminController) gets every published event and just re-fetches
// GET /api/admin/schema.
//
// Independently listens on SchemaManager's own "schemaChanged" Hazelcast topic rather than being
// explicitly invoked by SchemaManager.applyMutations() -- that topic already fires unconditionally
// on every successful mutation (property changes, renames, everything, not just create/delete),
// so tapping it directly here needs no changes to SchemaManager at all. Deliberately does NOT
// apply SchemaManager's own self-publishing-member filter: that filter exists because the
// *local* node's own AdminSchemaView/SchemaView cache was already rebuilt synchronously inside
// applyMutations() before the publish, so re-rebuilding it from its own broadcast would be
// redundant -- but a local admin's own edit should still refresh their own other browser tabs/
// panes, not just other cluster nodes, so this listener reacts to every message, including ones
// published by this same node.
@Component
public class SchemaChangeBroadcaster {

    private static final String SCHEMA_CHANGED_TOPIC = "schemaChanged";

    // directBestEffort(), not onBackpressureBuffer(): see TaskEventBroadcaster's own comment --
    // this is a live tap, not a history replay, and buffering risks the same silent stream-death
    // failure mode confirmed there under sustained event volume with no draining subscriber.
    private final Sinks.Many<String> sink = Sinks.many().multicast().directBestEffort();

    public SchemaChangeBroadcaster(ClusterService clusterService) {
        clusterService.<String>getTopic(SCHEMA_CHANGED_TOPIC)
                .addMessageListener(message -> sink.tryEmitNext(message.getMessageObject()));
    }

    public Flux<String> events() {
        return sink.asFlux();
    }
}
