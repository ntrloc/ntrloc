package org.ntrloc.graph.db.partition.schema;

import org.ntrloc.graph.db.partition.schema.definition.mutation.CreateItemPropertyDefinitionMutation;
import org.ntrloc.graph.db.partition.schema.definition.mutation.CreateLinkPropertyDefinitionMutation;
import org.ntrloc.graph.db.partition.schema.definition.mutation.DefinitionMutation;
import org.ntrloc.graph.db.partition.schema.definition.mutation.DeletePropertyDefinitionMutation;
import org.ntrloc.graph.db.partition.schema.definition.mutation.UpdatePropertyDefinitionMutation;
import org.ntrloc.graph.db.partition.schema.repository.SchemaRepository;
import org.springframework.stereotype.Component;

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
            var prop = repo.createProperty(m.name(), m.description(), m.propertyType(), m.cardinality(), m.usage());
            repo.associateItemProperty(m.itemId(), prop.id());
        } else if (mutation instanceof CreateLinkPropertyDefinitionMutation m) {
            SchemaMutationValidation.requireNameNotAssociated(repo.getPropertiesByLink(), m.linkId(), m.name(), "this link type");
            var prop = repo.createProperty(m.name(), m.description(), m.propertyType(), m.cardinality(), m.usage());
            repo.associateLinkProperty(m.linkId(), prop.id());
        } else if (mutation instanceof UpdatePropertyDefinitionMutation m) {
            repo.updateProperty(m.id(), m.name(), m.description(), m.propertyType(), m.cardinality(), m.usage());
        } else if (mutation instanceof DeletePropertyDefinitionMutation m) {
            repo.deleteProperty(m.id());
        } else {
            return false;
        }
        return true;
    }
}
