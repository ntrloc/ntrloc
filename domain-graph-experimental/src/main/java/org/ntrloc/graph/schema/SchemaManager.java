package org.ntrloc.graph.schema;

import org.ntrloc.graph.domain.DomainInitializer;
import org.ntrloc.graph.schema.AllowedValue;
import org.ntrloc.graph.schema.ControlledListManager;
import org.ntrloc.graph.schema.definition.PropertyType;
import org.ntrloc.graph.schema.definition.mutation.AssignItemPropertyGroupMutation;
import org.ntrloc.graph.schema.definition.mutation.CreateItemDefinitionMutation;
import org.ntrloc.graph.schema.definition.mutation.CreateItemPropertyDefinitionMutation;
import org.ntrloc.graph.schema.definition.mutation.CreateLinkPropertyDefinitionMutation;
import org.ntrloc.graph.schema.definition.mutation.CreatePropertyGroupMutation;
import org.ntrloc.graph.schema.definition.mutation.CreateTraitDefinitionMutation;
import org.ntrloc.graph.schema.definition.mutation.DeletePropertyDefinitionMutation;
import org.ntrloc.graph.schema.definition.mutation.DeletePropertyGroupMutation;
import org.ntrloc.graph.schema.definition.mutation.ImplementTraitMutation;
import org.ntrloc.graph.schema.definition.mutation.RemoveTraitMutation;
import org.ntrloc.graph.schema.definition.mutation.DefinitionMutation;
import org.ntrloc.graph.schema.definition.mutation.ReplaceControlledListMutation;
import org.ntrloc.graph.schema.definition.mutation.UpdateItemDefinitionMutation;
import org.ntrloc.graph.schema.definition.mutation.UpdatePerspectiveDefinitionMutation;
import org.ntrloc.graph.schema.definition.mutation.UpdatePropertyDefinitionMutation;
import org.ntrloc.graph.schema.definition.mutation.UpdatePropertyGroupMutation;
import org.ntrloc.graph.schema.definition.view.DefinedInView;
import org.ntrloc.graph.schema.definition.view.SortableFieldView;
import org.ntrloc.graph.schema.definition.view.TargetEntityView;
import org.ntrloc.graph.schema.definition.view.admin.AdminItemDefinitionView;
import org.ntrloc.graph.schema.definition.view.admin.AdminItemLinkPerspectiveView;
import org.ntrloc.graph.schema.definition.view.admin.AdminLinkView;
import org.ntrloc.graph.schema.definition.view.admin.AdminPropertyDefinitionView;
import org.ntrloc.graph.schema.definition.view.admin.AdminPropertyGroupView;
import org.ntrloc.graph.schema.definition.view.admin.AdminSchemaView;
import org.ntrloc.graph.schema.definition.view.admin.AdminTraitDefinitionView;
import org.ntrloc.graph.schema.definition.view.admin.PropertyTypeView;
import org.ntrloc.graph.schema.definition.view.admin.TraitRefView;
import org.ntrloc.graph.schema.definition.view.calculated.ItemDefinitionView;
import org.ntrloc.graph.schema.definition.view.calculated.ItemLinkPerspectiveView;
import org.ntrloc.graph.schema.definition.view.calculated.PropertyDefinitionView;
import org.ntrloc.graph.schema.definition.view.calculated.SchemaView;
import org.ntrloc.graph.schema.definition.view.calculated.TraitDefinitionView;
import org.ntrloc.graph.schema.repository.SchemaRepository;
import org.ntrloc.graph.schema.repository.SchemaRepository.ItemRow;
import org.ntrloc.graph.schema.repository.SchemaRepository.TraitRow;
import org.springframework.context.annotation.DependsOn;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
@DependsOn("schemaInitializer")
public class SchemaManager {

    private final SchemaRepository repo;
    private final ControlledListManager controlledListManager;

    private AdminSchemaView cachedAdminSchema;
    private SchemaView cachedSchema;

    public SchemaManager(SchemaRepository repo, ControlledListManager controlledListManager, Optional<DomainInitializer> domainInitializer) {
        this.repo = repo;
        this.controlledListManager = controlledListManager;
        domainInitializer.ifPresent(d -> d.initSchema(repo, controlledListManager));
        rebuildCache();
    }

    private void rebuildCache() {
        cachedAdminSchema = buildAdminSchema();
        cachedSchema = buildSchema();
    }

    public void applyMutations(List<DefinitionMutation> mutations) {
        for (DefinitionMutation mutation : mutations) {
            switch (mutation) {
                case CreateItemDefinitionMutation m -> {
                    var item = repo.createItem(m.name(), m.description());
                    for (var p : m.properties()) {
                        var prop = repo.createProperty(p.name(), p.description(), p.propertyType(), p.cardinality(), p.usage());
                        repo.associateItemProperty(item.id(), prop.id());
                    }
                }
                case CreateTraitDefinitionMutation m -> {
                    var trait = repo.createTrait(m.name(), m.description());
                    for (var p : m.properties()) {
                        var prop = repo.createProperty(p.name(), p.description(), p.propertyType(), p.cardinality(), p.usage());
                        repo.associateTraitProperty(trait.id(), prop.id());
                    }
                }
                case ImplementTraitMutation m ->
                        repo.implementTrait(m.itemId(), m.traitId());
                case RemoveTraitMutation m ->
                        repo.removeTrait(m.itemId(), m.traitId());
                case UpdateItemDefinitionMutation m ->
                        repo.updateItem(m.id(), m.name(), m.description());
                case CreateItemPropertyDefinitionMutation m -> {
                    var prop = repo.createProperty(m.name(), m.description(), m.propertyType(), m.cardinality(), m.usage());
                    repo.associateItemProperty(m.itemId(), prop.id());
                }
                case CreateLinkPropertyDefinitionMutation m -> {
                    var prop = repo.createProperty(m.name(), m.description(), m.propertyType(), m.cardinality(), m.usage());
                    repo.associateLinkProperty(m.linkId(), prop.id());
                }
                case UpdatePropertyDefinitionMutation m ->
                        repo.updateProperty(m.id(), m.name(), m.description(), m.propertyType(), m.cardinality(), m.usage());
                case DeletePropertyDefinitionMutation m ->
                        repo.deleteProperty(m.id());
                case UpdatePerspectiveDefinitionMutation m ->
                        repo.updatePerspective(m.id(), m.name(), m.description(), m.minCardinality(), m.maxCardinality());
                case ReplaceControlledListMutation m -> {
                    var list = controlledListManager.getListForProperty(m.propertyId())
                            .orElseThrow(() -> new IllegalArgumentException("No controlled list for property: " + m.propertyId()));
                    controlledListManager.replaceValues(list.id(), list.valueType(), m.values());
                }
                case CreatePropertyGroupMutation m ->
                        repo.createGroup(m.entityId(), m.name());
                case UpdatePropertyGroupMutation m ->
                        repo.updateGroup(m.id(), m.name());
                case DeletePropertyGroupMutation m ->
                        repo.deleteGroup(m.id());
                case AssignItemPropertyGroupMutation m ->
                        repo.setItemPropertyGroup(m.itemId(), m.propertyId(), m.groupId());
                default ->
                        throw new IllegalArgumentException("Unsupported mutation: " + mutation.getClass().getSimpleName());
            }
        }
        rebuildCache();
    }

    public AdminSchemaView getAdminSchema() {
        return cachedAdminSchema;
    }

    public SchemaView getSchema() {
        return cachedSchema;
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

        // entity_id → name (for resolving perspective targets)
        Map<UUID, String> entityNameMap = new HashMap<>();
        items.forEach(i -> entityNameMap.put(i.entityId(), i.name()));
        traits.forEach(t -> entityNameMap.put(t.entityId(), t.name()));

        // item entity IDs — used to determine targetKind on link perspectives
        Set<UUID> itemEntityIds = items.stream()
                .map(ItemRow::entityId)
                .collect(Collectors.toSet());

        var propertiesByItem     = repo.getPropertiesByItem();
        var propertiesByTrait    = repo.getPropertiesByTrait();
        var propertiesByLink     = repo.getPropertiesByLink();
        var perspectivesByEntity = repo.getPerspectivesByEntity();
        var traitIdsByItem       = repo.getTraitIdsByItem();
        var groupsByEntity       = repo.getGroupsByEntity();
        var itemPropertyGroups   = repo.getItemPropertyGroupAssignments();

        Map<UUID, TraitRow> traitById = traits.stream()
                .collect(Collectors.toMap(TraitRow::id, t -> t));

        var itemViews = items.stream().map(item -> {
            var traitIds = traitIdsByItem.getOrDefault(item.id(), List.of());
            var traitRefs = traitIds.stream().map(id -> new TraitRefView(traitById.get(id).id(), traitById.get(id).name())).toList();

            // Group assignments for this item (covers own and trait-inherited properties)
            var groupAssignments = itemPropertyGroups.getOrDefault(item.id(), Map.of());

            // Own properties (definedIn = null) + trait-inherited properties, both with item-level group assignment applied
            var ownProps = propertiesByItem.getOrDefault(item.id(), List.of()).stream()
                    .map(p -> groupAssignments.containsKey(p.id())
                            ? new AdminPropertyDefinitionView(p.id(), p.name(), p.description(), p.type(), p.cardinality(), p.usage(), null, p.controlledListId(), groupAssignments.get(p.id()))
                            : p)
                    .toList();
            var traitProps = traitIds.stream()
                    .flatMap(traitId -> {
                        var trait = traitById.get(traitId);
                        var definedIn = new DefinedInView("trait", trait.name());
                        return propertiesByTrait.getOrDefault(traitId, List.of()).stream()
                                .map(p -> new AdminPropertyDefinitionView(
                                        p.id(), p.name(), p.description(), p.type(), p.cardinality(), p.usage(), definedIn, p.controlledListId(), groupAssignments.get(p.id())));
                    })
                    .toList();
            var allProps = Stream.concat(ownProps.stream(), traitProps.stream()).toList();

            // Own link perspectives (definedIn = null) + trait-inherited perspectives
            var ownLinks = buildPerspectiveAdminViews(item.entityId(), perspectivesByEntity, entityNameMap, itemEntityIds, null);
            var traitLinks = traitIds.stream()
                    .map(traitId -> {
                        var trait = traitById.get(traitId);
                        var definedIn = new DefinedInView("trait", trait.name());
                        return buildPerspectiveAdminViews(trait.entityId(), perspectivesByEntity, entityNameMap, itemEntityIds, definedIn);
                    })
                    .filter(m -> m != null && !m.isEmpty())
                    .reduce(new LinkedHashMap<>(), (acc, m) -> { acc.putAll(m); return acc; });

            var allLinks = mergeLinkAdminMaps(ownLinks, traitLinks);
            var groups = groupsByEntity.getOrDefault(item.entityId(), List.of()).stream()
                    .map(g -> new AdminPropertyGroupView(g.id(), g.name()))
                    .toList();
            return new AdminItemDefinitionView(item.id(), item.entityId(), item.name(), item.description(), traitRefs, allProps, allLinks, sortableFieldsFor(allProps), groups);
        }).toList();

        var traitViews = traits.stream().map(trait -> {
            var props = propertiesByTrait.getOrDefault(trait.id(), List.of());
            var links = buildPerspectiveAdminViews(trait.entityId(), perspectivesByEntity, entityNameMap, itemEntityIds, null);
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
        items.forEach(i -> entityNameMap.put(i.entityId(), i.name()));
        traits.forEach(t -> entityNameMap.put(t.entityId(), t.name()));

        Set<UUID> itemEntityIds = items.stream()
                .map(ItemRow::entityId)
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

            var ownLinks = buildPerspectiveViews(item.entityId(), perspectivesByEntity, entityNameMap, itemEntityIds, propertiesByLink, null);
            var traitLinks = traitIds.stream()
                    .map(traitId -> {
                        var trait = traitById.get(traitId);
                        var definedIn = new DefinedInView("trait", trait.name());
                        return buildPerspectiveViews(trait.entityId(), perspectivesByEntity, entityNameMap, itemEntityIds, propertiesByLink, definedIn);
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
            var links = buildPerspectiveViews(trait.entityId(), perspectivesByEntity, entityNameMap, itemEntityIds, propertiesByLink, null);
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
