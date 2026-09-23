package org.ntrloc.graph.db.partition.schema;

import org.ntrloc.graph.db.partition.schema.definition.PropertyCardinality;
import org.ntrloc.graph.db.partition.schema.definition.PropertyType;
import org.ntrloc.graph.db.partition.schema.definition.view.DefinedInView;
import org.ntrloc.graph.db.partition.schema.definition.view.SortableFieldView;
import org.ntrloc.graph.db.partition.schema.definition.view.TargetEntityView;
import org.ntrloc.graph.db.partition.schema.definition.view.admin.AdminControlledListView;
import org.ntrloc.graph.db.partition.schema.definition.view.admin.AdminItemDefinitionView;
import org.ntrloc.graph.db.partition.schema.definition.view.admin.AdminItemLinkPerspectiveView;
import org.ntrloc.graph.db.partition.schema.definition.view.admin.AdminLinkView;
import org.ntrloc.graph.db.partition.schema.definition.view.admin.AdminPropertyDefinitionView;
import org.ntrloc.graph.db.partition.schema.definition.view.admin.AdminPropertyGroupView;
import org.ntrloc.graph.db.partition.schema.definition.view.admin.AdminSchemaView;
import org.ntrloc.graph.db.partition.schema.definition.view.admin.AdminStateMachineView;
import org.ntrloc.graph.db.partition.schema.definition.view.admin.AdminStateView;
import org.ntrloc.graph.db.partition.schema.definition.view.admin.AdminTraitDefinitionView;
import org.ntrloc.graph.db.partition.schema.definition.view.admin.AdminTransitionView;
import org.ntrloc.graph.db.partition.schema.definition.view.admin.PropertyTypeView;
import org.ntrloc.graph.db.partition.schema.definition.view.admin.TraitRefView;
import org.ntrloc.graph.db.partition.schema.definition.view.calculated.ItemDefinitionView;
import org.ntrloc.graph.db.partition.schema.definition.view.calculated.ItemLinkPerspectiveView;
import org.ntrloc.graph.db.partition.schema.definition.view.calculated.PropertyDefinitionView;
import org.ntrloc.graph.db.partition.schema.definition.view.calculated.PropertyGroupDefinitionView;
import org.ntrloc.graph.db.partition.schema.definition.view.calculated.SchemaView;
import org.ntrloc.graph.db.partition.schema.definition.view.calculated.TraitDefinitionView;
import org.ntrloc.graph.db.partition.schema.repository.SchemaRepository;
import org.ntrloc.graph.db.partition.schema.repository.SchemaRepository.ItemRow;
import org.ntrloc.graph.db.partition.schema.repository.SchemaRepository.StateMachineRow;
import org.ntrloc.graph.db.partition.schema.repository.SchemaRepository.StateRow;
import org.ntrloc.graph.db.partition.schema.repository.SchemaRepository.TraitRow;
import org.springframework.stereotype.Component;

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

// Builds both schema projections (admin: full detail for the schema editor; calculated: the
// client-facing shape SchemaManager caches and serves) from SchemaRepository's raw rows. Split out
// of SchemaManager -- which owns the cache lifecycle and mutation application -- to keep each class
// focused on one job; this one alone accounted for most of SchemaManager's dependency count.
@Component
class SchemaViewBuilder {

    private static final String ENTITY_KIND_TRAIT = "trait";
    private static final String ENTITY_KIND_SUPERTYPE = "supertype";

    private static final List<SortableFieldView> SYSTEM_SORTABLE_FIELDS = List.of(
            new SortableFieldView("itemId",          true),
            new SortableFieldView("itemType",        true),
            new SortableFieldView("createdAt",       true),
            new SortableFieldView("updatedAt",       true),
            new SortableFieldView("visibilityState", true)
    );

    private final SchemaRepository repo;
    private final ControlledListManager controlledListManager;

    SchemaViewBuilder(SchemaRepository repo, ControlledListManager controlledListManager) {
        this.repo = repo;
        this.controlledListManager = controlledListManager;
    }

    private List<SortableFieldView> sortableFieldsFor(Contents contents) {
        var result = new ArrayList<>(SYSTEM_SORTABLE_FIELDS);
        appendSortableFields(contents.properties(), contents.groups(), "", result);
        return List.copyOf(result);
    }

    // Recurses through groups so a scalar leaf nested under one is sortable via its dot-separated
    // path (e.g. "dimensions.width"); a trait's contributions sit under its namespace group, so they
    // come out as "File.name" -- matching the dot-path resolution RegisterPartitionManager already
    // does for both ORDER BY and filter predicates, this just exposes it as a pickable option.
    // Groups themselves, and LIST/SET-cardinality properties at any depth, are skipped: neither has
    // a single scalar value to order by. A BINARY leaf is skipped too -- its own value is never
    // stored in the register's flat properties JSONB, so it isn't itself a sort target (see
    // RegisterPartitionManager.sortExpressionFor's own rejection of a bare BINARY leaf) -- instead
    // its intrinsic attributes are offered, each addressed the same way it reads in the projected
    // response (content.metadata.length, not content.length -- see assembleBinaryValue), currently
    // just ".metadata.length" (mirrors RegisterPartitionManager.BINARY_METADATA_PATH_TYPES; extend
    // both together).
    private void appendSortableFields(List<AdminPropertyDefinitionView> properties, List<AdminPropertyGroupView> groups,
                                      String pathPrefix, List<SortableFieldView> result) {
        for (var p : properties) {
            if (p.cardinality() != PropertyCardinality.SINGLE) continue;
            if (p.type() == PropertyType.BINARY) {
                result.add(new SortableFieldView(pathPrefix + p.name() + ".metadata.length", false));
            } else {
                result.add(new SortableFieldView(pathPrefix + p.name(), false));
            }
        }
        for (var g : groups) {
            appendSortableFields(g.properties(), g.groups(), pathPrefix + g.name() + ".", result);
        }
    }

    // What a container (item type, trait, link type or group) directly holds: its properties and its
    // property groups, the latter already resolved recursively.
    private record Contents(List<AdminPropertyDefinitionView> properties, List<AdminPropertyGroupView> groups) {
        static final Contents EMPTY = new Contents(List.of(), List.of());
    }

    // Every owner's own (not inherited) contents. Properties and groups are read from separate
    // tables, each already keyed by its single parent, so this is just stitching them together.
    private record OwnerContents(Map<UUID, Contents> byItem, Map<UUID, Contents> byTrait, Map<UUID, Contents> byLink) {}

    private OwnerContents loadOwnerContents() {
        var propertiesByGroup = repo.getPropertiesByGroup();
        var groupsByGroup = repo.getGroupsByGroup();
        return new OwnerContents(
                resolveContents(repo.getPropertiesByItem(), repo.getGroupsByItem(), propertiesByGroup, groupsByGroup),
                resolveContents(repo.getPropertiesByTrait(), repo.getGroupsByTrait(), propertiesByGroup, groupsByGroup),
                resolveContents(repo.getPropertiesByLink(), repo.getGroupsByLink(), propertiesByGroup, groupsByGroup));
    }

    private Map<UUID, Contents> resolveContents(
            Map<UUID, List<AdminPropertyDefinitionView>> propertiesByOwner, Map<UUID, List<SchemaRepository.GroupRow>> groupsByOwner,
            Map<UUID, List<AdminPropertyDefinitionView>> propertiesByGroup, Map<UUID, List<SchemaRepository.GroupRow>> groupsByGroup) {
        Set<UUID> owners = new HashSet<>(propertiesByOwner.keySet());
        owners.addAll(groupsByOwner.keySet());
        Map<UUID, Contents> result = new HashMap<>();
        for (UUID owner : owners) {
            result.put(owner, new Contents(
                    propertiesByOwner.getOrDefault(owner, List.of()),
                    groupsByOwner.getOrDefault(owner, List.of()).stream()
                            .map(g -> resolveGroup(g, propertiesByGroup, groupsByGroup))
                            .toList()));
        }
        return result;
    }

    private AdminPropertyGroupView resolveGroup(
            SchemaRepository.GroupRow group,
            Map<UUID, List<AdminPropertyDefinitionView>> propertiesByGroup, Map<UUID, List<SchemaRepository.GroupRow>> groupsByGroup) {
        return new AdminPropertyGroupView(group.id(), group.name(), group.description(), null, false,
                propertiesByGroup.getOrDefault(group.id(), List.of()),
                groupsByGroup.getOrDefault(group.id(), List.of()).stream()
                        .map(child -> resolveGroup(child, propertiesByGroup, groupsByGroup))
                        .toList());
    }

    // --- Admin schema ---

    AdminSchemaView buildAdminSchema() {
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

        var ownerContents        = loadOwnerContents();
        var perspectivesByEntity = repo.getPerspectivesByEntity();
        var traitIdsByItem       = repo.getTraitIdsByItem();
        var stateMachinesByItem    = repo.getStateMachinesByItem();
        var statesByStateMachine   = repo.getStatesByStateMachine();
        var transitionsByFromState = repo.getTransitionsByFromState();

        Map<UUID, TraitRow> traitById = traits.stream()
                .collect(Collectors.toMap(TraitRow::id, t -> t));
        Map<UUID, ItemRow> itemById = items.stream()
                .collect(Collectors.toMap(ItemRow::id, i -> i));

        var itemViews = items.stream().map(item -> {
            var traitIds = traitIdsByItem.getOrDefault(item.id(), List.of());
            var traitRefs = traitIds.stream().map(id -> new TraitRefView(traitById.get(id).id(), traitById.get(id).name())).toList();

            // Own contents + trait namespaces + full supertype chain's effective contents
            var allContents = effectiveContentsAdmin(item, itemById, traitIdsByItem, ownerContents, traitById);

            // Own link perspectives + trait-inherited perspectives + supertype chain's effective links
            var allLinks = effectiveLinksAdmin(item, itemById, perspectivesByEntity, entityNameMap, itemEntityIds, traitIdsByItem, traitById);

            // Own state machines + full supertype chain's state machines (additive, no override)
            var stateMachineViews = effectiveStateMachinesAdmin(item, itemById, stateMachinesByItem, statesByStateMachine, transitionsByFromState);

            return new AdminItemDefinitionView(item.id(), item.name(), item.description(), traitRefs, allContents.properties(), allContents.groups(), allLinks,
                    sortableFieldsFor(allContents), stateMachineViews.isEmpty() ? null : stateMachineViews,
                    item.supertypeId(), item.abstractType(), item.displayLabelPattern());
        }).toList();

        var traitViews = traits.stream().map(trait -> {
            var contents = ownerContents.byTrait().getOrDefault(trait.id(), Contents.EMPTY);
            var links = buildPerspectiveAdminViews(trait.id(), perspectivesByEntity, entityNameMap, itemEntityIds, null);
            return new AdminTraitDefinitionView(trait.id(), trait.name(), trait.description(), contents.properties(), contents.groups(), links, sortableFieldsFor(contents));
        }).toList();

        var linkViews = repo.getAllLinkIds().stream()
                .map(id -> {
                    var contents = ownerContents.byLink().getOrDefault(id, Contents.EMPTY);
                    return new AdminLinkView(id, contents.properties(), contents.groups());
                })
                .toList();

        List<PropertyTypeView> propertyTypes = Arrays.stream(PropertyType.values())
                .map(type -> new PropertyTypeView(type, type.validCardinalities()))
                .toList();

        var controlledLists = buildControlledListViews(ownerContents, entityNameMap);

        return new AdminSchemaView(itemViews, traitViews, linkViews, propertyTypes, controlledLists);
    }

    // controlledListManager.getAllLists() + a valueCount per list + the reverse "which properties
    // point at each list" map, built from each owner's own (not inherited) contents, so each property
    // appears once, under its true owner. Link-owned list-backed properties are rare; they get a
    // generic owner label since links carry no name.
    private List<AdminControlledListView> buildControlledListViews(OwnerContents ownerContents, Map<UUID, String> entityNameMap) {
        Map<UUID, List<AdminControlledListView.UsageRef>> usageByListId = new HashMap<>();
        ownerContents.byItem().forEach((ownerId, contents) ->
                collectListUsage(contents, entityNameMap.getOrDefault(ownerId, "(item)"), usageByListId));
        ownerContents.byTrait().forEach((ownerId, contents) ->
                collectListUsage(contents, entityNameMap.getOrDefault(ownerId, "(trait)"), usageByListId));
        ownerContents.byLink().forEach((ownerId, contents) ->
                collectListUsage(contents, "(link property)", usageByListId));

        return controlledListManager.getAllLists().stream()
                .map(list -> new AdminControlledListView(list.id(), list.name(), list.valueType(),
                        controlledListManager.countValues(list.id()),
                        usageByListId.getOrDefault(list.id(), List.of())))
                .toList();
    }

    private void collectListUsage(Contents contents, String ownerLabel, Map<UUID, List<AdminControlledListView.UsageRef>> out) {
        collectListUsage(contents.properties(), contents.groups(), ownerLabel, out);
    }

    private void collectListUsage(List<AdminPropertyDefinitionView> props, List<AdminPropertyGroupView> groups, String ownerLabel,
                                  Map<UUID, List<AdminControlledListView.UsageRef>> out) {
        for (AdminPropertyDefinitionView p : props) {
            if (p.controlledListId() != null) {
                out.computeIfAbsent(p.controlledListId(), k -> new ArrayList<>())
                        .add(new AdminControlledListView.UsageRef(p.id(), p.name(), ownerLabel));
            }
        }
        for (AdminPropertyGroupView g : groups) {
            collectListUsage(g.properties(), g.groups(), ownerLabel, out);
        }
    }

    // An item's own contents (definedIn = null) plus one namespace group per directly-implemented
    // trait -- no supertype involvement, used as the per-level building block for
    // effectiveContentsAdmin's chain walk. A trait's contributions are addressed under its name
    // everywhere (projections, mutations, filters, sorts), so they are presented the same way here:
    // the synthetic group carries the trait's id and name, and the trait's own properties and groups
    // sit inside it.
    private Contents ownAndTraitContentsAdmin(
            ItemRow item, Map<UUID, List<UUID>> traitIdsByItem, OwnerContents ownerContents, Map<UUID, TraitRow> traitById) {
        var own = ownerContents.byItem().getOrDefault(item.id(), Contents.EMPTY);
        var namespaces = traitIdsByItem.getOrDefault(item.id(), List.of()).stream()
                .map(traitId -> {
                    var trait = traitById.get(traitId);
                    var contents = ownerContents.byTrait().getOrDefault(traitId, Contents.EMPTY);
                    return new AdminPropertyGroupView(trait.id(), trait.name(), trait.description(),
                            new DefinedInView(ENTITY_KIND_TRAIT, trait.name()), true, contents.properties(), contents.groups());
                })
                .toList();
        return new Contents(own.properties(), Stream.concat(own.groups().stream(), namespaces.stream()).toList());
    }

    // Recursive: own+trait contents, plus the full supertype chain's effective contents walked
    // upward. Something inherited from a supertype is tagged with that supertype's name -- but only
    // if it isn't already tagged (i.e. it's genuinely that ancestor's own, not something *that*
    // ancestor itself inherited from a trait or a further ancestor), so the tag always names the
    // actual originating source, not just the immediate parent. A trait implemented at two levels of
    // the chain contributes one namespace group, not two.
    private Contents effectiveContentsAdmin(
            ItemRow item, Map<UUID, ItemRow> itemById, Map<UUID, List<UUID>> traitIdsByItem,
            OwnerContents ownerContents, Map<UUID, TraitRow> traitById) {
        var own = ownAndTraitContentsAdmin(item, traitIdsByItem, ownerContents, traitById);
        var supertype = item.supertypeId() == null ? null : itemById.get(item.supertypeId());
        if (supertype == null) return own;
        var superContents = effectiveContentsAdmin(supertype, itemById, traitIdsByItem, ownerContents, traitById);
        var tag = new DefinedInView(ENTITY_KIND_SUPERTYPE, supertype.name());
        var inheritedProps = superContents.properties().stream()
                .map(p -> p.definedIn() == null ? p.withDefinedIn(tag) : p)
                .toList();
        Set<String> traitNamespacesHeld = own.groups().stream()
                .filter(AdminPropertyGroupView::traitNamespace)
                .map(AdminPropertyGroupView::name)
                .collect(Collectors.toSet());
        var inheritedGroups = superContents.groups().stream()
                .filter(g -> !(g.traitNamespace() && traitNamespacesHeld.contains(g.name())))
                .map(g -> g.definedIn() == null ? g.withDefinedIn(tag) : g)
                .toList();
        return new Contents(
                Stream.concat(own.properties().stream(), inheritedProps.stream()).toList(),
                Stream.concat(own.groups().stream(), inheritedGroups.stream()).toList());
    }

    private Map<String, List<AdminItemLinkPerspectiveView>> ownAndTraitLinksAdmin(
            ItemRow item, Map<UUID, List<SchemaRepository.PerspectiveRow>> perspectivesByEntity,
            Map<UUID, String> entityNameMap, Set<UUID> itemEntityIds,
            Map<UUID, List<UUID>> traitIdsByItem, Map<UUID, TraitRow> traitById) {
        var traitIds = traitIdsByItem.getOrDefault(item.id(), List.of());
        var ownLinks = buildPerspectiveAdminViews(item.id(), perspectivesByEntity, entityNameMap, itemEntityIds, null);
        var traitLinks = traitIds.stream()
                .map(traitId -> {
                    var trait = traitById.get(traitId);
                    var definedIn = new DefinedInView(ENTITY_KIND_TRAIT, trait.name());
                    return namespaced(trait.name(), buildPerspectiveAdminViews(trait.id(), perspectivesByEntity, entityNameMap, itemEntityIds, definedIn));
                })
                .filter(m -> m != null && !m.isEmpty())
                .reduce(new LinkedHashMap<>(), (acc, m) -> { acc.putAll(m); return acc; });
        return mergeLinkAdminMaps(ownLinks, traitLinks);
    }

    // Recursive counterpart to effectivePropertiesAdmin for links. mergeLinkAdminMaps requires
    // its second argument to be a non-null (possibly empty) map -- retagLinksAdminAsSupertype
    // upholds that even when the recursive call itself returns null (an ancestor with no links
    // of its own at all).
    private Map<String, List<AdminItemLinkPerspectiveView>> effectiveLinksAdmin(
            ItemRow item, Map<UUID, ItemRow> itemById,
            Map<UUID, List<SchemaRepository.PerspectiveRow>> perspectivesByEntity,
            Map<UUID, String> entityNameMap, Set<UUID> itemEntityIds,
            Map<UUID, List<UUID>> traitIdsByItem, Map<UUID, TraitRow> traitById) {
        var own = ownAndTraitLinksAdmin(item, perspectivesByEntity, entityNameMap, itemEntityIds, traitIdsByItem, traitById);
        var supertype = item.supertypeId() == null ? null : itemById.get(item.supertypeId());
        if (supertype == null) return own;
        var inherited = effectiveLinksAdmin(supertype, itemById, perspectivesByEntity, entityNameMap, itemEntityIds, traitIdsByItem, traitById);
        return mergeLinkAdminMaps(own, retagLinksAdminAsSupertype(inherited, supertype.name()));
    }

    private Map<String, List<AdminItemLinkPerspectiveView>> retagLinksAdminAsSupertype(
            Map<String, List<AdminItemLinkPerspectiveView>> links, String supertypeName) {
        if (links == null) return new LinkedHashMap<>();
        var definedIn = new DefinedInView(ENTITY_KIND_SUPERTYPE, supertypeName);
        var result = new LinkedHashMap<String, List<AdminItemLinkPerspectiveView>>();
        links.forEach((name, list) -> result.put(name, list.stream()
                .map(p -> p.definedIn() == null
                        ? new AdminItemLinkPerspectiveView(p.id(), p.linkId(), p.targets(), p.description(), p.minCardinality(), p.maxCardinality(), definedIn)
                        : p)
                .toList()));
        return result;
    }

    private List<AdminStateMachineView> ownStateMachinesAdmin(
            ItemRow item, Map<UUID, List<StateMachineRow>> stateMachinesByItem,
            Map<UUID, List<StateRow>> statesByStateMachine,
            Map<UUID, List<SchemaRepository.TransitionRow>> transitionsByFromState) {
        var rawStateMachines = stateMachinesByItem.get(item.id());
        if (rawStateMachines == null || rawStateMachines.isEmpty()) return List.of();
        return rawStateMachines.stream().map(machine -> {
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
                        state.kind(), state.entryProcessId(), state.exitProcessId(),
                        state.entryMarkerDecisionKey(), transitions);
            }).toList();
            return new AdminStateMachineView(machine.id(), machine.name(), machine.description(), stateViews);
        }).toList();
    }

    // Additive, no override: a subtype's effective state machines are its own plus every
    // ancestor's, concatenated. Unlike properties/links, AdminStateMachineView carries no
    // definedIn -- a state machine either belongs to this type or an ancestor's, and since it's
    // never redefined along the way (strictly additive), there's nothing to disambiguate.
    private List<AdminStateMachineView> effectiveStateMachinesAdmin(
            ItemRow item, Map<UUID, ItemRow> itemById,
            Map<UUID, List<StateMachineRow>> stateMachinesByItem,
            Map<UUID, List<StateRow>> statesByStateMachine,
            Map<UUID, List<SchemaRepository.TransitionRow>> transitionsByFromState) {
        var own = ownStateMachinesAdmin(item, stateMachinesByItem, statesByStateMachine, transitionsByFromState);
        var supertype = item.supertypeId() == null ? null : itemById.get(item.supertypeId());
        if (supertype == null) return own;
        var inherited = effectiveStateMachinesAdmin(supertype, itemById, stateMachinesByItem, statesByStateMachine, transitionsByFromState);
        return Stream.concat(own.stream(), inherited.stream()).toList();
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
                    .map(inv -> new TargetEntityView(entityNameMap.get(inv.entityId()), itemEntityIds.contains(inv.entityId()) ? "item" : ENTITY_KIND_TRAIT))
                    .toList();
            return Map.entry(p.name(), new AdminItemLinkPerspectiveView(
                    p.id(), p.linkId(), targets, p.description(), p.minCardinality(), p.maxCardinality(), definedIn));
        }).collect(Collectors.groupingBy(Map.Entry::getKey, LinkedHashMap::new, Collectors.mapping(Map.Entry::getValue, Collectors.toList())));
    }

    // A trait's link perspectives are addressed under the trait's name on an item, like its
    // properties -- "File.attachments", not "attachments" -- so they can never collide with the item
    // type's own perspectives.
    private static <V> Map<String, List<V>> namespaced(String traitName, Map<String, List<V>> perspectives) {
        if (perspectives == null) return null;
        var result = new LinkedHashMap<String, List<V>>();
        perspectives.forEach((name, list) -> result.put(traitName + "." + name, list));
        return result;
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

    SchemaView buildSchema() {
        var items  = repo.getAllItems();
        var traits = repo.getAllTraits();

        Map<UUID, String> entityNameMap = new HashMap<>();
        items.forEach(i -> entityNameMap.put(i.id(), i.name()));
        traits.forEach(t -> entityNameMap.put(t.id(), t.name()));

        Set<UUID> itemEntityIds = items.stream()
                .map(ItemRow::id)
                .collect(Collectors.toSet());

        var ownerContents = loadOwnerContents();
        var perspectivesByEntity = repo.getPerspectivesByEntity();
        var traitIdsByItem    = repo.getTraitIdsByItem();

        Map<UUID, TraitRow> traitById = traits.stream()
                .collect(Collectors.toMap(TraitRow::id, t -> t));
        Map<UUID, ItemRow> itemById = items.stream()
                .collect(Collectors.toMap(ItemRow::id, i -> i));

        var linkBuildContext = new LinkBuildContext(perspectivesByEntity, entityNameMap, itemEntityIds, ownerContents.byLink(), traitIdsByItem, traitById);

        var itemViews = items.stream().map(item -> {
            var contents = effectiveContentsAdmin(item, itemById, traitIdsByItem, ownerContents, traitById);
            var allLinks = effectiveLinks(item, itemById, linkBuildContext);

            return new ItemDefinitionView(item.id(), item.name(), item.description(),
                    toCalculatedProperties(contents.properties()), toCalculatedGroups(contents.groups()), allLinks,
                    sortableFieldsFor(contents), item.supertypeId(), item.abstractType());
        }).toList();

        var traitViews = traits.stream().map(trait -> {
            var contents = ownerContents.byTrait().getOrDefault(trait.id(), Contents.EMPTY);
            var links = buildPerspectiveViews(trait.id(), linkBuildContext, null);
            return new TraitDefinitionView(trait.id(), trait.name(), trait.description(),
                    toCalculatedProperties(contents.properties()), toCalculatedGroups(contents.groups()), links, sortableFieldsFor(contents));
        }).toList();

        return new SchemaView(itemViews, traitViews);
    }

    // Admin -> client-facing, recursively. definedIn is carried over as-is at every level, so the
    // trait/supertype tags the effective walk put on nodes survive into the calculated schema.
    private List<PropertyDefinitionView> toCalculatedProperties(List<AdminPropertyDefinitionView> properties) {
        return properties.stream().map(this::toCalculated).toList();
    }

    private List<PropertyGroupDefinitionView> toCalculatedGroups(List<AdminPropertyGroupView> groups) {
        return groups.stream()
                .map(g -> new PropertyGroupDefinitionView(g.id(), g.name(), g.description(), g.definedIn(), g.traitNamespace(),
                        toCalculatedProperties(g.properties()), toCalculatedGroups(g.groups())))
                .toList();
    }

    private PropertyDefinitionView toCalculated(AdminPropertyDefinitionView p) {
        return new PropertyDefinitionView(
                p.id(), p.name(), p.description(), p.type(), p.cardinality(), p.definedIn(), allowedValuesFor(p));
    }

    // Bundles the schema-wide lookup maps ownAndTraitLinks/effectiveLinks/buildPerspectiveViews all
    // thread through unchanged -- keeps effectiveLinks under Sonar's 7-parameter limit (S107)
    // without changing behavior; every field here is still exactly what buildSchema() already
    // builds once per call and passes down.
    private record LinkBuildContext(
            Map<UUID, List<SchemaRepository.PerspectiveRow>> perspectivesByEntity,
            Map<UUID, String> entityNameMap,
            Set<UUID> itemEntityIds,
            Map<UUID, Contents> contentsByLink,
            Map<UUID, List<UUID>> traitIdsByItem,
            Map<UUID, TraitRow> traitById) {
    }

    private Map<String, List<ItemLinkPerspectiveView>> ownAndTraitLinks(ItemRow item, LinkBuildContext ctx) {
        var traitIds = ctx.traitIdsByItem().getOrDefault(item.id(), List.of());
        var ownLinks = buildPerspectiveViews(item.id(), ctx, null);
        var traitLinks = traitIds.stream()
                .map(traitId -> {
                    var trait = ctx.traitById().get(traitId);
                    var definedIn = new DefinedInView(ENTITY_KIND_TRAIT, trait.name());
                    return namespaced(trait.name(), buildPerspectiveViews(trait.id(), ctx, definedIn));
                })
                .filter(m -> m != null && !m.isEmpty())
                .reduce(new LinkedHashMap<>(), (acc, m) -> { acc.putAll(m); return acc; });
        return mergeLinkViews(ownLinks, traitLinks);
    }

    private Map<String, List<ItemLinkPerspectiveView>> effectiveLinks(ItemRow item, Map<UUID, ItemRow> itemById, LinkBuildContext ctx) {
        var own = ownAndTraitLinks(item, ctx);
        var supertype = item.supertypeId() == null ? null : itemById.get(item.supertypeId());
        if (supertype == null) return own;
        var inherited = effectiveLinks(supertype, itemById, ctx);
        return mergeLinkViews(own, retagLinksAsSupertype(inherited, supertype.name()));
    }

    private Map<String, List<ItemLinkPerspectiveView>> retagLinksAsSupertype(
            Map<String, List<ItemLinkPerspectiveView>> links, String supertypeName) {
        if (links == null) return new LinkedHashMap<>();
        var definedIn = new DefinedInView(ENTITY_KIND_SUPERTYPE, supertypeName);
        var result = new LinkedHashMap<String, List<ItemLinkPerspectiveView>>();
        links.forEach((name, list) -> result.put(name, list.stream()
                .map(p -> p.definedIn() == null
                        ? new ItemLinkPerspectiveView(p.targets(), p.description(), p.minCardinality(), p.maxCardinality(), p.properties(), definedIn)
                        : p)
                .toList()));
        return result;
    }

    private Map<String, List<ItemLinkPerspectiveView>> buildPerspectiveViews(UUID entityId, LinkBuildContext ctx, DefinedInView definedIn) {
        var perspectives = ctx.perspectivesByEntity().get(entityId);
        if (perspectives == null || perspectives.isEmpty()) return null;
        return perspectives.stream().map(p -> {
            var inverses = repo.findInversePerspectives(p.linkId(), p.id());
            var targets = inverses.stream()
                    .map(inv -> new TargetEntityView(ctx.entityNameMap().get(inv.entityId()), ctx.itemEntityIds().contains(inv.entityId()) ? "item" : ENTITY_KIND_TRAIT))
                    .toList();
            // The perspective view carries only a link's own properties -- groups a link owns are
            // structural and reach clients through the admin schema.
            var linkContents = ctx.contentsByLink().get(p.linkId());
            var linkPropViews = linkContents == null ? null : toCalculatedProperties(linkContents.properties());
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
