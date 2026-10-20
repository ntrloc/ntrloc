package org.ntrloc.graph.db.partition.schema;

import org.ntrloc.graph.cluster.ClusterService;
import org.ntrloc.graph.events.SseEmitterRegistry;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

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
//
// DEPRECATED, pending removal: superseded by org.ntrloc.graph.events.ClusterEventBroadcaster.
// SchemaManager.refreshCache() now ALSO publishes to ClusterEventBroadcaster in parallel (see its
// own comment) -- proven working end to end against the React admin UI's SchemaView. Left alone
// deliberately for now, unmodified in behavior (still converted off WebFlux below, same as
// everything else in this app), alongside its existing consumer (SchemaAdminController.
// schemaEvents / schema-events.js in the vanilla admin UI). Remove this class, SchemaManager's own
// schemaChangedTopic field, and the /api/admin/schema/events endpoint once both admin UIs have
// fully moved onto the new shared /api/events endpoint; see TaskEventBroadcaster for the same
// deprecation, same reasoning.
@Component
public class SchemaChangeBroadcaster {

    private static final String SCHEMA_CHANGED_TOPIC = "schemaChanged";

    // SseEmitterRegistry's own direct-delivery, drop-on-failure semantics are what
    // Sinks.Many.directBestEffort() used to provide here -- see TaskEventBroadcaster's own comment
    // for why this is a live tap, not a history replay.
    private final SseEmitterRegistry<String> registry = new SseEmitterRegistry<>(type -> SseEmitter.event().data(type));

    public SchemaChangeBroadcaster(ClusterService clusterService) {
        clusterService.<String>getTopic(SCHEMA_CHANGED_TOPIC)
                .addMessageListener(message -> registry.publish(message.getMessageObject()));
    }

    public SseEmitter register() {
        return registry.register();
    }
}
