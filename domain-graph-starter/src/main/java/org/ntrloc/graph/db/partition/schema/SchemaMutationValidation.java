package org.ntrloc.graph.db.partition.schema;

import org.ntrloc.graph.db.partition.schema.definition.view.admin.AdminPropertyDefinitionView;
import org.ntrloc.graph.db.partition.schema.repository.SchemaRepository;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

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
    // that column's own comment in SchemaInitializer -- it's deliberately polymorphic and has no
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
}
