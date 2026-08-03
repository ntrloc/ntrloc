package org.ntrloc.graph.db.partition.schema;

import com.hazelcast.topic.ITopic;
import org.ntrloc.graph.cluster.ClusterService;
import org.ntrloc.graph.db.partition.authorization.PermissionService;
import org.ntrloc.graph.db.partition.schema.definition.mutation.DefinitionMutation;
import org.ntrloc.graph.db.partition.schema.definition.mutation.ReplaceControlledListMutation;
import org.ntrloc.graph.db.partition.schema.definition.view.admin.AdminSchemaView;
import org.ntrloc.graph.db.partition.schema.definition.view.calculated.SchemaView;
import org.ntrloc.graph.db.partition.security.NtrlocPrincipal;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

@Service
@DependsOnDatabaseInitialization
public class SchemaManager {

    // Cluster-wide "the schema changed, refresh your own copy" signal -- an ITopic, not the
    // IMap.put()-as-a-side-channel trick the old domain-graph-starter SchemaManagerImpl used
    // (its own comment flagged that as a hack). Every node's own SchemaManager, including the one
    // that made the change, subscribes; the publishing node's rebuild already happened locally
    // (see applyMutations), so its own message is filtered out by publishing-member comparison
    // rather than skipped some other way.
    private static final String SCHEMA_CHANGED_TOPIC = "schemaChanged";

    private final ControlledListManager controlledListManager;
    private final SchemaViewBuilder viewBuilder;
    private final ItemMutationApplier itemMutationApplier;
    private final TraitMutationApplier traitMutationApplier;
    private final PropertyMutationApplier propertyMutationApplier;
    private final LinkMutationApplier linkMutationApplier;
    private final StateMutationApplier stateMutationApplier;
    private final PermissionService permissionService;
    private final ClusterService clusterService;
    private final ITopic<String> schemaChangedTopic;

    // AtomicReference, not a plain volatile field: rebuildCache() can now run on the Hazelcast
    // message-listener thread (a remote node's change arriving) as well as on whatever thread
    // called applyMutations() -- readers of getAdminSchema()/getSchema() need to see a completed
    // rebuild from either thread promptly, not just eventually. AdminSchemaView/SchemaView are
    // themselves immutable records, so this is safe publication of the reference either way.
    private final AtomicReference<AdminSchemaView> cachedAdminSchema = new AtomicReference<>();
    private final AtomicReference<SchemaView> cachedSchema = new AtomicReference<>();

    public SchemaManager(ControlledListManager controlledListManager, SchemaViewBuilder viewBuilder,
                          ItemMutationApplier itemMutationApplier, TraitMutationApplier traitMutationApplier,
                          PropertyMutationApplier propertyMutationApplier, LinkMutationApplier linkMutationApplier,
                          StateMutationApplier stateMutationApplier, PermissionService permissionService,
                          ClusterService clusterService) {
        this.controlledListManager = controlledListManager;
        this.viewBuilder = viewBuilder;
        this.itemMutationApplier = itemMutationApplier;
        this.traitMutationApplier = traitMutationApplier;
        this.propertyMutationApplier = propertyMutationApplier;
        this.linkMutationApplier = linkMutationApplier;
        this.stateMutationApplier = stateMutationApplier;
        this.permissionService = permissionService;
        this.clusterService = clusterService;
        rebuildCache();

        this.schemaChangedTopic = clusterService.getTopic(SCHEMA_CHANGED_TOPIC);
        schemaChangedTopic.addMessageListener(message -> {
            if (!message.getPublishingMember().equals(clusterService.getLocalMember())) {
                rebuildCache();
            }
        });
    }

    private void rebuildCache() {
        cachedAdminSchema.set(viewBuilder.buildAdminSchema());
        cachedSchema.set(viewBuilder.buildSchema());
    }

    public void applyMutations(List<DefinitionMutation> mutations) {
        for (DefinitionMutation mutation : mutations) {
            applyMutation(mutation);
        }
        rebuildCache();
        schemaChangedTopic.publish(UUID.randomUUID().toString());
    }

    // Dispatches to one applier per mutation family (item, trait, property, link, state machine)
    // -- see those classes' own history for why this isn't one flat if-instanceof chain over all
    // ~25 DefinitionMutation subtypes (that alone drove this class's dependency count sky-high).
    private void applyMutation(DefinitionMutation mutation) {
        if (itemMutationApplier.apply(mutation)) return;
        if (traitMutationApplier.apply(mutation)) return;
        if (propertyMutationApplier.apply(mutation)) return;
        if (linkMutationApplier.apply(mutation)) return;
        if (stateMutationApplier.apply(mutation)) return;
        if (mutation instanceof ReplaceControlledListMutation m) {
            var list = controlledListManager.getListForProperty(m.propertyId())
                    .orElseThrow(() -> new IllegalArgumentException("No controlled list for property: " + m.propertyId()));
            controlledListManager.replaceValues(list.id(), list.valueType(), m.values());
            return;
        }
        throw new IllegalArgumentException("Unsupported mutation: " + mutation.getClass().getSimpleName());
    }

    public AdminSchemaView getAdminSchema() {
        return cachedAdminSchema.get();
    }

    public SchemaView getSchema(NtrlocPrincipal principal) {
        SchemaView schema = cachedSchema.get();
        if (principal.isSuperuser()) {
            return schema;
        }
        var markersByType = permissionService.getItemTypeMarkerAssignments();
        var grantedMarkers = permissionService.effectiveMarkers(principal, PermissionService.ITEM_READ);
        var visibleItems = schema.items().stream()
                .filter(item -> {
                    var markers = markersByType.getOrDefault(item.id(), List.of());
                    return !markers.isEmpty() && markers.stream().anyMatch(grantedMarkers::contains);
                })
                .toList();
        return new SchemaView(visibleItems, schema.traits());
    }
}
