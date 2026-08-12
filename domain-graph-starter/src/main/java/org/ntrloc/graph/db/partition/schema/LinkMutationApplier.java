package org.ntrloc.graph.db.partition.schema;

import org.ntrloc.graph.db.partition.schema.definition.mutation.CreateLinkDefinitionMutation;
import org.ntrloc.graph.db.partition.schema.definition.mutation.DefinitionMutation;
import org.ntrloc.graph.db.partition.schema.definition.mutation.DeleteLinkDefinitionMutation;
import org.ntrloc.graph.db.partition.schema.definition.mutation.UpdatePerspectiveDefinitionMutation;
import org.ntrloc.graph.db.partition.schema.event.SchemaChangeEvent;
import org.ntrloc.graph.db.partition.schema.repository.SchemaRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

// Applies link-definition mutations -- split out of SchemaManager (see its own history).
@Component
class LinkMutationApplier {

    private final SchemaRepository repo;
    private final ApplicationEventPublisher eventPublisher;

    LinkMutationApplier(SchemaRepository repo, ApplicationEventPublisher eventPublisher) {
        this.repo = repo;
        this.eventPublisher = eventPublisher;
    }

    // Flat dispatch by design -- see ItemMutationApplier.apply()'s own comment for why: each
    // instanceof branch used to carry its nested loop/conditional logic inline, which is what
    // pushed this method's Cognitive Complexity to 19 (Sonar's S3776, limit 15). Delegating to a
    // private method per mutation type drops the outer nesting layer everything inside a branch
    // used to inherit.
    boolean apply(DefinitionMutation mutation) {
        if (mutation instanceof CreateLinkDefinitionMutation m) {
            applyCreate(m);
        } else if (mutation instanceof DeleteLinkDefinitionMutation m) {
            applyDelete(m);
        } else if (mutation instanceof UpdatePerspectiveDefinitionMutation m) {
            applyUpdatePerspective(m);
        } else {
            return false;
        }
        return true;
    }

    private void applyCreate(CreateLinkDefinitionMutation m) {
        UUID linkId = repo.createLink();
        Set<String> usedNames = new HashSet<>();
        for (var p : m.properties()) {
            SchemaMutationValidation.requireUniqueName(usedNames, p.name(), "this link type");
            var prop = PropertyMutationApplier.createPropertyRecursive(repo, p);
            repo.associateLinkProperty(linkId, prop.id());
        }
        var perspectives = m.perspectives();
        for (int i = 0; i < perspectives.size(); i++) {
            var perspective = perspectives.get(i);
            SchemaMutationValidation.requireKnownItemOrTrait(repo, perspective.itemId());
            // The other participants in *this* link definition are this perspective's target
            // by construction -- compared in-memory rather than via a DB round trip, since
            // none of them exist as rows yet at validation time (they're all inserted below,
            // together, only once every perspective in the list has passed validation).
            Set<UUID> targets = new HashSet<>();
            for (int j = 0; j < perspectives.size(); j++) {
                if (j != i) targets.add(perspectives.get(j).itemId());
            }
            SchemaMutationValidation.requireConsistentPerspectiveTarget(repo, linkId, perspective.itemId(), perspective.name(), targets);
        }
        for (var perspective : perspectives) {
            repo.createPerspective(perspective.itemId(), linkId, perspective.name(), perspective.description(),
                    perspective.minCardinality(), perspective.maxCardinality());
        }
        eventPublisher.publishEvent(new SchemaChangeEvent.LinkTypeCreated(linkId));
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
