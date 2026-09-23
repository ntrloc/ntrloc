package org.ntrloc.graph.db.partition.schema;

import org.ntrloc.graph.db.partition.schema.definition.PropertyCardinality;
import org.ntrloc.graph.db.partition.schema.definition.PropertyType;
import org.ntrloc.graph.db.partition.schema.definition.view.DefinedInView;
import org.ntrloc.graph.db.partition.schema.definition.view.SortableFieldView;
import org.ntrloc.graph.db.partition.schema.definition.view.admin.AdminPropertyDefinitionView;
import org.ntrloc.graph.db.partition.schema.definition.view.admin.AdminPropertyGroupView;
import org.ntrloc.graph.db.partition.schema.repository.SchemaRepository;
import org.ntrloc.graph.db.partition.schema.repository.SchemaRepository.ItemRow;
import org.ntrloc.graph.db.partition.schema.repository.SchemaRepository.TraitRow;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

// Raw-content resolution shared by SchemaAdminViewBuilder and SchemaCalculatedViewBuilder -- split
// out of the single SchemaViewBuilder that used to build both projections, once that class grew
// past Sonar's class-coupling limit (S6539) the same way it once absorbed most of SchemaManager's
// own coupling when it was first split out of that class (see this class's siblings' own comments).
// Owns nothing about either view's shape; only how a container's own+inherited properties/groups
// resolve from SchemaRepository's raw rows.
@Component
class SchemaContentResolver {

    static final String ENTITY_KIND_TRAIT = "trait";
    static final String ENTITY_KIND_SUPERTYPE = "supertype";

    private static final List<SortableFieldView> SYSTEM_SORTABLE_FIELDS = List.of(
            new SortableFieldView("itemId",          true),
            new SortableFieldView("itemType",        true),
            new SortableFieldView("createdAt",       true),
            new SortableFieldView("updatedAt",       true),
            new SortableFieldView("visibilityState", true)
    );

    private final SchemaRepository repo;

    SchemaContentResolver(SchemaRepository repo) {
        this.repo = repo;
    }

    List<SortableFieldView> sortableFieldsFor(Contents contents) {
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
    record Contents(List<AdminPropertyDefinitionView> properties, List<AdminPropertyGroupView> groups) {
        static final Contents EMPTY = new Contents(List.of(), List.of());
    }

    // Every owner's own (not inherited) contents. Properties and groups are read from separate
    // tables, each already keyed by its single parent, so this is just stitching them together.
    record OwnerContents(Map<UUID, Contents> byItem, Map<UUID, Contents> byTrait, Map<UUID, Contents> byLink) {}

    OwnerContents loadOwnerContents() {
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

    // An item's own contents (definedIn = null) plus one namespace group per directly-implemented
    // trait -- no supertype involvement, used as the per-level building block for
    // effectiveContents' chain walk. A trait's contributions are addressed under its name
    // everywhere (projections, mutations, filters, sorts), so they are presented the same way here:
    // the synthetic group carries the trait's id and name, and the trait's own properties and groups
    // sit inside it.
    Contents ownAndTraitContents(
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
    Contents effectiveContents(
            ItemRow item, Map<UUID, ItemRow> itemById, Map<UUID, List<UUID>> traitIdsByItem,
            OwnerContents ownerContents, Map<UUID, TraitRow> traitById) {
        var own = ownAndTraitContents(item, traitIdsByItem, ownerContents, traitById);
        var supertype = item.supertypeId() == null ? null : itemById.get(item.supertypeId());
        if (supertype == null) return own;
        var superContents = effectiveContents(supertype, itemById, traitIdsByItem, ownerContents, traitById);
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

    // A trait's link perspectives are addressed under the trait's name on an item, like its
    // properties -- "File.attachments", not "attachments" -- so they can never collide with the item
    // type's own perspectives. Shared here since SchemaAdminViewBuilder and
    // SchemaCalculatedViewBuilder's own parallel link-building walks both need it identically.
    static <V> Map<String, List<V>> namespaced(String traitName, Map<String, List<V>> perspectives) {
        if (perspectives == null) return null;
        var result = new LinkedHashMap<String, List<V>>();
        perspectives.forEach((name, list) -> result.put(traitName + "." + name, list));
        return result;
    }
}
