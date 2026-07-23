package org.ntrloc;

import org.flowable.dmn.api.DmnRepositoryService;
import org.flowable.engine.RepositoryService;
import org.ntrloc.graph.domain.DomainInitializer;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

// This runtime's own seed content. Self-contained and self-triggering -- nothing calls initProcesses()
// for us, so this bean does it itself, from run() (not @PostConstruct: RepositoryService/
// DmnRepositoryService are ready either way, but @EventListener methods elsewhere -- notably
// RegisterPartitionManager's reaction to schema changes -- aren't wired up until every singleton has
// finished construction, so any schema/data seeding needs the same ApplicationRunner-based timing;
// keeping process seeding on the same mechanism keeps this class boringly consistent rather than
// correct by accident). No schema/data seeding needed yet, hence initSchema/initData are left as
// DomainInitializer's default no-ops.
@Component
public class Domain1Initializer implements DomainInitializer, ApplicationRunner {

    private final RepositoryService repositoryService;
    private final DmnRepositoryService dmnRepositoryService;

    public Domain1Initializer(RepositoryService repositoryService, DmnRepositoryService dmnRepositoryService) {
        this.repositoryService = repositoryService;
        this.dmnRepositoryService = dmnRepositoryService;
    }

    @Override
    public void run(ApplicationArguments args) {
        initProcesses(repositoryService, dmnRepositoryService);
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
