package org.ntrloc.graph.db.partition.schema;

import org.ntrloc.graph.db.partition.schema.definition.PropertyCardinality;
import org.ntrloc.graph.db.partition.schema.definition.PropertyType;
import org.ntrloc.graph.db.partition.schema.definition.view.admin.AdminPropertyDefinitionView;
import org.ntrloc.graph.db.partition.schema.repository.SchemaRepository;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

// Shared validation helpers for the per-family mutation appliers (ItemMutationApplier,
// TraitMutationApplier, etc.) -- split out alongside them so each applier stays focused on its
// own mutation family without duplicating these checks.
final class SchemaMutationValidation {

    private SchemaMutationValidation() {
    }

    // A property's name only needs to be unique within whichever single item/trait/link type
    // it's associated with -- different types legitimately have their own distinct property
    // row (different id) sharing the same name. These two checks enforce that at the type
    // level, since the DB's schema_property table itself no longer enforces uniqueness (the
    // association is a separate join table, not a column here).
    static void requireUniqueName(Set<String> namesSeenSoFar, String name, String context) {
        if (!namesSeenSoFar.add(name)) {
            throw new IllegalArgumentException("Property '" + name + "' is defined more than once for " + context);
        }
    }

    static void requireNameNotAssociated(Map<UUID, List<AdminPropertyDefinitionView>> propertiesByOwner, UUID ownerId, String name, String context) {
        boolean collision = propertiesByOwner.getOrDefault(ownerId, List.of()).stream()
                .anyMatch(p -> p.name().equals(name));
        if (collision) {
            throw new IllegalArgumentException("Property '" + name + "' already exists on " + context);
        }
    }

    // Despite the parameter name (CreatePerspectiveDefinitionMutation.itemId(), inherited here),
    // a perspective can target either an item type or a trait -- schema_item.id and
    // schema_trait.id are both valid values for schema_entity_link_perspective.entity_id (see
    // that column's own comment in V1_0_0_1__baseline.sql -- it's deliberately polymorphic and has no
    // FK constraint of its own, so this is the only place a bad id gets caught at all). Checking
    // only getAllItems() meant any perspective whose target was actually a trait (e.g. Pack ->
    // PackComponent, where PackComponent is a trait implemented by several item types, not an
    // item type itself) threw "Unknown item" here even though the id was perfectly valid -- just
    // valid for a trait.
    static void requireKnownItemOrTrait(SchemaRepository repo, UUID id) {
        boolean known = repo.getAllItems().stream().anyMatch(item -> item.id().equals(id))
                || repo.getAllTraits().stream().anyMatch(trait -> trait.id().equals(id));
        if (!known) {
            throw new IllegalArgumentException("Unknown item or trait: " + id);
        }
    }

    // The one guardrail schema_entity_link_perspective's now-removed UNIQUE(entity_id,
    // link_definition_id) used to provide as an accidental side effect: a perspective name is
    // only meaningful if it names a single, consistent target across every link definition that
    // uses it for a given entity -- e.g. Person "worksFor" must always mean the same thing,
    // whether it resolves to Company via one link or Department via another, it can't be both.
    // Same-entity, same-name, same-target is fine (that's how self-links and the deliberately
    // ambiguous-link-type test fixtures both work), so this compares target *sets*, not row
    // counts.
    static void requireConsistentPerspectiveTarget(SchemaRepository repo, UUID newLinkId, UUID entityId, String name, Set<UUID> newTargets) {
        for (var existing : repo.findPerspectivesByEntityAndName(entityId, name, newLinkId)) {
            Set<UUID> existingTargets = repo.findInversePerspectives(existing.linkId(), existing.id()).stream()
                    .map(SchemaRepository.PerspectiveRow::entityId)
                    .collect(Collectors.toSet());
            if (!existingTargets.equals(newTargets)) {
                throw new IllegalArgumentException(
                        "Perspective '" + name + "' already targets a different type via another link definition");
            }
        }
    }

    // Deletion must never be allowed to touch already-persisted instance data, even behind a
    // confirmation -- ntrloc's data is append-only/immutable by design. "Not in use" is therefore
    // a hard, symmetric gate for both traits and item types, not a warn-and-proceed check: if
    // either is in use, the fix is for the admin to remove the in-use references first (unassign
    // the trait, delete the items), never for the deletion itself to cascade into that data.
    static void requireTraitNotInUse(SchemaRepository repo, UUID traitId) {
        if (repo.isTraitInUse(traitId)) {
            String name = repo.getAllTraits().stream()
                    .filter(t -> t.id().equals(traitId))
                    .findFirst()
                    .map(SchemaRepository.TraitRow::name)
                    .orElse(traitId.toString());
            throw new IllegalArgumentException(
                    "Cannot delete trait '" + name + "' because it is still implemented by an item type or referenced by a link perspective");
        }
    }

    static void requireItemTypeNotInUse(SchemaRepository repo, UUID itemTypeId) {
        if (repo.isItemTypeInUse(itemTypeId)) {
            String name = repo.getAllItems().stream()
                    .filter(i -> i.id().equals(itemTypeId))
                    .findFirst()
                    .map(SchemaRepository.ItemRow::name)
                    .orElse(itemTypeId.toString());
            throw new IllegalArgumentException(
                    "Cannot delete item type '" + name + "' because items of this type still exist");
        }
    }

    // A supertype must be a concrete item type -- unlike a link perspective's polymorphic
    // target (see requireKnownItemOrTrait), "is-a" identity doesn't make sense against a trait,
    // which is a horizontal capability with no identity claim of its own.
    static void requireKnownItem(SchemaRepository repo, UUID id) {
        boolean known = repo.getAllItems().stream().anyMatch(item -> item.id().equals(id));
        if (!known) {
            throw new IllegalArgumentException("Unknown item type: " + id);
        }
    }

    // Inheritance is strictly additive -- no property override, ever -- so a name already owned
    // by any ancestor in the supertype chain can never be re-declared lower down; it would just
    // create a second, distinct property row silently coexisting with the inherited one, not an
    // override. Walks up from startSupertypeId (the item's own supertype for a property-add, or
    // the proposed new supertype for a re-parent) checking each ancestor's own directly-associated
    // properties (repo.getPropertiesByItem() -- trait-contributed names aren't included here; that
    // own-vs-trait collision is the separate, already-documented pre-existing gap).
    static void requireNameNotInSupertypeChain(SchemaRepository repo, UUID startSupertypeId, String name) {
        Map<UUID, UUID> supertypeById = repo.getAllItems().stream()
                .filter(item -> item.supertypeId() != null)
                .collect(Collectors.toMap(SchemaRepository.ItemRow::id, SchemaRepository.ItemRow::supertypeId));
        var propertiesByItem = repo.getPropertiesByItem();
        UUID current = startSupertypeId;
        while (current != null) {
            UUID ancestorId = current;
            boolean collision = propertiesByItem.getOrDefault(ancestorId, List.of()).stream()
                    .anyMatch(p -> p.name().equals(name));
            if (collision) {
                String ancestorName = repo.getAllItems().stream()
                        .filter(i -> i.id().equals(ancestorId))
                        .findFirst()
                        .map(SchemaRepository.ItemRow::name)
                        .orElse(ancestorId.toString());
                throw new IllegalArgumentException(
                        "Property '" + name + "' is already defined on supertype '" + ancestorName + "'");
            }
            current = supertypeById.get(current);
        }
    }

    // Item types form a single-parent tree, not a DAG -- walk proposedSupertypeId's own ancestor
    // chain and reject if itemId would appear in it (including the trivial one-node cycle where
    // proposedSupertypeId equals itemId itself, an item can't be its own supertype).
    static void requireNoSupertypeCycle(SchemaRepository repo, UUID itemId, UUID proposedSupertypeId) {
        // Collectors.toMap uses Map.merge internally and throws on a null value, so items with no
        // supertype (the common case) are filtered out rather than mapped to null -- an absent key
        // and a key mapped to null behave identically for this walk (get() returns null either way).
        Map<UUID, UUID> supertypeById = repo.getAllItems().stream()
                .filter(item -> item.supertypeId() != null)
                .collect(Collectors.toMap(SchemaRepository.ItemRow::id, SchemaRepository.ItemRow::supertypeId));
        UUID current = proposedSupertypeId;
        while (current != null) {
            if (current.equals(itemId)) {
                throw new IllegalArgumentException("Cannot set supertype: would create a cycle");
            }
            current = supertypeById.get(current);
        }
    }

    // Object properties nest via schema_property_property, the same single-parent-tree shape as
    // the supertype chain above -- same cycle risk, same walk. Only relevant when the *new*
    // container of a moved property is itself a property (nesting into an item/trait/link can
    // never cycle, since those aren't part of the property containment tree).
    static void requireNoPropertyContainmentCycle(SchemaRepository repo, UUID propertyId, UUID proposedParentPropertyId) {
        Map<UUID, UUID> parentByProperty = repo.getParentPropertyIdByProperty();
        UUID current = proposedParentPropertyId;
        while (current != null) {
            if (current.equals(propertyId)) {
                throw new IllegalArgumentException("Cannot move property: would create a containment cycle");
            }
            current = parentByProperty.get(current);
        }
    }

    // Facetable is an admin-controlled opt-in (RegisterPartitionManager.isTermsFacetable), but
    // only ever meaningful on top of real structural eligibility -- SINGLE cardinality, and
    // either BOOLEAN or a type that can carry a controlled list (STRING/INT/LONG -- the same set
    // the admin UI itself offers a controlled list for, see ntrloc-property-table.js's own
    // CONTROLLED_LIST_TYPES). Deliberately does NOT also require a controlled list already be
    // attached: an admin drafting a brand-new property has no property id yet to attach one to
    // (that's a separate call, after this one returns), so "facetable checked, list not attached
    // yet" is a normal, transient step in the real workflow, not an invalid state -- it just means
    // isTermsFacetable won't actually treat it as facetable until the list catches up. What this
    // rejects is only the combinations that could never be valid regardless of sequencing:
    // LIST/SET cardinality, or a type that can never carry a controlled list or be BOOLEAN
    // (DATE/DATETIME/DOUBLE/BINARY/OBJECT).
    private static final Set<PropertyType> FACETABLE_ELIGIBLE_TYPES =
            Set.of(PropertyType.BOOLEAN, PropertyType.STRING, PropertyType.INT, PropertyType.LONG);

    static void requireFacetableEligible(PropertyType type, PropertyCardinality cardinality, boolean facetable) {
        if (!facetable) return;
        if (cardinality != PropertyCardinality.SINGLE || !FACETABLE_ELIGIBLE_TYPES.contains(type)) {
            throw new IllegalArgumentException(
                    "Only a SINGLE-cardinality property backed by a controlled list (STRING/INT/LONG), or a BOOLEAN, can be marked facetable");
        }
    }

    // A property can only contain children if it's itself OBJECT-typed -- nesting inside a
    // scalar property has no meaning, there's nowhere for the structure to go.
    static void requireObjectTypeProperty(SchemaRepository repo, UUID propertyId) {
        PropertyType type = repo.findProperty(propertyId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown property: " + propertyId))
                .type();
        if (type != PropertyType.OBJECT) {
            throw new IllegalArgumentException("Property " + propertyId + " is not an OBJECT property and cannot contain other properties");
        }
    }
}
