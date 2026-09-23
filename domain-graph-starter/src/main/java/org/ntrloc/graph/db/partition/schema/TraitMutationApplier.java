package org.ntrloc.graph.db.partition.schema;

import org.ntrloc.graph.db.partition.schema.definition.PropertyContainerKind;
import org.ntrloc.graph.db.partition.schema.definition.mutation.CreateTraitDefinitionMutation;
import org.ntrloc.graph.db.partition.schema.definition.mutation.DefinitionMutation;
import org.ntrloc.graph.db.partition.schema.definition.mutation.DeleteTraitDefinitionMutation;
import org.ntrloc.graph.db.partition.schema.definition.mutation.ImplementTraitMutation;
import org.ntrloc.graph.db.partition.schema.definition.mutation.RemoveTraitMutation;
import org.ntrloc.graph.db.partition.schema.event.SchemaChangeEvent;
import org.ntrloc.graph.db.partition.schema.repository.SchemaRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

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
            PropertyMutationApplier.createContents(repo, new SchemaRepository.PropertyOwnerRef(PropertyContainerKind.TRAIT, trait.id()),
                    m.properties(), m.groups(), ENTITY_KIND_TRAIT + " '" + m.name() + "'");
            eventPublisher.publishEvent(new SchemaChangeEvent.TraitCreated(trait.id()));
        } else if (mutation instanceof DeleteTraitDefinitionMutation m) {
            SchemaMutationValidation.requireTraitNotInUse(repo, m.id());
            repo.deleteTrait(m.id());
            eventPublisher.publishEvent(new SchemaChangeEvent.TraitDeleted(m.id()));
        } else if (mutation instanceof ImplementTraitMutation m) {
            var traitName = repo.getAllTraits().stream()
                    .filter(t -> t.id().equals(m.traitId()))
                    .findFirst()
                    .map(SchemaRepository.TraitRow::name)
                    .orElseThrow(() -> new IllegalArgumentException("Unknown trait: " + m.traitId()));
            SchemaMutationValidation.requireTraitNameAvailable(repo, m.itemId(), traitName);
            repo.implementTrait(m.itemId(), m.traitId());
        } else if (mutation instanceof RemoveTraitMutation m) {
            repo.removeTrait(m.itemId(), m.traitId());
        } else {
            return false;
        }
        return true;
    }
}
