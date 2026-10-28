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

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

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
    // refIdToItemTypeId: item types created earlier in the batch, by refId, for a perspective's itemRefId.
    boolean apply(DefinitionMutation mutation, Map<String, UUID> refIdToItemTypeId) {
        switch (mutation) {
            case CreateLinkDefinitionMutation m -> applyCreate(m, refIdToItemTypeId);
            case DeleteLinkDefinitionMutation m -> applyDelete(m);
            case UpdatePerspectiveDefinitionMutation m -> applyUpdatePerspective(m);
            default -> {
                return false;
            }
        }
        return true;
    }

    private void applyCreate(CreateLinkDefinitionMutation m, Map<String, UUID> refIdToItemTypeId) {
        UUID linkId = repo.createLink();
        propertyMutationApplier.createContents(new SchemaRepository.PropertyOwnerRef(PropertyContainerKind.LINK, linkId),
                m.properties(), m.groups(), "this link type");
        var perspectives = m.perspectives();
        List<UUID> itemIds = perspectives.stream().map(p -> resolveItemId(p, refIdToItemTypeId)).toList();
        for (int i = 0; i < perspectives.size(); i++) {
            var perspective = perspectives.get(i);
            SchemaMutationValidation.requireKnownItemOrTrait(repo, itemIds.get(i));
            // The other participants in *this* link definition are this perspective's target
            // by construction -- compared in-memory rather than via a DB round trip, since
            // none of them exist as rows yet at validation time (they're all inserted below,
            // together, only once every perspective in the list has passed validation).
            Set<UUID> targets = new HashSet<>();
            for (int j = 0; j < perspectives.size(); j++) {
                if (j != i) targets.add(itemIds.get(j));
            }
            SchemaMutationValidation.requireConsistentPerspectiveTarget(repo, linkId, itemIds.get(i), perspective.name(), targets);
        }
        for (int i = 0; i < perspectives.size(); i++) {
            var perspective = perspectives.get(i);
            repo.createPerspective(itemIds.get(i), linkId, perspective.name(), perspective.description(),
                    perspective.minCardinality(), perspective.maxCardinality());
        }
        eventPublisher.publishEvent(new SchemaChangeEvent.LinkTypeCreated(linkId));
    }

    private static UUID resolveItemId(CreatePerspectiveDefinitionMutation perspective, Map<String, UUID> refIdToItemTypeId) {
        if ((perspective.itemId() == null) == (perspective.itemRefId() == null)) {
            throw new IllegalArgumentException("A link's perspective '" + perspective.name() + "' needs exactly one of itemId and itemRefId");
        }
        if (perspective.itemId() != null) return perspective.itemId();
        UUID itemId = refIdToItemTypeId.get(perspective.itemRefId());
        if (itemId == null) {
            throw new IllegalArgumentException("Unknown item type refId: " + perspective.itemRefId());
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
            Set<UUID> targets = repo.findInversePerspectives(existing.linkId(), existing.id()).stream()
                    .map(SchemaRepository.PerspectiveRow::entityId)
                    .collect(Collectors.toSet());
            SchemaMutationValidation.requireConsistentPerspectiveTarget(repo, existing.linkId(), existing.entityId(), m.name(), targets);
        }
        repo.updatePerspective(m.id(), m.name(), m.description(), m.minCardinality(), m.maxCardinality());
    }
}
