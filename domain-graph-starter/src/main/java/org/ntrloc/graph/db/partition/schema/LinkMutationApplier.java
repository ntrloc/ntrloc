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

// Applies link-definition mutations -- split out of SchemaManager (see its own history).
@Component
class LinkMutationApplier {

    private final SchemaRepository repo;
    private final ApplicationEventPublisher eventPublisher;

    LinkMutationApplier(SchemaRepository repo, ApplicationEventPublisher eventPublisher) {
        this.repo = repo;
        this.eventPublisher = eventPublisher;
    }

    boolean apply(DefinitionMutation mutation) {
        if (mutation instanceof CreateLinkDefinitionMutation m) {
            UUID linkId = repo.createLink();
            Set<String> usedNames = new HashSet<>();
            for (var p : m.properties()) {
                SchemaMutationValidation.requireUniqueName(usedNames, p.name(), "this link type");
                var prop = repo.createProperty(p.name(), p.description(), p.propertyType(), p.cardinality(), p.usage());
                repo.associateLinkProperty(linkId, prop.id());
            }
            for (var perspective : m.perspectives()) {
                SchemaMutationValidation.requireKnownItemOrTrait(repo, perspective.itemId());
                repo.createPerspective(perspective.itemId(), linkId, perspective.name(), perspective.description(),
                        perspective.minCardinality(), perspective.maxCardinality());
            }
            eventPublisher.publishEvent(new SchemaChangeEvent.LinkTypeCreated(linkId));
        } else if (mutation instanceof DeleteLinkDefinitionMutation m) {
            repo.deleteLink(m.id());
            eventPublisher.publishEvent(new SchemaChangeEvent.LinkTypeDeleted(m.id()));
        } else if (mutation instanceof UpdatePerspectiveDefinitionMutation m) {
            repo.updatePerspective(m.id(), m.name(), m.description(), m.minCardinality(), m.maxCardinality());
        } else {
            return false;
        }
        return true;
    }
}
