package org.ntrloc.graph.db.partition.schema;

import org.ntrloc.graph.db.partition.schema.definition.PropertyContainerKind;
import org.ntrloc.graph.db.partition.schema.definition.mutation.CreateLinkDefinitionMutation;
import org.ntrloc.graph.db.partition.schema.definition.mutation.CreatePerspectiveDefinitionMutation;
import org.ntrloc.graph.db.partition.schema.definition.mutation.DefinitionMutation;
import org.ntrloc.graph.db.partition.schema.definition.mutation.DeleteLinkDefinitionMutation;
import org.ntrloc.graph.db.partition.schema.definition.mutation.UpdatePerspectiveDefinitionMutation;
import org.ntrloc.graph.db.partition.schema.event.SchemaChangeEvent;
import org.ntrloc.graph.db.partition.schema.repository.SchemaRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.UUID;

// Applies link-definition mutations -- split out of SchemaManager (see its own history).
@Component
class LinkMutationApplier {

    private final SchemaRepository repo;
    private final ApplicationEventPublisher eventPublisher;
    private final PropertyMutationApplier propertyMutationApplier;

    LinkMutationApplier(SchemaRepository repo, ApplicationEventPublisher eventPublisher, PropertyMutationApplier propertyMutationApplier) {
        this.repo = repo;
        this.eventPublisher = eventPublisher;
        this.propertyMutationApplier = propertyMutationApplier;
    }

    // Flat dispatch by design -- see ItemMutationApplier.apply()'s own comment for why: each
    // instanceof branch used to carry its nested loop/conditional logic inline, which is what
    // pushed this method's Cognitive Complexity to 19 (Sonar's S3776, limit 15). Delegating to a
    // private method per mutation type drops the outer nesting layer everything inside a branch
    // used to inherit.
    // refIdToEntityId: item types and traits created earlier in the batch, by refId, for a perspective's itemRefId.
    boolean apply(DefinitionMutation mutation, Map<String, UUID> refIdToEntityId) {
        switch (mutation) {
            case CreateLinkDefinitionMutation m -> applyCreate(m, refIdToEntityId);
            case DeleteLinkDefinitionMutation m -> applyDelete(m);
            case UpdatePerspectiveDefinitionMutation m -> applyUpdatePerspective(m);
            default -> {
                return false;
            }
        }
        return true;
    }

    private void applyCreate(CreateLinkDefinitionMutation m, Map<String, UUID> refIdToEntityId) {
        UUID linkId = repo.createLink();
        propertyMutationApplier.createContents(new SchemaRepository.PropertyOwnerRef(PropertyContainerKind.LINK, linkId),
                m.properties(), m.groups(), "this link type");
        var perspectives = m.perspectives();
        List<UUID> itemIds = perspectives.stream().map(p -> resolveItemId(p, refIdToEntityId)).toList();
        // Every perspective is checked before any is written, so a failure leaves no half-built link.
        for (int i = 0; i < perspectives.size(); i++) {
            SchemaMutationValidation.requireKnownItemOrTrait(repo, itemIds.get(i));
            SchemaMutationValidation.requirePerspectiveNameAvailable(repo, itemIds.get(i), perspectives.get(i).name(), linkId);
        }
        for (int i = 0; i < perspectives.size(); i++) {
            var perspective = perspectives.get(i);
            repo.createPerspective(itemIds.get(i), linkId, perspective.name(), perspective.description(),
                    perspective.minCardinality(), perspective.maxCardinality());
        }
        eventPublisher.publishEvent(new SchemaChangeEvent.LinkTypeCreated(linkId));
    }

    private static UUID resolveItemId(CreatePerspectiveDefinitionMutation perspective, Map<String, UUID> refIdToEntityId) {
        if ((perspective.itemId() == null) == (perspective.itemRefId() == null)) {
            throw new IllegalArgumentException("A link's perspective '" + perspective.name() + "' needs exactly one of itemId and itemRefId");
        }
        if (perspective.itemId() != null) return perspective.itemId();
        UUID itemId = refIdToEntityId.get(perspective.itemRefId());
        if (itemId == null) {
            throw new IllegalArgumentException("Unknown refId: " + perspective.itemRefId());
        }
        return itemId;
    }

    private void applyDelete(DeleteLinkDefinitionMutation m) {
        repo.deleteLink(m.id());
        eventPublisher.publishEvent(new SchemaChangeEvent.LinkTypeDeleted(m.id()));
    }

    private void applyUpdatePerspective(UpdatePerspectiveDefinitionMutation m) {
        var existing = repo.findPerspectiveById(m.id());
        if (!existing.name().equals(m.name())) {
            SchemaMutationValidation.requirePerspectiveNameAvailable(repo, existing.entityId(), m.name(), existing.linkId());
        }
        repo.updatePerspective(m.id(), m.name(), m.description(), m.minCardinality(), m.maxCardinality());
    }
}
