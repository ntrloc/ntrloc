package org.ntrloc;

import org.flowable.dmn.api.DmnRepositoryService;
import org.flowable.engine.RepositoryService;
import org.ntrloc.graph.db.partition.schema.ControlledListManager;
import org.ntrloc.graph.db.partition.schema.SchemaManager;
import org.ntrloc.graph.db.partition.schema.definition.mutation.CreateItemDefinitionMutation;
import org.ntrloc.graph.db.partition.schema.definition.view.admin.AdminItemDefinitionView;
import org.ntrloc.graph.domain.DomainInitializer;
import org.ntrloc.graph.domain.DomainInitializer.StateDefinition;
import org.ntrloc.graph.domain.DomainInitializer.TransitionDefinition;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.List;

// This runtime's own seed content. Self-contained and self-triggering -- nothing calls initSchema()/
// initProcesses() for us, so this bean does it itself, from run() (not @PostConstruct: SchemaManager/
// RepositoryService/DmnRepositoryService are ready either way, but @EventListener methods elsewhere --
// notably RegisterPartitionManager's reaction to schema changes -- aren't wired up until every
// singleton has finished construction, so any schema/data seeding needs the same ApplicationRunner-
// based timing; keeping process seeding on the same mechanism keeps this class boringly consistent
// rather than correct by accident).
@Component
public class Domain1Initializer implements DomainInitializer, ApplicationRunner {

    private final SchemaManager schemaManager;
    private final ControlledListManager controlledListManager;
    private final RepositoryService repositoryService;
    private final DmnRepositoryService dmnRepositoryService;

    public Domain1Initializer(SchemaManager schemaManager, ControlledListManager controlledListManager,
                               RepositoryService repositoryService, DmnRepositoryService dmnRepositoryService) {
        this.schemaManager = schemaManager;
        this.controlledListManager = controlledListManager;
        this.repositoryService = repositoryService;
        this.dmnRepositoryService = dmnRepositoryService;
    }

    @Override
    public void run(ApplicationArguments args) {
        initSchema(schemaManager, controlledListManager);
        initProcesses(repositoryService, dmnRepositoryService);
    }

    // Exact reproduction of the real "Product" state machine's topology (from the other demo
    // project) that triggers ntrloc-state-machine-diagram.js's label-collision/overflow bugs:
    // a self-loop on two different states, a bidirectional pair between Work in Progress and
    // Pending ISBN Finalization (Request_ISBN / Return_to_Requestor), and Recently Finalized
    // pushed off the right edge of the panel. Lets that rendering be fixed and verified against
    // this runtime's own admin UI, without depending on the other (external) demo project being
    // up. Not meant to model a real domain; DemoWorkflowItem exists purely to exercise this shape.
    @Override
    public void initSchema(SchemaManager schemaManager, ControlledListManager controlledListManager) {
        schemaManager.applyMutations(List.of(
                new CreateItemDefinitionMutation("DemoWorkflowItem", "Sample state machine for diagram rendering", List.of())));

        AdminItemDefinitionView itemType = schemaManager.getAdminSchema().items().stream()
                .filter(item -> item.name().equals("DemoWorkflowItem"))
                .findFirst()
                .orElseThrow();

        initStateMachine(schemaManager, itemType.id(), "ISBN Approval",
                List.of(
                        new StateDefinition("Work in Progress", true),
                        new StateDefinition("Pending ISBN Finalization", false),
                        new StateDefinition("Recently Finalized", false),
                        new StateDefinition("End", false)),
                List.of(
                        new TransitionDefinition("Work in Progress", "Pending ISBN Finalization", "Request_ISBN"),
                        new TransitionDefinition("Work in Progress", "Recently Finalized", "Finalize_ISBN"),
                        new TransitionDefinition("Work in Progress", "Work in Progress", "Loop"),
                        new TransitionDefinition("Work in Progress", "End", "Done"),
                        new TransitionDefinition("Pending ISBN Finalization", "Recently Finalized", "Finalize_ISBN"),
                        new TransitionDefinition("Pending ISBN Finalization", "Work in Progress", "Return_to_Requestor"),
                        new TransitionDefinition("Pending ISBN Finalization", "Pending ISBN Finalization", "Loop"),
                        new TransitionDefinition("Pending ISBN Finalization", "End", "Done"),
                        new TransitionDefinition("Recently Finalized", "End", "Remove")));
    }

    @Override
    public void initProcesses(RepositoryService repositoryService, DmnRepositoryService dmnRepositoryService) {
        deployDecisions(dmnRepositoryService, "pmdm-decisions",
                "decisions/can-finalize.dmn",
                "decisions/can-request.dmn",
                "decisions/destroy-after.dmn",
                "decisions/isbn-status.dmn",
                "decisions/royalty-reporting.dmn");
        deployProcesses(repositoryService, "pmdm-processes",
                "processes/edit-product.bpmn20.xml",
                "processes/product-create.bpmn20.xml",
                "processes/product-finalize.bpmn20.xml",
                "processes/product-initialize.bpmn20.xml",
                "processes/product-request-isbn.bpmn20.xml",
                "processes/trade-product-export.bpmn20.xml");
    }
}
