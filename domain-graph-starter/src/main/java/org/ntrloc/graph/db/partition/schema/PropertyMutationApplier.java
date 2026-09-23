package org.ntrloc.graph.db.partition.schema;

import org.ntrloc.graph.db.partition.schema.definition.PropertyContainerKind;
import org.ntrloc.graph.db.partition.schema.definition.mutation.AddPropertyDefinitionMutation;
import org.ntrloc.graph.db.partition.schema.definition.mutation.AddPropertyGroupDefinitionMutation;
import org.ntrloc.graph.db.partition.schema.definition.mutation.CreatePropertyDefinitionMutation;
import org.ntrloc.graph.db.partition.schema.definition.mutation.CreatePropertyGroupDefinitionMutation;
import org.ntrloc.graph.db.partition.schema.definition.mutation.DefinitionMutation;
import org.ntrloc.graph.db.partition.schema.definition.mutation.DeletePropertyDefinitionMutation;
import org.ntrloc.graph.db.partition.schema.definition.mutation.DeletePropertyGroupDefinitionMutation;
import org.ntrloc.graph.db.partition.schema.definition.mutation.MovePropertyDefinitionMutation;
import org.ntrloc.graph.db.partition.schema.definition.mutation.MovePropertyGroupDefinitionMutation;
import org.ntrloc.graph.db.partition.schema.definition.mutation.UpdatePropertyDefinitionMutation;
import org.ntrloc.graph.db.partition.schema.definition.mutation.UpdatePropertyGroupDefinitionMutation;
import org.ntrloc.graph.db.partition.schema.repository.SchemaRepository;
import org.ntrloc.graph.db.partition.schema.repository.SchemaRepository.PropertyOwnerRef;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

// Applies property and property-group definition mutations -- split out of SchemaManager (see its
// own history). Every property and group has exactly one parent (an item type, trait, link type,
// or group); a move is just repointing that parent, and data is untouched because register/ledger
// values are keyed by property id, never by containment path.
@Component
class PropertyMutationApplier {

    private final SchemaRepository repo;

    PropertyMutationApplier(SchemaRepository repo) {
        this.repo = repo;
    }

    boolean apply(DefinitionMutation mutation) {
        if (mutation instanceof AddPropertyDefinitionMutation m) {
            applyAddProperty(m);
        } else if (mutation instanceof UpdatePropertyDefinitionMutation m) {
            SchemaMutationValidation.requireFacetableEligible(m.propertyType(), m.cardinality(), m.facetable());
            var existing = repo.findProperty(m.id())
                    .orElseThrow(() -> new IllegalArgumentException("Unknown property: " + m.id()));
            if (!existing.name().equals(m.name())) {
                var parent = repo.findPropertyParent(m.id())
                        .orElseThrow(() -> new IllegalArgumentException("Property " + m.id() + " has no parent"));
                SchemaMutationValidation.requireNameAvailable(repo, parent, m.name(), "this " + parent.kind().name().toLowerCase());
            }
            repo.updateProperty(m.id(), m.name(), m.description(), m.propertyType(), m.cardinality(), m.usage(), m.facetable());
        } else if (mutation instanceof DeletePropertyDefinitionMutation m) {
            repo.deleteProperty(m.id());
        } else if (mutation instanceof MovePropertyDefinitionMutation m) {
            applyMoveProperty(m);
        } else if (mutation instanceof AddPropertyGroupDefinitionMutation m) {
            applyAddGroup(m);
        } else if (mutation instanceof UpdatePropertyGroupDefinitionMutation m) {
            applyUpdateGroup(m);
        } else if (mutation instanceof DeletePropertyGroupDefinitionMutation m) {
            SchemaMutationValidation.requireGroupEmpty(repo, m.id());
            repo.deleteGroup(m.id());
        } else if (mutation instanceof MovePropertyGroupDefinitionMutation m) {
            applyMoveGroup(m);
        } else {
            return false;
        }
        return true;
    }

    private static String label(PropertyOwnerRef parent) {
        return "this " + parent.kind().name().toLowerCase();
    }

    private void applyAddProperty(AddPropertyDefinitionMutation m) {
        var parent = new PropertyOwnerRef(m.parentKind(), m.parentId());
        SchemaMutationValidation.requireKnownParent(repo, parent);
        SchemaMutationValidation.requireNameAvailable(repo, parent, m.name(), label(parent));
        createPropertyUnder(repo, parent, new CreatePropertyDefinitionMutation(
                m.name(), m.description(), m.propertyType(), m.cardinality(), m.usage(), m.facetable()));
    }

    private void applyAddGroup(AddPropertyGroupDefinitionMutation m) {
        var parent = new PropertyOwnerRef(m.parentKind(), m.parentId());
        SchemaMutationValidation.requireKnownParent(repo, parent);
        SchemaMutationValidation.requireNameAvailable(repo, parent, m.name(), label(parent));
        createGroupRecursive(repo, parent, new CreatePropertyGroupDefinitionMutation(
                m.name(), m.description(), m.properties(), m.groups()));
    }

    private void applyUpdateGroup(UpdatePropertyGroupDefinitionMutation m) {
        var existing = repo.findGroup(m.id())
                .orElseThrow(() -> new IllegalArgumentException("Unknown property group: " + m.id()));
        if (!existing.name().equals(m.name())) {
            SchemaMutationValidation.requireNameAvailable(repo, existing.parent(), m.name(), label(existing.parent()));
        }
        repo.updateGroup(m.id(), m.name(), m.description());
    }

    // The property's current parent is looked up server-side rather than trusted from the mutation,
    // so a client working off a stale schema can't repoint the wrong thing.
    private void applyMoveProperty(MovePropertyDefinitionMutation m) {
        var property = repo.findProperty(m.propertyId())
                .orElseThrow(() -> new IllegalArgumentException("Unknown property: " + m.propertyId()));
        var target = new PropertyOwnerRef(m.targetKind(), m.targetId());
        SchemaMutationValidation.requireKnownParent(repo, target);
        SchemaMutationValidation.requireNameAvailable(repo, target, property.name(), label(target));
        repo.moveProperty(m.propertyId(), target);
    }

    private void applyMoveGroup(MovePropertyGroupDefinitionMutation m) {
        var group = repo.findGroup(m.groupId())
                .orElseThrow(() -> new IllegalArgumentException("Unknown property group: " + m.groupId()));
        var target = new PropertyOwnerRef(m.targetKind(), m.targetId());
        SchemaMutationValidation.requireKnownParent(repo, target);
        if (m.targetKind() == PropertyContainerKind.GROUP) {
            SchemaMutationValidation.requireNoGroupContainmentCycle(repo, m.groupId(), m.targetId());
        }
        SchemaMutationValidation.requireNameAvailable(repo, target, group.name(), label(target));
        repo.moveGroup(m.groupId(), target);
    }

    // Creates one leaf property under an already-existing parent. Name availability is the caller's
    // job (it differs between a standalone add and an initial-contents batch).
    static void createPropertyUnder(SchemaRepository repo, PropertyOwnerRef parent, CreatePropertyDefinitionMutation spec) {
        SchemaMutationValidation.requireFacetableEligible(spec.propertyType(), spec.cardinality(), spec.facetable());
        repo.createProperty(parent, spec.name(), spec.description(), spec.propertyType(), spec.cardinality(), spec.usage(), spec.facetable());
    }

    // Creates the initial contents (properties, then groups) of a container that has just been
    // created and so starts empty. Properties and groups share the one sibling namespace, so both
    // kinds go through the same used-names set; names against a supertype chain / trait names are
    // the item applier's concern and checked before it calls this.
    static void createContents(SchemaRepository repo, PropertyOwnerRef parent, List<CreatePropertyDefinitionMutation> properties,
                               List<CreatePropertyGroupDefinitionMutation> groups, String context) {
        Set<String> usedNames = new HashSet<>();
        for (var p : properties) {
            SchemaMutationValidation.requireUniqueName(usedNames, p.name(), context);
            createPropertyUnder(repo, parent, p);
        }
        for (var g : groups) {
            SchemaMutationValidation.requireUniqueName(usedNames, g.name(), context);
            createGroupRecursive(repo, parent, g);
        }
    }

    // Names declared at the top level of a create payload, for up-front namespace checks.
    static Set<String> topLevelNames(List<CreatePropertyDefinitionMutation> properties, List<CreatePropertyGroupDefinitionMutation> groups) {
        Set<String> names = new HashSet<>();
        properties.forEach(p -> names.add(p.name()));
        groups.forEach(g -> names.add(g.name()));
        return names;
    }

    // Recursively creates a group with its full subtree, each child attached under its parent's real
    // id as soon as the parent exists.
    static UUID createGroupRecursive(SchemaRepository repo, PropertyOwnerRef parent, CreatePropertyGroupDefinitionMutation spec) {
        var group = repo.createGroup(parent, spec.name(), spec.description());
        createContents(repo, new PropertyOwnerRef(PropertyContainerKind.GROUP, group.id()),
                spec.properties(), spec.groups(), "property group '" + spec.name() + "'");
        return group.id();
    }
}
