package org.ntrloc.graph.db.partition.schema;

import com.hazelcast.topic.ITopic;
import org.ntrloc.graph.cluster.ClusterService;
import org.ntrloc.graph.db.partition.authorization.PermissionService;
import org.ntrloc.graph.db.partition.schema.definition.PropertyType;
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
import org.ntrloc.graph.db.partition.schema.definition.view.DefinedInView;
import org.ntrloc.graph.db.partition.schema.definition.view.SortableFieldView;
import org.ntrloc.graph.db.partition.schema.definition.view.TargetEntityView;
import org.ntrloc.graph.db.partition.schema.definition.view.admin.AdminItemDefinitionView;
import org.ntrloc.graph.db.partition.schema.definition.view.admin.AdminItemLinkPerspectiveView;
import org.ntrloc.graph.db.partition.schema.definition.view.admin.AdminLinkView;
import org.ntrloc.graph.db.partition.schema.definition.view.admin.AdminPropertyDefinitionView;
import org.ntrloc.graph.db.partition.schema.definition.view.admin.AdminSchemaView;
import org.ntrloc.graph.db.partition.schema.definition.view.admin.AdminStateMachineView;
import org.ntrloc.graph.db.partition.schema.definition.view.admin.AdminStateView;
import org.ntrloc.graph.db.partition.schema.definition.view.admin.AdminTraitDefinitionView;
import org.ntrloc.graph.db.partition.schema.definition.view.admin.AdminTransitionView;
import org.ntrloc.graph.db.partition.schema.definition.view.admin.PropertyTypeView;
import org.ntrloc.graph.db.partition.schema.definition.view.admin.TraitRefView;
import org.ntrloc.graph.db.partition.schema.repository.SchemaRepository.StateMachineRow;
import org.ntrloc.graph.db.partition.schema.repository.SchemaRepository.StateRow;
import org.ntrloc.graph.db.partition.schema.definition.view.calculated.ItemDefinitionView;
import org.ntrloc.graph.db.partition.schema.definition.view.calculated.ItemLinkPerspectiveView;
import org.ntrloc.graph.db.partition.schema.definition.view.calculated.PropertyDefinitionView;
import org.ntrloc.graph.db.partition.schema.definition.view.calculated.SchemaView;
import org.ntrloc.graph.db.partition.schema.definition.view.calculated.TraitDefinitionView;
import org.ntrloc.graph.db.partition.schema.event.SchemaChangeEvent;
import org.ntrloc.graph.db.partition.schema.repository.SchemaRepository;
import org.ntrloc.graph.db.partition.schema.repository.SchemaRepository.ItemRow;
import org.ntrloc.graph.db.partition.schema.repository.SchemaRepository.TraitRow;
import org.ntrloc.graph.db.partition.security.NtrlocPrincipal;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.DependsOn;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

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

    private final SchemaRepository repo;
    private final ControlledListManager controlledListManager;
    private final PermissionService permissionService;
    private final ApplicationEventPublisher eventPublisher;
    private final ClusterService clusterService;
    private final ITopic<String> schemaChangedTopic;

    // volatile: rebuildCache() can now run on the Hazelcast message-listener thread (a remote
    // node's change arriving) as well as on whatever thread called applyMutations() -- readers of
    // getAdminSchema()/getSchema() need to see a completed rebuild from either thread promptly,
    // not just eventually.
    private volatile AdminSchemaView cachedAdminSchema;
    private volatile SchemaView cachedSchema;

    public SchemaManager(SchemaRepository repo, ControlledListManager controlledListManager, PermissionService permissionService, ApplicationEventPublisher eventPublisher, ClusterService clusterService) {
        this.repo = repo;
        this.controlledListManager = controlledListManager;
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
        cachedAdminSchema = buildAdminSchema();
        cachedSchema = buildSchema();
    }

    public void applyMutations(List<DefinitionMutation> mutations) {
        for (DefinitionMutation mutation : mutations) {
            if (mutation instanceof CreateItemDefinitionMutation m) {
                var item = repo.createItem(m.name(), m.description());
                Set<String> usedNames = new HashSet<>();
                for (var p : m.properties()) {
                    requireUniqueName(usedNames, p.name(), "item type '" + m.name() + "'");
                    var prop = repo.createProperty(p.name(), p.description(), p.propertyType(), p.cardinality(), p.usage());
                    repo.associateItemProperty(item.id(), prop.id());
                }
                eventPublisher.publishEvent(new SchemaChangeEvent.ItemTypeCreated(item.id()));
            } else if (mutation instanceof DeleteItemDefinitionMutation m) {
                repo.deleteItem(m.id());
                eventPublisher.publishEvent(new SchemaChangeEvent.ItemTypeDeleted(m.id()));
            } else if (mutation instanceof CreateTraitDefinitionMutation m) {
                var trait = repo.createTrait(m.name(), m.description());
                Set<String> usedNames = new HashSet<>();
                for (var p : m.properties()) {
                    requireUniqueName(usedNames, p.name(), "trait '" + m.name() + "'");
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
            } else if (mutation instanceof UpdateItemDefinitionMutation m) {
                repo.updateItem(m.id(), m.name(), m.description());
            } else if (mutation instanceof CreateItemPropertyDefinitionMutation m) {
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
            } else if (mutation instanceof CreateLinkDefinitionMutation m) {
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
            } else if (mutation instanceof ReplaceControlledListMutation m) {
                var list = controlledListManager.getListForProperty(m.propertyId())
                        .orElseThrow(() -> new IllegalArgumentException("No controlled list for property: " + m.propertyId()));
                controlledListManager.replaceValues(list.id(), list.valueType(), m.values());
            } else if (mutation instanceof CreateStateMachineMutation m) {
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
            } else if (mutation instanceof SetItemInitProcessMutation m) {
                repo.setItemInitProcess(m.itemId(), m.initProcessId());
            } else {
                throw new IllegalArgumentException("Unsupported mutation: " + mutation.getClass().getSimpleName());
            }
        }
        rebuildCache();
        schemaChangedTopic.publish(UUID.randomUUID().toString());
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
        return cachedAdminSchema;
    }

    public SchemaView getSchema(NtrlocPrincipal principal) {
        if (principal.isSuperuser()) {
            return cachedSchema;
        }
        var markersByType = permissionService.getItemTypeMarkerAssignments();
        var grantedMarkers = permissionService.effectiveMarkers(principal, PermissionService.ITEM_READ);
        var visibleItems = cachedSchema.items().stream()
                .filter(item -> {
                    var markers = markersByType.getOrDefault(item.id(), List.of());
                    return !markers.isEmpty() && markers.stream().anyMatch(grantedMarkers::contains);
                })
                .toList();
        return new SchemaView(visibleItems, cachedSchema.traits());
    }

    // --- Sort support ---

    private static final List<SortableFieldView> SYSTEM_SORTABLE_FIELDS = List.of(
            new SortableFieldView("itemId",          true),
            new SortableFieldView("itemType",        true),
            new SortableFieldView("createdAt",       true),
            new SortableFieldView("updatedAt",       true),
            new SortableFieldView("visibilityState", true)
    );

    private List<SortableFieldView> sortableFieldsFor(List<AdminPropertyDefinitionView> properties) {
        var result = new ArrayList<>(SYSTEM_SORTABLE_FIELDS);
        if (properties != null) {
            properties.stream()
                    .map(p -> new SortableFieldView(p.name(), false))
                    .forEach(result::add);
        }
        return List.copyOf(result);
    }

    // --- Admin schema ---

    private AdminSchemaView buildAdminSchema() {
        var items  = repo.getAllItems();
        var traits = repo.getAllTraits();

        // id → name (for resolving perspective targets, which are keyed by item-or-trait id)
        Map<UUID, String> entityNameMap = new HashMap<>();
        items.forEach(i -> entityNameMap.put(i.id(), i.name()));
        traits.forEach(t -> entityNameMap.put(t.id(), t.name()));

        // item ids — used to determine targetKind on link perspectives
        Set<UUID> itemEntityIds = items.stream()
                .map(ItemRow::id)
                .collect(Collectors.toSet());

        var propertiesByItem     = repo.getPropertiesByItem();
        var propertiesByTrait    = repo.getPropertiesByTrait();
        var propertiesByLink     = repo.getPropertiesByLink();
        var perspectivesByEntity = repo.getPerspectivesByEntity();
        var traitIdsByItem       = repo.getTraitIdsByItem();
        var stateMachinesByItem    = repo.getStateMachinesByItem();
        var statesByStateMachine   = repo.getStatesByStateMachine();
        var transitionsByFromState = repo.getTransitionsByFromState();

        Map<UUID, TraitRow> traitById = traits.stream()
                .collect(Collectors.toMap(TraitRow::id, t -> t));

        var itemViews = items.stream().map(item -> {
            var traitIds = traitIdsByItem.getOrDefault(item.id(), List.of());
            var traitRefs = traitIds.stream().map(id -> new TraitRefView(traitById.get(id).id(), traitById.get(id).name())).toList();

            // Own properties (definedIn = null) + trait-inherited properties
            var ownProps = propertiesByItem.getOrDefault(item.id(), List.of());
            var traitProps = traitIds.stream()
                    .flatMap(traitId -> {
                        var trait = traitById.get(traitId);
                        var definedIn = new DefinedInView("trait", trait.name());
                        return propertiesByTrait.getOrDefault(traitId, List.of()).stream()
                                .map(p -> new AdminPropertyDefinitionView(
                                        p.id(), p.name(), p.description(), p.type(), p.cardinality(), p.usage(), definedIn, p.controlledListId()));
                    })
                    .toList();
            var allProps = Stream.concat(ownProps.stream(), traitProps.stream()).toList();

            // Own link perspectives (definedIn = null) + trait-inherited perspectives
            var ownLinks = buildPerspectiveAdminViews(item.id(), perspectivesByEntity, entityNameMap, itemEntityIds, null);
            var traitLinks = traitIds.stream()
                    .map(traitId -> {
                        var trait = traitById.get(traitId);
                        var definedIn = new DefinedInView("trait", trait.name());
                        return buildPerspectiveAdminViews(trait.id(), perspectivesByEntity, entityNameMap, itemEntityIds, definedIn);
                    })
                    .filter(m -> m != null && !m.isEmpty())
                    .reduce(new LinkedHashMap<>(), (acc, m) -> { acc.putAll(m); return acc; });

            var allLinks = mergeLinkAdminMaps(ownLinks, traitLinks);

            var rawStateMachines = stateMachinesByItem.get(item.id());
            List<AdminStateMachineView> stateMachineViews = null;
            if (rawStateMachines != null && !rawStateMachines.isEmpty()) {
                stateMachineViews = rawStateMachines.stream().map(machine -> {
                    var rawStates = statesByStateMachine.getOrDefault(machine.id(), List.of());
                    Map<UUID, String> stateNameById = rawStates.stream()
                            .collect(Collectors.toMap(StateRow::id, StateRow::name));
                    var stateViews = rawStates.stream().map(state -> {
                        var transitions = transitionsByFromState.getOrDefault(state.id(), List.of()).stream()
                                .map(t -> new AdminTransitionView(
                                        t.id(), t.toStateId(), stateNameById.get(t.toStateId()),
                                        t.name(), t.description(), t.processId(),
                                        repo.parseGuardCondition(t.guardCondition())))
                                .toList();
                        return new AdminStateView(state.id(), state.name(), state.description(),
                                state.isInitial(), state.entryProcessId(), state.exitProcessId(), transitions);
                    }).toList();
                    return new AdminStateMachineView(machine.id(), machine.name(), machine.description(), stateViews);
                }).toList();
            }

            return new AdminItemDefinitionView(item.id(), item.name(), item.description(), traitRefs, allProps, allLinks, sortableFieldsFor(allProps), item.initProcessId(), stateMachineViews);
        }).toList();

        var traitViews = traits.stream().map(trait -> {
            var props = propertiesByTrait.getOrDefault(trait.id(), List.of());
            var links = buildPerspectiveAdminViews(trait.id(), perspectivesByEntity, entityNameMap, itemEntityIds, null);
            return new AdminTraitDefinitionView(trait.id(), trait.name(), trait.description(), props, links, sortableFieldsFor(props));
        }).toList();

        var linkViews = repo.getAllLinkIds().stream()
                .map(id -> new AdminLinkView(id, propertiesByLink.getOrDefault(id, List.of())))
                .toList();

        List<PropertyTypeView> propertyTypes = Arrays.stream(PropertyType.values())
                .map(type -> new PropertyTypeView(type, type.validCardinalities()))
                .toList();

        return new AdminSchemaView(itemViews, traitViews, linkViews, propertyTypes);
    }

    private Map<String, List<AdminItemLinkPerspectiveView>> buildPerspectiveAdminViews(
            UUID entityId,
            Map<UUID, List<SchemaRepository.PerspectiveRow>> perspectivesByEntity,
            Map<UUID, String> entityNameMap,
            Set<UUID> itemEntityIds,
            DefinedInView definedIn) {
        var perspectives = perspectivesByEntity.get(entityId);
        if (perspectives == null || perspectives.isEmpty()) return null;
        return perspectives.stream().map(p -> {
            var inverses = repo.findInversePerspectives(p.linkId(), p.id());
            var targets = inverses.stream()
                    .map(inv -> new TargetEntityView(entityNameMap.get(inv.entityId()), itemEntityIds.contains(inv.entityId()) ? "item" : "trait"))
                    .toList();
            return Map.entry(p.name(), new AdminItemLinkPerspectiveView(
                    p.id(), p.linkId(), targets, p.description(), p.minCardinality(), p.maxCardinality(), definedIn));
        }).collect(Collectors.groupingBy(Map.Entry::getKey, LinkedHashMap::new, Collectors.mapping(Map.Entry::getValue, Collectors.toList())));
    }

    private Map<String, List<AdminItemLinkPerspectiveView>> mergeLinkAdminMaps(
            Map<String, List<AdminItemLinkPerspectiveView>> own,
            Map<String, List<AdminItemLinkPerspectiveView>> inherited) {
        if (own == null && inherited.isEmpty()) return null;
        var result = new LinkedHashMap<String, List<AdminItemLinkPerspectiveView>>();
        if (own != null) result.putAll(own);
        result.putAll(inherited);
        return result.isEmpty() ? null : result;
    }

    // --- Calculated schema ---

    private SchemaView buildSchema() {
        var items  = repo.getAllItems();
        var traits = repo.getAllTraits();

        Map<UUID, String> entityNameMap = new HashMap<>();
        items.forEach(i -> entityNameMap.put(i.id(), i.name()));
        traits.forEach(t -> entityNameMap.put(t.id(), t.name()));

        Set<UUID> itemEntityIds = items.stream()
                .map(ItemRow::id)
                .collect(Collectors.toSet());

        var propertiesByItem  = repo.getPropertiesByItem();
        var propertiesByTrait = repo.getPropertiesByTrait();
        var propertiesByLink  = repo.getPropertiesByLink();
        var perspectivesByEntity = repo.getPerspectivesByEntity();
        var traitIdsByItem    = repo.getTraitIdsByItem();

        Map<UUID, TraitRow> traitById = traits.stream()
                .collect(Collectors.toMap(TraitRow::id, t -> t));

        var itemViews = items.stream().map(item -> {
            var traitIds = traitIdsByItem.getOrDefault(item.id(), List.of());

            var ownProps = propertiesByItem.getOrDefault(item.id(), List.of()).stream()
                    .map(p -> new PropertyDefinitionView(p.id(), p.name(), p.description(), p.type(), p.cardinality(), null, allowedValuesFor(p)))
                    .toList();
            var traitProps = traitIds.stream()
                    .flatMap(traitId -> {
                        var trait = traitById.get(traitId);
                        var definedIn = new DefinedInView("trait", trait.name());
                        return propertiesByTrait.getOrDefault(traitId, List.of()).stream()
                                .map(p -> new PropertyDefinitionView(p.id(), p.name(), p.description(), p.type(), p.cardinality(), definedIn, allowedValuesFor(p)));
                    })
                    .toList();
            var allProps = Stream.concat(ownProps.stream(), traitProps.stream()).toList();

            var ownLinks = buildPerspectiveViews(item.id(), perspectivesByEntity, entityNameMap, itemEntityIds, propertiesByLink, null);
            var traitLinks = traitIds.stream()
                    .map(traitId -> {
                        var trait = traitById.get(traitId);
                        var definedIn = new DefinedInView("trait", trait.name());
                        return buildPerspectiveViews(trait.id(), perspectivesByEntity, entityNameMap, itemEntityIds, propertiesByLink, definedIn);
                    })
                    .filter(m -> m != null && !m.isEmpty())
                    .reduce(new LinkedHashMap<>(), (acc, m) -> { acc.putAll(m); return acc; });

            var allLinks = mergeLinkViews(ownLinks, traitLinks);

            // Rebuild admin props for sortableFields (includes both own and trait)
            var adminAllProps = Stream.concat(
                    propertiesByItem.getOrDefault(item.id(), List.of()).stream(),
                    traitIds.stream().flatMap(tid -> propertiesByTrait.getOrDefault(tid, List.of()).stream())
            ).toList();

            return new ItemDefinitionView(item.id(), item.name(), item.description(), allProps, allLinks, sortableFieldsFor(adminAllProps));
        }).toList();

        var traitViews = traits.stream().map(trait -> {
            var adminProps = propertiesByTrait.getOrDefault(trait.id(), List.of());
            var props = adminProps.stream()
                    .map(p -> new PropertyDefinitionView(p.id(), p.name(), p.description(), p.type(), p.cardinality(), null, allowedValuesFor(p)))
                    .toList();
            var links = buildPerspectiveViews(trait.id(), perspectivesByEntity, entityNameMap, itemEntityIds, propertiesByLink, null);
            return new TraitDefinitionView(trait.id(), trait.name(), trait.description(), props, links, sortableFieldsFor(adminProps));
        }).toList();

        return new SchemaView(itemViews, traitViews);
    }

    private Map<String, List<ItemLinkPerspectiveView>> buildPerspectiveViews(
            UUID entityId,
            Map<UUID, List<SchemaRepository.PerspectiveRow>> perspectivesByEntity,
            Map<UUID, String> entityNameMap,
            Set<UUID> itemEntityIds,
            Map<UUID, List<AdminPropertyDefinitionView>> propertiesByLink,
            DefinedInView definedIn) {
        var perspectives = perspectivesByEntity.get(entityId);
        if (perspectives == null || perspectives.isEmpty()) return null;
        return perspectives.stream().map(p -> {
            var inverses = repo.findInversePerspectives(p.linkId(), p.id());
            var targets = inverses.stream()
                    .map(inv -> new TargetEntityView(entityNameMap.get(inv.entityId()), itemEntityIds.contains(inv.entityId()) ? "item" : "trait"))
                    .toList();
            var linkProps = propertiesByLink.get(p.linkId());
            var linkPropViews = linkProps == null ? null : linkProps.stream()
                    .map(lp -> new PropertyDefinitionView(lp.id(), lp.name(), lp.description(), lp.type(), lp.cardinality(), null, allowedValuesFor(lp)))
                    .toList();
            return Map.entry(p.name(), new ItemLinkPerspectiveView(
                    targets, p.description(), p.minCardinality(), p.maxCardinality(), linkPropViews, definedIn));
        }).collect(Collectors.groupingBy(Map.Entry::getKey, LinkedHashMap::new, Collectors.mapping(Map.Entry::getValue, Collectors.toList())));
    }

    private Map<String, List<ItemLinkPerspectiveView>> mergeLinkViews(
            Map<String, List<ItemLinkPerspectiveView>> own,
            Map<String, List<ItemLinkPerspectiveView>> inherited) {
        if (own == null && inherited.isEmpty()) return null;
        var result = new LinkedHashMap<String, List<ItemLinkPerspectiveView>>();
        if (own != null) result.putAll(own);
        result.putAll(inherited);
        return result.isEmpty() ? null : result;
    }

    private List<AllowedValue> allowedValuesFor(AdminPropertyDefinitionView p) {
        if (p.controlledListId() == null) return null;
        return controlledListManager.getValues(p.controlledListId(), p.type());
    }
}
