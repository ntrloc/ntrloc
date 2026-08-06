package org.ntrloc.graph.db.mutation;

import org.ntrloc.graph.db.partition.schema.ControlledListManager;
import org.ntrloc.graph.db.partition.schema.SchemaManager;
import org.ntrloc.graph.db.partition.schema.definition.mutation.CreateItemDefinitionMutation;
import org.ntrloc.graph.db.partition.schema.definition.mutation.CreateLinkDefinitionMutation;
import org.ntrloc.graph.db.partition.schema.definition.mutation.CreatePerspectiveDefinitionMutation;
import org.ntrloc.graph.db.partition.schema.definition.view.admin.AdminItemDefinitionView;
import org.ntrloc.graph.domain.DomainInitializer;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

// Dedicated fixture for SelfLinkIntegrationTest -- a Person item type linked to itself two ways,
// the two shapes a same-type self-link can take: an asymmetric "mother"/"children" link (two
// distinctly-named perspectives on the same entity) and a symmetric "sibling"/"sibling" link (two
// identically-named perspectives on the same entity). See LinkMutationApplier and
// SchemaMutationValidation.requireConsistentPerspectiveTarget for what makes both possible now
// that schema_entity_link_perspective's old UNIQUE(entity_id, link_definition_id) is gone.
@Component
public class SelfLinkTestDomainInitializer implements DomainInitializer, ApplicationRunner {

    private UUID personTypeId;

    private final SchemaManager schemaManager;
    private final ControlledListManager controlledListManager;

    public SelfLinkTestDomainInitializer(SchemaManager schemaManager, ControlledListManager controlledListManager) {
        this.schemaManager = schemaManager;
        this.controlledListManager = controlledListManager;
    }

    @Override
    public void run(ApplicationArguments args) {
        initSchema(schemaManager, controlledListManager);
    }

    @Override
    public void initSchema(SchemaManager schemaManager, ControlledListManager controlledListManager) {
        schemaManager.applyMutations(List.of(new CreateItemDefinitionMutation(
                "SelfLinkPerson", "SelfLinkIntegrationTest fixture", List.of(), null, false)));
        personTypeId = findItem("SelfLinkPerson").id();

        schemaManager.applyMutations(List.of(new CreateLinkDefinitionMutation(List.of(), List.of(
                new CreatePerspectiveDefinitionMutation(personTypeId, "mother", "the mother of this person", 0, 1),
                new CreatePerspectiveDefinitionMutation(personTypeId, "children", "the children of this person", 0, null)))));

        schemaManager.applyMutations(List.of(new CreateLinkDefinitionMutation(List.of(), List.of(
                new CreatePerspectiveDefinitionMutation(personTypeId, "sibling", "the siblings of this person", 0, null),
                new CreatePerspectiveDefinitionMutation(personTypeId, "sibling", "the siblings of this person", 0, null)))));
    }

    private AdminItemDefinitionView findItem(String name) {
        return schemaManager.getAdminSchema().items().stream()
                .filter(item -> item.name().equals(name))
                .findFirst()
                .orElseThrow();
    }

    public UUID personTypeId() {
        return personTypeId;
    }
}
