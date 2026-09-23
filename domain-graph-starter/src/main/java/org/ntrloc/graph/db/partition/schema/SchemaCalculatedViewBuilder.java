package org.ntrloc.graph.db.partition.schema;

import org.ntrloc.graph.db.partition.schema.SchemaContentResolver.Contents;
import org.ntrloc.graph.db.partition.schema.definition.view.DefinedInView;
import org.ntrloc.graph.db.partition.schema.definition.view.TargetEntityView;
import org.ntrloc.graph.db.partition.schema.definition.view.admin.AdminPropertyDefinitionView;
import org.ntrloc.graph.db.partition.schema.definition.view.admin.AdminPropertyGroupView;
import org.ntrloc.graph.db.partition.schema.definition.view.calculated.ItemDefinitionView;
import org.ntrloc.graph.db.partition.schema.definition.view.calculated.ItemLinkPerspectiveView;
import org.ntrloc.graph.db.partition.schema.definition.view.calculated.PropertyDefinitionView;
import org.ntrloc.graph.db.partition.schema.definition.view.calculated.PropertyGroupDefinitionView;
import org.ntrloc.graph.db.partition.schema.definition.view.calculated.SchemaView;
import org.ntrloc.graph.db.partition.schema.definition.view.calculated.TraitDefinitionView;
import org.ntrloc.graph.db.partition.schema.repository.SchemaRepository;
import org.ntrloc.graph.db.partition.schema.repository.SchemaRepository.ItemRow;
import org.ntrloc.graph.db.partition.schema.repository.SchemaRepository.TraitRow;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

// Builds the calculated (client-facing) schema projection that SchemaManager caches and serves,
// from SchemaRepository's raw rows, via SchemaContentResolver for the content-resolution logic
// shared with SchemaAdminViewBuilder. Split out of the single SchemaViewBuilder that used to build
// both projections -- see SchemaContentResolver's own comment for why.
@Component
class SchemaCalculatedViewBuilder {

    private final SchemaRepository repo;
    private final ControlledListManager controlledListManager;
    private final SchemaContentResolver contentResolver;

    SchemaCalculatedViewBuilder(SchemaRepository repo, ControlledListManager controlledListManager, SchemaContentResolver contentResolver) {
        this.repo = repo;
        this.controlledListManager = controlledListManager;
        this.contentResolver = contentResolver;
    }

    SchemaView buildSchema() {
        var items  = repo.getAllItems();
        var traits = repo.getAllTraits();

        Map<UUID, String> entityNameMap = new HashMap<>();
        items.forEach(i -> entityNameMap.put(i.id(), i.name()));
        traits.forEach(t -> entityNameMap.put(t.id(), t.name()));

        Set<UUID> itemEntityIds = items.stream()
                .map(ItemRow::id)
                .collect(Collectors.toSet());

        var ownerContents = contentResolver.loadOwnerContents();
        var perspectivesByEntity = repo.getPerspectivesByEntity();
        var traitIdsByItem    = repo.getTraitIdsByItem();

        Map<UUID, TraitRow> traitById = traits.stream()
                .collect(Collectors.toMap(TraitRow::id, t -> t));
        Map<UUID, ItemRow> itemById = items.stream()
                .collect(Collectors.toMap(ItemRow::id, i -> i));

        var linkBuildContext = new LinkBuildContext(perspectivesByEntity, entityNameMap, itemEntityIds, ownerContents.byLink(), traitIdsByItem, traitById);

        var itemViews = items.stream().map(item -> {
            var contents = contentResolver.effectiveContents(item, itemById, traitIdsByItem, ownerContents, traitById);
            var allLinks = effectiveLinks(item, itemById, linkBuildContext);

            return new ItemDefinitionView(item.id(), item.name(), item.description(),
                    toCalculatedProperties(contents.properties()), toCalculatedGroups(contents.groups()), allLinks,
                    contentResolver.sortableFieldsFor(contents), item.supertypeId(), item.abstractType());
        }).toList();

        var traitViews = traits.stream().map(trait -> {
            var contents = ownerContents.byTrait().getOrDefault(trait.id(), Contents.EMPTY);
            var links = buildPerspectiveViews(trait.id(), linkBuildContext, null);
            return new TraitDefinitionView(trait.id(), trait.name(), trait.description(),
                    toCalculatedProperties(contents.properties()), toCalculatedGroups(contents.groups()), links,
                    contentResolver.sortableFieldsFor(contents));
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
                    var definedIn = new DefinedInView(SchemaContentResolver.ENTITY_KIND_TRAIT, trait.name());
                    return SchemaContentResolver.namespaced(trait.name(), buildPerspectiveViews(trait.id(), ctx, definedIn));
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
        var definedIn = new DefinedInView(SchemaContentResolver.ENTITY_KIND_SUPERTYPE, supertypeName);
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
                    .map(inv -> new TargetEntityView(ctx.entityNameMap().get(inv.entityId()),
                            ctx.itemEntityIds().contains(inv.entityId()) ? "item" : SchemaContentResolver.ENTITY_KIND_TRAIT))
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
