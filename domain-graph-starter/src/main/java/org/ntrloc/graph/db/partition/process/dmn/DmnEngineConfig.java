package org.ntrloc.graph.db.partition.process.dmn;

import org.flowable.common.engine.impl.interceptor.EngineConfigurationConstants;
import org.flowable.dmn.api.DmnDecisionService;
import org.flowable.dmn.api.DmnEngineConfigurationApi;
import org.flowable.dmn.api.DmnRepositoryService;
import org.flowable.dmn.spring.SpringDmnEngineConfiguration;
import org.flowable.dmn.spring.configurator.SpringDmnEngineConfigurator;
import org.flowable.engine.ProcessEngine;
import org.ntrloc.graph.db.partition.process.dmn.persistence.DecisionDataManagerImpl;
import org.ntrloc.graph.db.partition.process.dmn.persistence.DecisionDeploymentDataManagerImpl;
import org.ntrloc.graph.db.partition.process.dmn.persistence.DecisionResourceDataManagerImpl;
import org.ntrloc.graph.db.partition.process.dmn.persistence.HistoricDecisionExecutionDataManagerImpl;
import org.ntrloc.graph.db.partition.process.persistence.ProcessSessionFactory;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

// Zero-MyBatis DMN engine wiring, mirroring ProcessEngineConfig's own conventions. Registered as
// a configurator on the process engine (see ProcessEngineConfig.processEngineConfiguration) --
// SpringDmnEngineConfigurator.configure() auto-shares the process engine's datasource/transaction-
// manager/beans map (initialiseCommonProperties, verified via source), so none of that is set
// here. Reuses ProcessSessionFactory as-is: Session/SessionFactory are shared Flowable base types
// with no engine-specific state (verified directly against ProcessSessionFactory.java), so the
// same instance registered on both engines resolves correctly per-command regardless of which
// engine's CommandContext is current.
@Configuration
@DependsOnDatabaseInitialization
public class DmnEngineConfig {

    @Bean
    public SpringDmnEngineConfiguration dmnEngineConfiguration(ProcessSessionFactory processSessionFactory) {
        SpringDmnEngineConfiguration config = new SpringDmnEngineConfiguration();
        // Same suppression as ProcessEngineConfig -- DB_SCHEMA_UPDATE_FALSE would still query
        // ACT_GE_PROPERTY (shared with the process engine, already suppressed there) for a version
        // check; this pre-set no-op is the actual extension point.
        config.setSchemaManagementCmd(commandContext -> null);
        config.setIdGenerator(new org.flowable.common.engine.impl.persistence.StrongUuidGenerator());

        config.addCustomSessionFactory(processSessionFactory);

        config.setDeploymentDataManager(new DecisionDeploymentDataManagerImpl());
        config.setResourceDataManager(new DecisionResourceDataManagerImpl());
        config.setDecisionDataManager(new DecisionDataManagerImpl());
        config.setHistoricDecisionExecutionDataManager(new HistoricDecisionExecutionDataManagerImpl());

        // Deliberately no deploymentResources here -- see ProcessEngineConfig's matching comment.

        return config;
    }

    @Bean
    public SpringDmnEngineConfigurator dmnEngineConfigurator(SpringDmnEngineConfiguration dmnEngineConfiguration) {
        SpringDmnEngineConfigurator configurator = new SpringDmnEngineConfigurator();
        configurator.setDmnEngineConfiguration(dmnEngineConfiguration);
        return configurator;
    }

    // Pulled off the *running* process engine's registered engine configurations rather than
    // built independently -- guarantees this is the exact same DmnEngine instance
    // DmnActivityBehavior actually calls at runtime (via CommandContextUtil.getDmnRuleService()),
    // not a second, separately-built one.
    @Bean
    public DmnRepositoryService dmnRepositoryService(ProcessEngine processEngine) {
        return dmnEngineConfigurationApi(processEngine).getDmnRepositoryService();
    }

    @Bean
    public DmnDecisionService dmnDecisionService(ProcessEngine processEngine) {
        return dmnEngineConfigurationApi(processEngine).getDmnDecisionService();
    }

    private DmnEngineConfigurationApi dmnEngineConfigurationApi(ProcessEngine processEngine) {
        return (DmnEngineConfigurationApi) processEngine.getProcessEngineConfiguration()
                .getEngineConfigurations()
                .get(EngineConfigurationConstants.KEY_DMN_ENGINE_CONFIG);
    }
}
