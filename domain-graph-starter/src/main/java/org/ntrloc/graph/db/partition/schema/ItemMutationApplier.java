package org.ntrloc.graph.db.partition.schema;

import org.ntrloc.graph.db.partition.schema.definition.PropertyContainerKind;
import org.ntrloc.graph.db.partition.schema.definition.mutation.CreateItemDefinitionMutation;
import org.ntrloc.graph.db.partition.schema.definition.mutation.DefinitionMutation;
import org.ntrloc.graph.db.partition.schema.definition.mutation.DeleteItemDefinitionMutation;
import org.ntrloc.graph.db.partition.schema.definition.mutation.ExpectItemDefinitionMutation;
import org.ntrloc.graph.db.partition.schema.definition.mutation.UpdateItemDefinitionMutation;
import org.ntrloc.graph.db.partition.schema.event.SchemaChangeEvent;
import org.ntrloc.graph.db.partition.schema.repository.SchemaRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

// Applies item-definition mutations -- split out of SchemaManager (see its own history) to keep
// each mutation-family applier's own dependency footprint small.
@Component
class ItemMutationApplier {

    private final SchemaRepository repo;
    private final ApplicationEventPublisher eventPublisher;
    private final PropertyMutationApplier propertyMutationApplier;
    private final LinkMutationApplier linkMutationApplier;

    ItemMutationApplier(SchemaRepository repo, ApplicationEventPublisher eventPublisher, PropertyMutationApplier propertyMutationApplier,
                        LinkMutationApplier linkMutationApplier) {
        this.repo = repo;
        this.eventPublisher = eventPublisher;
        this.propertyMutationApplier = propertyMutationApplier;
        this.linkMutationApplier = linkMutationApplier;
    }

    // A flat dispatch by design -- each branch used to carry its own nested loop/conditional logic
    // inline, which pushed this method's own Cognitive Complexity to 17 (Sonar's S3776, limit 15):
    // every nested control-flow structure inside an instanceof branch was penalized twice, once
    // for its own nesting and once for sitting inside the branch's own nesting level. Delegating to
    // a private method per mutation type removes that outer nesting layer -- each handler's own
    // complexity is far lower in isolation (worst case 4), and this method is now just a sequence
    // of flat, unnested checks.
    // refIdToEntityId: a created item type's (or trait's) id, by its refId, for later mutations in the batch.
    boolean apply(DefinitionMutation mutation, Map<String, UUID> refIdToEntityId) {
        switch (mutation) {
            case CreateItemDefinitionMutation m -> applyCreate(m, refIdToEntityId);
            case UpdateItemDefinitionMutation m -> applyUpdate(m);
            case DeleteItemDefinitionMutation m -> applyDelete(m);
            case ExpectItemDefinitionMutation m -> applyExpect(m);
            default -> {
                return false;
            }
        }
        return true;
    }

    private void applyCreate(CreateItemDefinitionMutation m, Map<String, UUID> refIdToEntityId) {
        if (m.supertypeId() != null) {
            SchemaMutationValidation.requireKnownItem(repo, m.supertypeId());
        }
        // Everything the new item type will own at the top level -- its own properties and groups, and
        // whatever the traits it implements bring -- checked before anything is written, so a failure
        // leaves no half-built item behind.
        Set<String> ownNames = PropertyMutationApplier.topLevelNames(m.properties(), m.groups());
        SchemaMutationValidation.requireNamesAvailableForNewItemType(repo, m.supertypeId(), ownNames, m.traitIds());
        var item = repo.createItem(m.name(), m.description(), m.supertypeId(), m.abstractType(), m.displayLabelPattern());
        propertyMutationApplier.createContents(new SchemaRepository.PropertyOwnerRef(PropertyContainerKind.ITEM, item.id()),
                m.properties(), m.groups(), "item type '" + m.name() + "'");
        for (var traitId : m.traitIds()) {
            repo.implementTrait(item.id(), traitId);
        }
        if (m.refId() != null) {
            refIdToEntityId.put(m.refId(), item.id());
        }
        eventPublisher.publishEvent(new SchemaChangeEvent.ItemTypeCreated(item.id()));
    }

    private void applyUpdate(UpdateItemDefinitionMutation m) {
        if (m.supertypeId() != null) {
            SchemaMutationValidation.requireKnownItem(repo, m.supertypeId());
            SchemaMutationValidation.requireNoSupertypeCycle(repo, m.id(), m.supertypeId());
            SchemaMutationValidation.requireNoTopLevelCollisionsForSupertype(repo, m.id(), m.supertypeId());
        }
        repo.updateItem(m.id(), m.name(), m.description(), m.supertypeId(), m.abstractType(), m.displayLabelPattern());
    }

    // Blank and absent text are the same to an editor, so they're the same here too.
    private void applyExpect(ExpectItemDefinitionMutation m) {
        var row = repo.lockItem(m.id())
                .orElseThrow(() -> new SchemaConflictException("This item type was deleted by someone else."));
        boolean unchanged = row.name().equals(m.name())
                && Objects.equals(blankToNull(row.description()), blankToNull(m.description()))
                && Objects.equals(row.supertypeId(), m.supertypeId())
                && row.abstractType() == m.abstractType()
                && Objects.equals(blankToNull(row.displayLabelPattern()), blankToNull(m.displayLabelPattern()));
        if (!unchanged) {
            throw new SchemaConflictException("Item type " + row.name() + " was changed by someone else while you were editing it.");
        }
    }

    private static String blankToNull(String text) {
        return text == null || text.isBlank() ? null : text;
    }

    private void applyDelete(DeleteItemDefinitionMutation m) {
        SchemaMutationValidation.requireItemTypeNotInUse(repo, m.id());
        SchemaMutationValidation.requireNoSubtypes(repo, m.id());
        linkMutationApplier.deleteLinksTouching(m.id());
        repo.deleteItem(m.id());
        eventPublisher.publishEvent(new SchemaChangeEvent.ItemTypeDeleted(m.id()));
    }
}
