package org.ntrloc.graph.db.partition.schema;

import com.hazelcast.topic.ITopic;
import org.ntrloc.graph.cluster.ClusterService;
import org.ntrloc.graph.db.partition.authorization.PermissionService;
import org.ntrloc.graph.db.partition.schema.definition.mutation.CreateItemDefinitionMutation;
import org.ntrloc.graph.db.partition.schema.definition.mutation.CreateItemPropertyDefinitionMutation;
import org.ntrloc.graph.db.partition.schema.definition.mutation.CreateLinkDefinitionMutation;
import org.ntrloc.graph.db.partition.schema.definition.mutation.CreateLinkPropertyDefinitionMutation;
import org.ntrloc.graph.db.partition.schema.definition.mutation.CreateStateMachineMutation;
import org.ntrloc.graph.db.partition.schema.definition.mutation.CreateStateMutation;
import org.ntrloc.graph.db.partition.schema.definition.mutation.CreateTraitDefinitionMutation;
import org.ntrloc.graph.db.partition.schema.definition.mutation.CreateTransitionMutation;
import org.ntrloc.graph.db.partition.schema.definition.mutation.DefinitionMutation;
import org.ntrloc.graph.db.partition.schema.definition.mutation.DeleteItemDefinitionMutation;
import org.ntrloc.graph.db.partition.schema.definition.mutation.DeleteLinkDefinitionMutation;
import org.ntrloc.graph.db.partition.schema.definition.mutation.DeletePropertyDefinitionMutation;
import org.ntrloc.graph.db.partition.schema.definition.mutation.DeleteStateMachineMutation;
import org.ntrloc.graph.db.partition.schema.definition.mutation.DeleteStateMutation;
import org.ntrloc.graph.db.partition.schema.definition.mutation.DeleteTraitDefinitionMutation;
import org.ntrloc.graph.db.partition.schema.definition.mutation.DeleteTransitionMutation;
import org.ntrloc.graph.db.partition.schema.definition.mutation.ImplementTraitMutation;
import org.ntrloc.graph.db.partition.schema.definition.mutation.RemoveTraitMutation;
import org.ntrloc.graph.db.partition.schema.definition.mutation.ReplaceControlledListMutation;
import org.ntrloc.graph.db.partition.schema.definition.mutation.SetItemInitProcessMutation;
import org.ntrloc.graph.db.partition.schema.definition.mutation.UpdateItemDefinitionMutation;
import org.ntrloc.graph.db.partition.schema.definition.mutation.UpdatePerspectiveDefinitionMutation;
import org.ntrloc.graph.db.partition.schema.definition.mutation.UpdatePropertyDefinitionMutation;
import org.ntrloc.graph.db.partition.schema.definition.mutation.UpdateStateMachineMutation;
import org.ntrloc.graph.db.partition.schema.definition.mutation.UpdateStateMutation;
import org.ntrloc.graph.db.partition.schema.definition.mutation.UpdateTransitionMutation;
import org.ntrloc.graph.db.partition.schema.definition.view.admin.AdminPropertyDefinitionView;
import org.ntrloc.graph.db.partition.schema.definition.view.admin.AdminSchemaView;
import org.ntrloc.graph.db.partition.schema.definition.view.calculated.SchemaView;
import org.ntrloc.graph.db.partition.schema.event.SchemaChangeEvent;
import org.ntrloc.graph.db.partition.schema.repository.SchemaRepository;
import org.ntrloc.graph.db.partition.security.NtrlocPrincipal;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.DependsOn;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

@Service
@DependsOn("schemaInitializer")
public class SchemaManager {

    // Cluster-wide "the schema changed, refresh your own copy" signal -- an ITopic, not the
    // IMap.put()-as-a-side-channel trick the old domain-graph-starter SchemaManagerImpl used
    // (its own comment flagged that as a hack). Every node's own SchemaManager, including the one
    // that made the change, subscribes; the publishing node's rebuild already happened locally
    // (see applyMutations), so its own message is filtered out by publishing-member comparison
    // rather than skipped some other way.
    private static final String SCHEMA_CHANGED_TOPIC = "schemaChanged";
    private static final String ENTITY_KIND_TRAIT = "trait";

    private final SchemaRepository repo;
    private final ControlledListManager controlledListManager;
    private final SchemaViewBuilder viewBuilder;
    private final PermissionService permissionService;
    private final ApplicationEventPublisher eventPublisher;
    private final ClusterService clusterService;
    private final ITopic<String> schemaChangedTopic;

    // AtomicReference, not a plain volatile field: rebuildCache() can now run on the Hazelcast
    // message-listener thread (a remote node's change arriving) as well as on whatever thread
    // called applyMutations() -- readers of getAdminSchema()/getSchema() need to see a completed
    // rebuild from either thread promptly, not just eventually. AdminSchemaView/SchemaView are
    // themselves immutable records, so this is safe publication of the reference either way.
    private final AtomicReference<AdminSchemaView> cachedAdminSchema = new AtomicReference<>();
    private final AtomicReference<SchemaView> cachedSchema = new AtomicReference<>();

    public SchemaManager(SchemaRepository repo, ControlledListManager controlledListManager, SchemaViewBuilder viewBuilder,
                          PermissionService permissionService, ApplicationEventPublisher eventPublisher, ClusterService clusterService) {
        this.repo = repo;
        this.controlledListManager = controlledListManager;
        this.viewBuilder = viewBuilder;
        this.permissionService = permissionService;
        this.eventPublisher = eventPublisher;
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

    // Grouped by mutation family (item, trait, property, link, state machine) rather than one flat
    // if-instanceof chain over all ~25 DefinitionMutation subtypes -- that chain alone drove this
    // method's Cognitive Complexity to 40. Each apply*Mutation() below returns whether it recognized
    // (and applied) the mutation, so this dispatcher stays a short, flat sequence of family checks.
    private void applyMutation(DefinitionMutation mutation) {
        if (applyItemMutation(mutation)) return;
        if (applyTraitMutation(mutation)) return;
        if (applyPropertyMutation(mutation)) return;
        if (applyLinkMutation(mutation)) return;
        if (applyStateMutation(mutation)) return;
        if (mutation instanceof ReplaceControlledListMutation m) {
            var list = controlledListManager.getListForProperty(m.propertyId())
                    .orElseThrow(() -> new IllegalArgumentException("No controlled list for property: " + m.propertyId()));
            controlledListManager.replaceValues(list.id(), list.valueType(), m.values());
            return;
        }
        if (mutation instanceof SetItemInitProcessMutation m) {
            repo.setItemInitProcess(m.itemId(), m.initProcessId());
            return;
        }
        throw new IllegalArgumentException("Unsupported mutation: " + mutation.getClass().getSimpleName());
    }

    private boolean applyItemMutation(DefinitionMutation mutation) {
        if (mutation instanceof CreateItemDefinitionMutation m) {
            var item = repo.createItem(m.name(), m.description());
            Set<String> usedNames = new HashSet<>();
            for (var p : m.properties()) {
                requireUniqueName(usedNames, p.name(), "item type '" + m.name() + "'");
                var prop = repo.createProperty(p.name(), p.description(), p.propertyType(), p.cardinality(), p.usage());
                repo.associateItemProperty(item.id(), prop.id());
            }
            eventPublisher.publishEvent(new SchemaChangeEvent.ItemTypeCreated(item.id()));
        } else if (mutation instanceof UpdateItemDefinitionMutation m) {
            repo.updateItem(m.id(), m.name(), m.description());
        } else if (mutation instanceof DeleteItemDefinitionMutation m) {
            repo.deleteItem(m.id());
            eventPublisher.publishEvent(new SchemaChangeEvent.ItemTypeDeleted(m.id()));
        } else {
            return false;
        }
        return true;
    }

    private boolean applyTraitMutation(DefinitionMutation mutation) {
        if (mutation instanceof CreateTraitDefinitionMutation m) {
            var trait = repo.createTrait(m.name(), m.description());
            Set<String> usedNames = new HashSet<>();
            for (var p : m.properties()) {
                requireUniqueName(usedNames, p.name(), ENTITY_KIND_TRAIT + " '" + m.name() + "'");
                var prop = repo.createProperty(p.name(), p.description(), p.propertyType(), p.cardinality(), p.usage());
                repo.associateTraitProperty(trait.id(), prop.id());
            }
            eventPublisher.publishEvent(new SchemaChangeEvent.TraitCreated(trait.id()));
        } else if (mutation instanceof DeleteTraitDefinitionMutation m) {
            repo.deleteTrait(m.id());
            eventPublisher.publishEvent(new SchemaChangeEvent.TraitDeleted(m.id()));
        } else if (mutation instanceof ImplementTraitMutation m) {
            repo.implementTrait(m.itemId(), m.traitId());
        } else if (mutation instanceof RemoveTraitMutation m) {
            repo.removeTrait(m.itemId(), m.traitId());
        } else {
            return false;
        }
        return true;
    }

    private boolean applyPropertyMutation(DefinitionMutation mutation) {
        if (mutation instanceof CreateItemPropertyDefinitionMutation m) {
            requireNameNotAssociated(repo.getPropertiesByItem(), m.itemId(), m.name(), "this item type");
            var prop = repo.createProperty(m.name(), m.description(), m.propertyType(), m.cardinality(), m.usage());
            repo.associateItemProperty(m.itemId(), prop.id());
        } else if (mutation instanceof CreateLinkPropertyDefinitionMutation m) {
            requireNameNotAssociated(repo.getPropertiesByLink(), m.linkId(), m.name(), "this link type");
            var prop = repo.createProperty(m.name(), m.description(), m.propertyType(), m.cardinality(), m.usage());
            repo.associateLinkProperty(m.linkId(), prop.id());
        } else if (mutation instanceof UpdatePropertyDefinitionMutation m) {
            repo.updateProperty(m.id(), m.name(), m.description(), m.propertyType(), m.cardinality(), m.usage());
        } else if (mutation instanceof DeletePropertyDefinitionMutation m) {
            repo.deleteProperty(m.id());
        } else {
            return false;
        }
        return true;
    }

    private boolean applyLinkMutation(DefinitionMutation mutation) {
        if (mutation instanceof CreateLinkDefinitionMutation m) {
            UUID linkId = repo.createLink();
            Set<String> usedNames = new HashSet<>();
            for (var p : m.properties()) {
                requireUniqueName(usedNames, p.name(), "this link type");
                var prop = repo.createProperty(p.name(), p.description(), p.propertyType(), p.cardinality(), p.usage());
                repo.associateLinkProperty(linkId, prop.id());
            }
            for (var perspective : m.perspectives()) {
                requireKnownItemOrTrait(perspective.itemId());
                repo.createPerspective(perspective.itemId(), linkId, perspective.name(), perspective.description(),
                        perspective.minCardinality(), perspective.maxCardinality());
            }
            eventPublisher.publishEvent(new SchemaChangeEvent.LinkTypeCreated(linkId));
        } else if (mutation instanceof DeleteLinkDefinitionMutation m) {
            repo.deleteLink(m.id());
            eventPublisher.publishEvent(new SchemaChangeEvent.LinkTypeDeleted(m.id()));
        } else if (mutation instanceof UpdatePerspectiveDefinitionMutation m) {
            repo.updatePerspective(m.id(), m.name(), m.description(), m.minCardinality(), m.maxCardinality());
        } else {
            return false;
        }
        return true;
    }

    private boolean applyStateMutation(DefinitionMutation mutation) {
        if (mutation instanceof CreateStateMachineMutation m) {
            repo.createStateMachine(m.itemDefinitionId(), m.name(), m.description());
        } else if (mutation instanceof UpdateStateMachineMutation m) {
            repo.updateStateMachine(m.id(), m.name(), m.description());
        } else if (mutation instanceof DeleteStateMachineMutation m) {
            repo.deleteStateMachine(m.id());
        } else if (mutation instanceof CreateStateMutation m) {
            repo.createState(m.stateMachineId(), m.name(), m.description(), m.isInitial(), m.entryProcessId(), m.exitProcessId());
        } else if (mutation instanceof UpdateStateMutation m) {
            repo.updateState(m.id(), m.name(), m.description(), m.isInitial(), m.entryProcessId(), m.exitProcessId());
        } else if (mutation instanceof DeleteStateMutation m) {
            repo.deleteState(m.id());
        } else if (mutation instanceof CreateTransitionMutation m) {
            repo.createTransition(m.fromStateId(), m.toStateId(), m.name(), m.description(), m.processId(), repo.serializeGuardCondition(m.guardCondition()));
        } else if (mutation instanceof UpdateTransitionMutation m) {
            repo.updateTransition(m.id(), m.name(), m.description(), m.processId(), repo.serializeGuardCondition(m.guardCondition()));
        } else if (mutation instanceof DeleteTransitionMutation m) {
            repo.deleteTransition(m.id());
        } else {
            return false;
        }
        return true;
    }

    // A property's name only needs to be unique within whichever single item/trait/link type
    // it's associated with -- different types legitimately have their own distinct property
    // row (different id) sharing the same name. These two checks enforce that at the type
    // level, since the DB's schema_property table itself no longer enforces uniqueness (the
    // association is a separate join table, not a column here).
    private void requireUniqueName(Set<String> namesSeenSoFar, String name, String context) {
        if (!namesSeenSoFar.add(name)) {
            throw new IllegalArgumentException("Property '" + name + "' is defined more than once for " + context);
        }
    }

    private void requireNameNotAssociated(Map<UUID, List<AdminPropertyDefinitionView>> propertiesByOwner, UUID ownerId, String name, String context) {
        boolean collision = propertiesByOwner.getOrDefault(ownerId, List.of()).stream()
                .anyMatch(p -> p.name().equals(name));
        if (collision) {
            throw new IllegalArgumentException("Property '" + name + "' already exists on " + context);
        }
    }

    // Despite the parameter name (CreatePerspectiveDefinitionMutation.itemId(), inherited here),
    // a perspective can target either an item type or a trait -- schema_item.id and
    // schema_trait.id are both valid values for schema_entity_link_perspective.entity_id (see
    // that column's own comment in SchemaInitializer -- it's deliberately polymorphic and has no
    // FK constraint of its own, so this is the only place a bad id gets caught at all). Checking
    // only getAllItems() meant any perspective whose target was actually a trait (e.g. Pack ->
    // PackComponent, where PackComponent is a trait implemented by several item types, not an
    // item type itself) threw "Unknown item" here even though the id was perfectly valid -- just
    // valid for a trait.
    private void requireKnownItemOrTrait(UUID id) {
        boolean known = repo.getAllItems().stream().anyMatch(item -> item.id().equals(id))
                || repo.getAllTraits().stream().anyMatch(trait -> trait.id().equals(id));
        if (!known) {
            throw new IllegalArgumentException("Unknown item or trait: " + id);
        }
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
