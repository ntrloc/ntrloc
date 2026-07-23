package org.ntrloc.graph.domain;

import org.flowable.dmn.api.DmnRepositoryService;
import org.flowable.engine.RepositoryService;
import org.ntrloc.graph.db.partition.binary.BinaryPartitionManager;
import org.ntrloc.graph.db.partition.schema.ControlledListManager;
import org.ntrloc.graph.db.partition.schema.SchemaManager;
import org.springframework.jdbc.core.simple.JdbcClient;

// The one and only thing that seeds a domain -- schema, data, processes, everything. Nothing else
// in the system (SchemaManager, RegisterInitializer, ProcessEngineConfig, DmnEngineConfig) has any
// knowledge that a DomainInitializer exists or that the system is being "initialized": it starts
// up empty, and a DomainInitializer implementation seeds it afterward through the same public APIs
// any other caller would use (SchemaManager.applyMutations() publishes SchemaChangeEvent, which
// RegisterPartitionManager already reacts to by creating per-item-type/per-link-type register
// tables -- no eager scan needed).
//
// Each of the three init* methods is a default no-op -- implement only what a given domain
// actually needs. Nothing calls these for you: a concrete implementation is a normal @Component
// that also implements Spring's ApplicationRunner (not @PostConstruct -- see the class-level note
// on any implementation for why: @EventListener methods elsewhere aren't wired up until *all*
// singletons finish construction, so a @PostConstruct that publishes SchemaChangeEvent during its
// own construction can fire before RegisterPartitionManager is even listening), constructor-
// injecting whatever it needs (SchemaManager, ControlledListManager, JdbcClient,
// BinaryPartitionManager, RepositoryService, DmnRepositoryService) and calling its own overridden
// method(s) from run(), in the order initSchema -> initData -> initProcesses.
public interface DomainInitializer {

    /**
     * Implementations create item types, traits, links, properties, and controlled lists via
     * schemaManager.applyMutations(...) -- the same API the admin UI's schema editor uses.
     */
    default void initSchema(SchemaManager schemaManager, ControlledListManager controlledListManager) {
    }

    /**
     * Implementations seed domain data via jdbcClient and may store binary content.
     */
    default void initData(JdbcClient jdbcClient, BinaryPartitionManager binaryPartitionManager) {
    }

    /**
     * Implementations deploy domain-specific BPMN processes and/or DMN decision tables, e.g. via
     * deployProcesses/deployDecisions below.
     */
    default void initProcesses(RepositoryService repositoryService, DmnRepositoryService dmnRepositoryService) {
    }

    default void deployProcesses(RepositoryService repositoryService, String deploymentName, String... classpathResources) {
        var builder = repositoryService.createDeployment().name(deploymentName);
        for (String resource : classpathResources) {
            builder.addClasspathResource(resource);
        }
        builder.deploy();
    }

    default void deployDecisions(DmnRepositoryService dmnRepositoryService, String deploymentName, String... classpathResources) {
        var builder = dmnRepositoryService.createDeployment().name(deploymentName);
        for (String resource : classpathResources) {
            builder.addClasspathResource(resource);
        }
        builder.deploy();
    }
}
