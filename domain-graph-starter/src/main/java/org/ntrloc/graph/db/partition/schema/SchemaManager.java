package org.ntrloc.graph.db.partition.schema;

import com.hazelcast.topic.ITopic;
import org.ntrloc.graph.cluster.ClusterService;
import org.ntrloc.graph.db.partition.authorization.PermissionService;
import org.ntrloc.graph.db.partition.schema.definition.mutation.DefinitionMutation;
import org.ntrloc.graph.db.partition.schema.definition.mutation.ReplaceControlledListMutation;
import org.ntrloc.graph.db.partition.schema.definition.view.admin.AdminItemDefinitionView;
import org.ntrloc.graph.db.partition.schema.definition.view.admin.AdminSchemaView;
import org.ntrloc.graph.db.partition.schema.definition.view.calculated.SchemaView;
import org.ntrloc.graph.db.partition.security.NtrlocPrincipal;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.stereotype.Service;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

@Service
@DependsOnDatabaseInitialization
public class SchemaManager {

    // Cluster-wide "the schema changed, refresh your own copy" signal -- an ITopic, not the
    // IMap.put()-as-a-side-channel trick the old domain-graph-starter SchemaManagerImpl used
    // (its own comment flagged that as a hack). Every node's own SchemaManager, including the one
    // that made the change, subscribes; the publishing node's rebuild already happened locally
    // (see applyMutations), so its own message is filtered out by publishing-member comparison
    // rather than skipped some other way.
    //
    // SchemaChangeBroadcaster independently listens on this same topic (same name, obtained the
    // same way) to relay every message to the admin UI over SSE -- deliberately without this
    // class's self-publishing-member filter, since a local admin's own edit should still refresh
    // their own other browser tabs, not just other cluster nodes.
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

    // Root plus every transitive descendant -- the "polymorphic by default" resolution a
    // supertype-rooted cross-type query needs. Cycles can't exist (SchemaMutationValidation.
    // requireNoSupertypeCycle guards every supertype assignment), but result.add()'s
    // already-present check is a cheap defensive guard against ever infinite-looping if that
    // guard were somehow bypassed, rather than trusting it silently.
    public Set<UUID> resolveSupertypeInclusiveItemTypeIds(UUID rootItemTypeId) {
        var items = getAdminSchema().items();
        Map<UUID, List<UUID>> childrenByParent = items.stream()
                .filter(item -> item.supertypeId() != null)
                .collect(Collectors.groupingBy(AdminItemDefinitionView::supertypeId,
                        Collectors.mapping(AdminItemDefinitionView::id, Collectors.toList())));

        Set<UUID> result = new HashSet<>();
        Deque<UUID> queue = new ArrayDeque<>(List.of(rootItemTypeId));
        while (!queue.isEmpty()) {
            UUID current = queue.poll();
            if (!result.add(current)) continue;
            queue.addAll(childrenByParent.getOrDefault(current, List.of()));
        }
        return result;
    }

    // Every item type that implements the given trait, directly or via any ancestor in its
    // supertype chain. AdminItemDefinitionView.traits() only reflects an item's own direct
    // assignments (it isn't walked up the chain the way properties/links/state machines are), so
    // a naive itemTraits.contains(traitId) check would miss a subtype whose supertype implements
    // the trait -- this walks the chain explicitly instead.
    public Set<UUID> resolveTraitImplementerItemTypeIds(UUID traitId) {
        var items = getAdminSchema().items();
        Map<UUID, AdminItemDefinitionView> itemById = items.stream()
                .collect(Collectors.toMap(AdminItemDefinitionView::id, item -> item));

        Set<UUID> result = new HashSet<>();
        for (var item : items) {
            if (implementsTraitInChain(item, traitId, itemById)) {
                result.add(item.id());
            }
        }
        return result;
    }

    private boolean implementsTraitInChain(AdminItemDefinitionView item, UUID traitId, Map<UUID, AdminItemDefinitionView> itemById) {
        var current = item;
        while (current != null) {
            if (current.traits().stream().anyMatch(t -> t.id().equals(traitId))) return true;
            current = current.supertypeId() == null ? null : itemById.get(current.supertypeId());
        }
        return false;
    }
}
