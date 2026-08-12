package org.ntrloc.graph.db.partition.schema;

import org.ntrloc.graph.db.partition.schema.definition.PropertyContainerKind;
import org.ntrloc.graph.db.partition.schema.definition.PropertyType;
import org.ntrloc.graph.db.partition.schema.definition.mutation.CreateItemPropertyDefinitionMutation;
import org.ntrloc.graph.db.partition.schema.definition.mutation.CreateLinkPropertyDefinitionMutation;
import org.ntrloc.graph.db.partition.schema.definition.mutation.CreatePropertyDefinitionMutation;
import org.ntrloc.graph.db.partition.schema.definition.mutation.CreatePropertyPropertyDefinitionMutation;
import org.ntrloc.graph.db.partition.schema.definition.mutation.DefinitionMutation;
import org.ntrloc.graph.db.partition.schema.definition.mutation.DeletePropertyDefinitionMutation;
import org.ntrloc.graph.db.partition.schema.definition.mutation.MovePropertyDefinitionMutation;
import org.ntrloc.graph.db.partition.schema.definition.mutation.UpdatePropertyDefinitionMutation;
import org.ntrloc.graph.db.partition.schema.definition.view.admin.AdminPropertyDefinitionView;
import org.ntrloc.graph.db.partition.schema.repository.SchemaRepository;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

// Applies property-definition mutations -- split out of SchemaManager (see its own history).
@Component
class PropertyMutationApplier {

    private final SchemaRepository repo;

    PropertyMutationApplier(SchemaRepository repo) {
        this.repo = repo;
    }

    boolean apply(DefinitionMutation mutation) {
        if (mutation instanceof CreateItemPropertyDefinitionMutation m) {
            SchemaMutationValidation.requireNameNotAssociated(repo.getPropertiesByItem(), m.itemId(), m.name(), "this item type");
            repo.getAllItems().stream()
                    .filter(item -> item.id().equals(m.itemId()))
                    .findFirst()
                    .map(SchemaRepository.ItemRow::supertypeId)
                    .ifPresent(supertypeId -> SchemaMutationValidation.requireNameNotInSupertypeChain(repo, supertypeId, m.name()));
            var prop = createPropertyRecursive(repo, new CreatePropertyDefinitionMutation(m.name(), m.description(), m.propertyType(), m.cardinality(), m.usage(), m.properties()));
            repo.associateItemProperty(m.itemId(), prop.id());
        } else if (mutation instanceof CreateLinkPropertyDefinitionMutation m) {
            SchemaMutationValidation.requireNameNotAssociated(repo.getPropertiesByLink(), m.linkId(), m.name(), "this link type");
            var prop = createPropertyRecursive(repo, new CreatePropertyDefinitionMutation(m.name(), m.description(), m.propertyType(), m.cardinality(), m.usage(), m.properties()));
            repo.associateLinkProperty(m.linkId(), prop.id());
        } else if (mutation instanceof CreatePropertyPropertyDefinitionMutation m) {
            SchemaMutationValidation.requireObjectTypeProperty(repo, m.parentPropertyId());
            SchemaMutationValidation.requireNameNotAssociated(repo.getPropertiesByProperty(), m.parentPropertyId(), m.name(), "this object property");
            var prop = createPropertyRecursive(repo, new CreatePropertyDefinitionMutation(m.name(), m.description(), m.propertyType(), m.cardinality(), m.usage(), m.properties()));
            repo.associatePropertyProperty(m.parentPropertyId(), prop.id());
        } else if (mutation instanceof UpdatePropertyDefinitionMutation m) {
            repo.updateProperty(m.id(), m.name(), m.description(), m.propertyType(), m.cardinality(), m.usage());
        } else if (mutation instanceof DeletePropertyDefinitionMutation m) {
            repo.deleteProperty(m.id());
        } else if (mutation instanceof MovePropertyDefinitionMutation m) {
            applyMove(m);
        } else {
            return false;
        }
        return true;
    }

    // Data is untouched by a move -- register/ledger values are keyed by property id, never by
    // containment path, so this is purely repointing which schema_*_property join row exists.
    // The property's current container is looked up server-side rather than trusted from the
    // mutation, so a client working off a stale schema can't dissociate the wrong owner.
    private void applyMove(MovePropertyDefinitionMutation m) {
        var property = repo.findProperty(m.propertyId())
                .orElseThrow(() -> new IllegalArgumentException("Unknown property: " + m.propertyId()));
        var currentOwner = repo.findCurrentOwner(m.propertyId())
                .orElseThrow(() -> new IllegalArgumentException("Property " + m.propertyId() + " has no current container"));

        switch (m.targetKind()) {
            case PROPERTY -> {
                SchemaMutationValidation.requireObjectTypeProperty(repo, m.targetId());
                SchemaMutationValidation.requireNoPropertyContainmentCycle(repo, m.propertyId(), m.targetId());
                SchemaMutationValidation.requireNameNotAssociated(repo.getPropertiesByProperty(), m.targetId(), property.name(), "the target object property");
            }
            case ITEM -> SchemaMutationValidation.requireNameNotAssociated(repo.getPropertiesByItem(), m.targetId(), property.name(), "this item type");
            case TRAIT -> SchemaMutationValidation.requireNameNotAssociated(repo.getPropertiesByTrait(), m.targetId(), property.name(), "this trait");
            case LINK -> SchemaMutationValidation.requireNameNotAssociated(repo.getPropertiesByLink(), m.targetId(), property.name(), "this link type");
        }

        dissociate(currentOwner.kind(), currentOwner.ownerId(), m.propertyId());
        associate(m.targetKind(), m.targetId(), m.propertyId());
    }

    private void dissociate(PropertyContainerKind kind, UUID ownerId, UUID propertyId) {
        switch (kind) {
            case ITEM -> repo.dissociateItemProperty(ownerId, propertyId);
            case TRAIT -> repo.dissociateTraitProperty(ownerId, propertyId);
            case LINK -> repo.dissociateLinkProperty(ownerId, propertyId);
            case PROPERTY -> repo.dissociatePropertyProperty(ownerId, propertyId);
        }
    }

    private void associate(PropertyContainerKind kind, UUID ownerId, UUID propertyId) {
        switch (kind) {
            case ITEM -> repo.associateItemProperty(ownerId, propertyId);
            case TRAIT -> repo.associateTraitProperty(ownerId, propertyId);
            case LINK -> repo.associateLinkProperty(ownerId, propertyId);
            case PROPERTY -> repo.associatePropertyProperty(ownerId, propertyId);
        }
    }

    // Recursively creates a property from spec, associating each descendant under its own new
    // parent's real id as soon as that parent exists (never a placeholder/ref id -- the parent is
    // always created first, in the same call, before any of its children). Shared by every place
    // that creates a property: standalone (CreateItemPropertyDefinitionMutation and friends,
    // above) and embedded in a new item/trait/link's initial property list (ItemMutationApplier,
    // TraitMutationApplier, LinkMutationApplier), so an OBJECT property's full subtree can always
    // be created atomically in one mutation, regardless of where in the schema it's being added.
    static AdminPropertyDefinitionView createPropertyRecursive(SchemaRepository repo, CreatePropertyDefinitionMutation spec) {
        if (!spec.properties().isEmpty() && spec.propertyType() != PropertyType.OBJECT) {
            throw new IllegalArgumentException("Property '" + spec.name() + "' is not an OBJECT property and cannot contain other properties");
        }
        var prop = repo.createProperty(spec.name(), spec.description(), spec.propertyType(), spec.cardinality(), spec.usage());
        Set<String> usedNames = new HashSet<>();
        for (var childSpec : spec.properties()) {
            SchemaMutationValidation.requireUniqueName(usedNames, childSpec.name(), "object property '" + spec.name() + "'");
            var child = createPropertyRecursive(repo, childSpec);
            repo.associatePropertyProperty(prop.id(), child.id());
        }
        return prop;
    }
}
