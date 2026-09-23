package org.ntrloc.graph.db.partition.process;

import org.flowable.dmn.api.DmnRepositoryService;
import org.flowable.engine.RepositoryService;
import org.ntrloc.graph.domain.DomainInitializer;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

// The first *production* process deployer -- ProcessEngineConfig deliberately deploys nothing
// itself (that's a DomainInitializer concern), and the only DomainInitializer that deployed
// anything process-related before this one was ProcessTestDomainInitializer, which is test-scoped
// on purpose (hello-world.bpmn20.xml is generic example content, not something a real deployment
// should ship). extract-binary-metadata.bpmn20.xml is real: BinaryMetadataExtractionTrigger starts
// it by key whenever a binary is genuinely first created, so it has to actually be deployed
// wherever this app runs, tests included -- DomainInitializers run as ApplicationRunners
// regardless of test vs. production context, so this covers both without a separate test-only copy.
@Component
public class ProcessDomainInitializer implements DomainInitializer, ApplicationRunner {

    private final RepositoryService repositoryService;
    private final DmnRepositoryService dmnRepositoryService;

    public ProcessDomainInitializer(RepositoryService repositoryService, DmnRepositoryService dmnRepositoryService) {
        this.repositoryService = repositoryService;
        this.dmnRepositoryService = dmnRepositoryService;
    }

    @Override
    public void run(ApplicationArguments args) {
        initProcesses(repositoryService, dmnRepositoryService);
    }

    @Override
    public void initProcesses(RepositoryService repositoryService, DmnRepositoryService dmnRepositoryService) {
        deployProcesses(repositoryService, "extract-binary-metadata", "processes/extract-binary-metadata.bpmn20.xml");
    }
}
