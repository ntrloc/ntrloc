package org.ntrloc.graph.db.partition.schema;

import org.ntrloc.graph.db.partition.schema.definition.PropertyType;
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
import org.ntrloc.graph.db.partition.schema.definition.view.calculated.ItemDefinitionView;
import org.ntrloc.graph.db.partition.schema.definition.view.calculated.ItemLinkPerspectiveView;
import org.ntrloc.graph.db.partition.schema.definition.view.calculated.PropertyDefinitionView;
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
                        var definedIn = new DefinedInView(ENTITY_KIND_TRAIT, trait.name());
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
                        var definedIn = new DefinedInView(ENTITY_KIND_TRAIT, trait.name());
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
                    .map(inv -> new TargetEntityView(entityNameMap.get(inv.entityId()), itemEntityIds.contains(inv.entityId()) ? "item" : ENTITY_KIND_TRAIT))
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

    SchemaView buildSchema() {
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
                        var definedIn = new DefinedInView(ENTITY_KIND_TRAIT, trait.name());
                        return propertiesByTrait.getOrDefault(traitId, List.of()).stream()
                                .map(p -> new PropertyDefinitionView(p.id(), p.name(), p.description(), p.type(), p.cardinality(), definedIn, allowedValuesFor(p)));
                    })
                    .toList();
            var allProps = Stream.concat(ownProps.stream(), traitProps.stream()).toList();

            var ownLinks = buildPerspectiveViews(item.id(), perspectivesByEntity, entityNameMap, itemEntityIds, propertiesByLink, null);
            var traitLinks = traitIds.stream()
                    .map(traitId -> {
                        var trait = traitById.get(traitId);
                        var definedIn = new DefinedInView(ENTITY_KIND_TRAIT, trait.name());
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
                    .map(inv -> new TargetEntityView(entityNameMap.get(inv.entityId()), itemEntityIds.contains(inv.entityId()) ? "item" : ENTITY_KIND_TRAIT))
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
