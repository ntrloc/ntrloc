package org.ntrloc.graph.db.partition.schema;

import org.ntrloc.graph.db.partition.schema.definition.mutation.CreateTraitDefinitionMutation;
import org.ntrloc.graph.db.partition.schema.definition.mutation.DefinitionMutation;
import org.ntrloc.graph.db.partition.schema.definition.mutation.DeleteTraitDefinitionMutation;
import org.ntrloc.graph.db.partition.schema.definition.mutation.ImplementTraitMutation;
import org.ntrloc.graph.db.partition.schema.definition.mutation.RemoveTraitMutation;
import org.ntrloc.graph.db.partition.schema.event.SchemaChangeEvent;
import org.ntrloc.graph.db.partition.schema.repository.SchemaRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.Set;

// Applies trait-definition mutations -- split out of SchemaManager (see its own history).
@Component
class TraitMutationApplier {

    private static final String ENTITY_KIND_TRAIT = "trait";

    private final SchemaRepository repo;
    private final ApplicationEventPublisher eventPublisher;

    TraitMutationApplier(SchemaRepository repo, ApplicationEventPublisher eventPublisher) {
        this.repo = repo;
        this.eventPublisher = eventPublisher;
    }

    boolean apply(DefinitionMutation mutation) {
        if (mutation instanceof CreateTraitDefinitionMutation m) {
            var trait = repo.createTrait(m.name(), m.description());
            Set<String> usedNames = new HashSet<>();
            for (var p : m.properties()) {
                SchemaMutationValidation.requireUniqueName(usedNames, p.name(), ENTITY_KIND_TRAIT + " '" + m.name() + "'");
                var prop = PropertyMutationApplier.createPropertyRecursive(repo, p);
                repo.associateTraitProperty(trait.id(), prop.id());
            }
            eventPublisher.publishEvent(new SchemaChangeEvent.TraitCreated(trait.id()));
        } else if (mutation instanceof DeleteTraitDefinitionMutation m) {
            SchemaMutationValidation.requireTraitNotInUse(repo, m.id());
            repo.deleteTrait(m.id());
            eventPublisher.publishEvent(new SchemaChangeEvent.TraitDeleted(m.id()));
        } else if (mutation instanceof ImplementTraitMutation m) {
            repo.implementTrait(m.itemId(), m.traitId());
        } else if (mutation instanceof RemoveTraitMutation m) {
            repo.removeTrait(m.itemId(), m.traitId());
        } else {
            return false;
        }
        return true;
    }
}
