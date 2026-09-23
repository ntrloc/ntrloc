package org.ntrloc.graph.db.partition.schema;

import org.ntrloc.graph.db.partition.schema.SchemaContentResolver.Contents;
import org.ntrloc.graph.db.partition.schema.SchemaContentResolver.OwnerContents;
import org.ntrloc.graph.db.partition.schema.definition.PropertyType;
import org.ntrloc.graph.db.partition.schema.definition.view.DefinedInView;
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
import org.ntrloc.graph.db.partition.schema.repository.SchemaRepository;
import org.ntrloc.graph.db.partition.schema.repository.SchemaRepository.ItemRow;
import org.ntrloc.graph.db.partition.schema.repository.SchemaRepository.StateMachineRow;
import org.ntrloc.graph.db.partition.schema.repository.SchemaRepository.StateRow;
import org.ntrloc.graph.db.partition.schema.repository.SchemaRepository.TraitRow;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

// Builds the admin schema projection (full detail for the schema editor) from SchemaRepository's
// raw rows, via SchemaContentResolver for the content-resolution logic shared with
// SchemaCalculatedViewBuilder. Split out of the single SchemaViewBuilder that used to build both
// projections -- see SchemaContentResolver's own comment for why.
@Component
class SchemaAdminViewBuilder {

    private final SchemaRepository repo;
    private final ControlledListManager controlledListManager;
    private final SchemaContentResolver contentResolver;

    SchemaAdminViewBuilder(SchemaRepository repo, ControlledListManager controlledListManager, SchemaContentResolver contentResolver) {
        this.repo = repo;
        this.controlledListManager = controlledListManager;
        this.contentResolver = contentResolver;
    }

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

        var ownerContents        = contentResolver.loadOwnerContents();
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
            var allContents = contentResolver.effectiveContents(item, itemById, traitIdsByItem, ownerContents, traitById);

            // Own link perspectives + trait-inherited perspectives + supertype chain's effective links
            var allLinks = effectiveLinksAdmin(item, itemById, perspectivesByEntity, entityNameMap, itemEntityIds, traitIdsByItem, traitById);

            // Own state machines + full supertype chain's state machines (additive, no override)
            var stateMachineViews = effectiveStateMachinesAdmin(item, itemById, stateMachinesByItem, statesByStateMachine, transitionsByFromState);

            return new AdminItemDefinitionView(item.id(), item.name(), item.description(), traitRefs, allContents.properties(), allContents.groups(), allLinks,
                    contentResolver.sortableFieldsFor(allContents), stateMachineViews.isEmpty() ? null : stateMachineViews,
                    item.supertypeId(), item.abstractType(), item.displayLabelPattern());
        }).toList();

        var traitViews = traits.stream().map(trait -> {
            var contents = ownerContents.byTrait().getOrDefault(trait.id(), Contents.EMPTY);
            var links = buildPerspectiveAdminViews(trait.id(), perspectivesByEntity, entityNameMap, itemEntityIds, null);
            return new AdminTraitDefinitionView(trait.id(), trait.name(), trait.description(), contents.properties(), contents.groups(), links,
                    contentResolver.sortableFieldsFor(contents));
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

    private Map<String, List<AdminItemLinkPerspectiveView>> ownAndTraitLinksAdmin(
            ItemRow item, Map<UUID, List<SchemaRepository.PerspectiveRow>> perspectivesByEntity,
            Map<UUID, String> entityNameMap, Set<UUID> itemEntityIds,
            Map<UUID, List<UUID>> traitIdsByItem, Map<UUID, TraitRow> traitById) {
        var traitIds = traitIdsByItem.getOrDefault(item.id(), List.of());
        var ownLinks = buildPerspectiveAdminViews(item.id(), perspectivesByEntity, entityNameMap, itemEntityIds, null);
        var traitLinks = traitIds.stream()
                .map(traitId -> {
                    var trait = traitById.get(traitId);
                    var definedIn = new DefinedInView(SchemaContentResolver.ENTITY_KIND_TRAIT, trait.name());
                    return SchemaContentResolver.namespaced(trait.name(),
                            buildPerspectiveAdminViews(trait.id(), perspectivesByEntity, entityNameMap, itemEntityIds, definedIn));
                })
                .filter(m -> m != null && !m.isEmpty())
                .reduce(new LinkedHashMap<>(), (acc, m) -> { acc.putAll(m); return acc; });
        return mergeLinkAdminMaps(ownLinks, traitLinks);
    }

    // Recursive counterpart to SchemaContentResolver.effectiveContents for links. mergeLinkAdminMaps
    // requires its second argument to be a non-null (possibly empty) map -- retagLinksAdminAsSupertype
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
        var definedIn = new DefinedInView(SchemaContentResolver.ENTITY_KIND_SUPERTYPE, supertypeName);
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
                    .map(inv -> new TargetEntityView(entityNameMap.get(inv.entityId()),
                            itemEntityIds.contains(inv.entityId()) ? "item" : SchemaContentResolver.ENTITY_KIND_TRAIT))
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
}
