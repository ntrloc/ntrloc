package org.ntrloc.graph.db.partition.process;

import org.flowable.dmn.api.DmnRepositoryService;
import org.flowable.engine.RepositoryService;
import org.ntrloc.graph.domain.DomainInitializer;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

// This module's own generic examples (hello-world.bpmn20.xml, approval-decision.dmn) are test-only
// seed content -- ProcessEngineIntegrationTest/ProcessAdminControllerIntegrationTest need them
// deployed, but no production runtime should ship them. Same self-triggering pattern as any other
// DomainInitializer implementation (see that interface's class comment for why ApplicationRunner,
// not @PostConstruct) -- this is the test-scope equivalent of CoordinatorTestDomainInitializer,
// just for the process/DMN partition instead of the schema partition.
@Component
public class ProcessTestDomainInitializer implements DomainInitializer, ApplicationRunner {

    private final RepositoryService repositoryService;
    private final DmnRepositoryService dmnRepositoryService;

    public ProcessTestDomainInitializer(RepositoryService repositoryService, DmnRepositoryService dmnRepositoryService) {
        this.repositoryService = repositoryService;
        this.dmnRepositoryService = dmnRepositoryService;
    }

    @Override
    public void run(ApplicationArguments args) {
        initProcesses(repositoryService, dmnRepositoryService);
    }

    @Override
    public void initProcesses(RepositoryService repositoryService, DmnRepositoryService dmnRepositoryService) {
        deployProcesses(repositoryService, "hello-world", "processes/hello-world.bpmn20.xml");
        deployDecisions(dmnRepositoryService, "approval-decision", "decisions/approval-decision.dmn");
    }
}
