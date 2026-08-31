package org.ntrloc.graph.db.partition.schema;

import org.ntrloc.graph.db.partition.schema.definition.mutation.CreateItemDefinitionMutation;
import org.ntrloc.graph.db.partition.schema.definition.mutation.DefinitionMutation;
import org.ntrloc.graph.db.partition.schema.definition.mutation.DeleteItemDefinitionMutation;
import org.ntrloc.graph.db.partition.schema.definition.mutation.UpdateItemDefinitionMutation;
import org.ntrloc.graph.db.partition.schema.event.SchemaChangeEvent;
import org.ntrloc.graph.db.partition.schema.repository.SchemaRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
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

    // A flat dispatch by design -- each branch used to carry its own nested loop/conditional logic
    // inline, which pushed this method's own Cognitive Complexity to 17 (Sonar's S3776, limit 15):
    // every nested control-flow structure inside an instanceof branch was penalized twice, once
    // for its own nesting and once for sitting inside the branch's own nesting level. Delegating to
    // a private method per mutation type removes that outer nesting layer -- each handler's own
    // complexity is far lower in isolation (worst case 4), and this method is now just a sequence
    // of flat, unnested checks.
    boolean apply(DefinitionMutation mutation) {
        if (mutation instanceof CreateItemDefinitionMutation m) {
            applyCreate(m);
        } else if (mutation instanceof UpdateItemDefinitionMutation m) {
            applyUpdate(m);
        } else if (mutation instanceof DeleteItemDefinitionMutation m) {
            applyDelete(m);
        } else {
            return false;
        }
        return true;
    }

    private void applyCreate(CreateItemDefinitionMutation m) {
        if (m.supertypeId() != null) {
            SchemaMutationValidation.requireKnownItem(repo, m.supertypeId());
        }
        var item = repo.createItem(m.name(), m.description(), m.supertypeId(), m.abstractType(), m.displayLabelPattern());
        Set<String> usedNames = new HashSet<>();
        for (var p : m.properties()) {
            SchemaMutationValidation.requireUniqueName(usedNames, p.name(), "item type '" + m.name() + "'");
            if (m.supertypeId() != null) {
                SchemaMutationValidation.requireNameNotInSupertypeChain(repo, m.supertypeId(), p.name());
            }
            var prop = PropertyMutationApplier.createPropertyRecursive(repo, p);
            repo.associateItemProperty(item.id(), prop.id());
        }
        eventPublisher.publishEvent(new SchemaChangeEvent.ItemTypeCreated(item.id()));
    }

    private void applyUpdate(UpdateItemDefinitionMutation m) {
        if (m.supertypeId() != null) {
            SchemaMutationValidation.requireKnownItem(repo, m.supertypeId());
            SchemaMutationValidation.requireNoSupertypeCycle(repo, m.id(), m.supertypeId());
            for (var ownProperty : repo.getPropertiesByItem().getOrDefault(m.id(), List.of())) {
                SchemaMutationValidation.requireNameNotInSupertypeChain(repo, m.supertypeId(), ownProperty.name());
            }
        }
        repo.updateItem(m.id(), m.name(), m.description(), m.supertypeId(), m.abstractType(), m.displayLabelPattern());
    }

    private void applyDelete(DeleteItemDefinitionMutation m) {
        SchemaMutationValidation.requireItemTypeNotInUse(repo, m.id());
        repo.deleteItem(m.id());
        eventPublisher.publishEvent(new SchemaChangeEvent.ItemTypeDeleted(m.id()));
    }
}
