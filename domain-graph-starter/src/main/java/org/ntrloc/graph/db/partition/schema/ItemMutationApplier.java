package org.ntrloc.graph.db.partition.schema;

import org.ntrloc.graph.db.partition.schema.definition.mutation.CreateItemDefinitionMutation;
import org.ntrloc.graph.db.partition.schema.definition.mutation.DefinitionMutation;
import org.ntrloc.graph.db.partition.schema.definition.mutation.DeleteItemDefinitionMutation;
import org.ntrloc.graph.db.partition.schema.definition.mutation.SetItemInitProcessMutation;
import org.ntrloc.graph.db.partition.schema.definition.mutation.UpdateItemDefinitionMutation;
import org.ntrloc.graph.db.partition.schema.event.SchemaChangeEvent;
import org.ntrloc.graph.db.partition.schema.repository.SchemaRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.Set;

// Applies item-definition mutations -- split out of SchemaManager (see its own history) to keep
// each mutation-family applier's own dependency footprint small.
@Component
class ItemMutationApplier {

    private final SchemaRepository repo;
    private final ApplicationEventPublisher eventPublisher;

    ItemMutationApplier(SchemaRepository repo, ApplicationEventPublisher eventPublisher) {
        this.repo = repo;
        this.eventPublisher = eventPublisher;
    }

    boolean apply(DefinitionMutation mutation) {
        if (mutation instanceof CreateItemDefinitionMutation m) {
            var item = repo.createItem(m.name(), m.description());
            Set<String> usedNames = new HashSet<>();
            for (var p : m.properties()) {
                SchemaMutationValidation.requireUniqueName(usedNames, p.name(), "item type '" + m.name() + "'");
                var prop = repo.createProperty(p.name(), p.description(), p.propertyType(), p.cardinality(), p.usage());
                repo.associateItemProperty(item.id(), prop.id());
            }
            eventPublisher.publishEvent(new SchemaChangeEvent.ItemTypeCreated(item.id()));
        } else if (mutation instanceof UpdateItemDefinitionMutation m) {
            repo.updateItem(m.id(), m.name(), m.description());
        } else if (mutation instanceof DeleteItemDefinitionMutation m) {
            repo.deleteItem(m.id());
            eventPublisher.publishEvent(new SchemaChangeEvent.ItemTypeDeleted(m.id()));
        } else if (mutation instanceof SetItemInitProcessMutation m) {
            repo.setItemInitProcess(m.itemId(), m.initProcessId());
        } else {
            return false;
        }
        return true;
    }
}
