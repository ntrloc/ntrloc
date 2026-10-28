package org.ntrloc.graph.db.partition.schema;

import org.ntrloc.graph.db.partition.schema.definition.PropertyContainerKind;
import org.ntrloc.graph.db.partition.schema.definition.mutation.CreateTraitDefinitionMutation;
import org.ntrloc.graph.db.partition.schema.definition.mutation.DefinitionMutation;
import org.ntrloc.graph.db.partition.schema.definition.mutation.DeleteTraitDefinitionMutation;
import org.ntrloc.graph.db.partition.schema.definition.mutation.ExpectTraitDefinitionMutation;
import org.ntrloc.graph.db.partition.schema.definition.mutation.ImplementTraitMutation;
import org.ntrloc.graph.db.partition.schema.definition.mutation.RemoveTraitMutation;
import org.ntrloc.graph.db.partition.schema.definition.mutation.UpdateTraitDefinitionMutation;
import org.ntrloc.graph.db.partition.schema.event.SchemaChangeEvent;
import org.ntrloc.graph.db.partition.schema.repository.SchemaRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;

// Applies trait-definition mutations -- split out of SchemaManager (see its own history).
@Component
class TraitMutationApplier {

    private static final String ENTITY_KIND_TRAIT = "trait";

    private final SchemaRepository repo;
    private final ApplicationEventPublisher eventPublisher;
    private final PropertyMutationApplier propertyMutationApplier;
    private final LinkMutationApplier linkMutationApplier;

    TraitMutationApplier(SchemaRepository repo, ApplicationEventPublisher eventPublisher, PropertyMutationApplier propertyMutationApplier,
                        LinkMutationApplier linkMutationApplier) {
        this.repo = repo;
        this.eventPublisher = eventPublisher;
        this.propertyMutationApplier = propertyMutationApplier;
        this.linkMutationApplier = linkMutationApplier;
    }

    // refIdToEntityId: a created trait's id, by its refId, for later mutations in the batch.
    boolean apply(DefinitionMutation mutation, Map<String, UUID> refIdToEntityId) {
        switch (mutation) {
            case CreateTraitDefinitionMutation m -> {
                var trait = repo.createTrait(m.name(), m.description());
                propertyMutationApplier.createContents(new SchemaRepository.PropertyOwnerRef(PropertyContainerKind.TRAIT, trait.id()),
                        m.properties(), m.groups(), ENTITY_KIND_TRAIT + " '" + m.name() + "'");
                if (m.refId() != null) {
                    refIdToEntityId.put(m.refId(), trait.id());
                }
                eventPublisher.publishEvent(new SchemaChangeEvent.TraitCreated(trait.id()));
            }
            case UpdateTraitDefinitionMutation m -> repo.updateTrait(m.id(), m.name(), m.description());
            case ExpectTraitDefinitionMutation m -> requireUnchanged(m);
            case DeleteTraitDefinitionMutation m -> {
                SchemaMutationValidation.requireTraitNotInUse(repo, m.id());
                linkMutationApplier.deleteLinksTouching(m.id());
                repo.deleteTrait(m.id());
                eventPublisher.publishEvent(new SchemaChangeEvent.TraitDeleted(m.id()));
            }
            case ImplementTraitMutation m -> {
                if (repo.getAllTraits().stream().noneMatch(t -> t.id().equals(m.traitId()))) {
                    throw new IllegalArgumentException("Unknown trait: " + m.traitId());
                }
                SchemaMutationValidation.requireTraitMembersAvailable(repo, m.itemId(), m.traitId());
                repo.implementTrait(m.itemId(), m.traitId());
            }
            case RemoveTraitMutation m -> repo.removeTrait(m.itemId(), m.traitId());
            default -> {
                return false;
            }
        }
        return true;
    }

    private void requireUnchanged(ExpectTraitDefinitionMutation m) {
        var row = repo.lockTrait(m.id())
                .orElseThrow(() -> new SchemaConflictException("This trait was deleted by someone else."));
        boolean unchanged = row.name().equals(m.name()) && Objects.equals(blankToNull(row.description()), blankToNull(m.description()));
        if (!unchanged) {
            throw new SchemaConflictException("Trait " + row.name() + " was changed by someone else while you were editing it.");
        }
    }

    private static String blankToNull(String text) {
        return text == null || text.isBlank() ? null : text;
    }
}
