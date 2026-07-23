package org.ntrloc.graph.db.partition.process;

import org.flowable.dmn.spring.configurator.SpringDmnEngineConfigurator;
import org.flowable.engine.HistoryService;
import org.flowable.engine.ProcessEngine;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.ntrloc.graph.db.partition.process.persistence.ActivityInstanceDataManagerImpl;
import org.ntrloc.graph.db.partition.process.persistence.DeploymentDataManagerImpl;
import org.ntrloc.graph.db.partition.process.persistence.EventSubscriptionServiceConfigurator;
import org.ntrloc.graph.db.partition.process.persistence.ExecutionDataManagerImpl;
import org.ntrloc.graph.db.partition.process.persistence.IdentityLinkServiceConfigurator;
import org.ntrloc.graph.db.partition.process.persistence.JobServiceConfigurator;
import org.ntrloc.graph.db.partition.process.persistence.ModelDataManagerImpl;
import org.ntrloc.graph.db.partition.process.persistence.NtrlocPrincipalVariableType;
import org.ntrloc.graph.db.partition.process.persistence.ProcessDefinitionDataManagerImpl;
import org.ntrloc.graph.db.partition.process.persistence.ProcessDefinitionInfoDataManagerImpl;
import org.ntrloc.graph.db.partition.process.persistence.ProcessSessionFactory;
import org.ntrloc.graph.db.partition.process.persistence.PropertyDataManagerImpl;
import org.ntrloc.graph.db.partition.process.persistence.ResourceDataManagerImpl;
import org.ntrloc.graph.db.partition.process.persistence.TaskServiceConfigurator;
import org.ntrloc.graph.db.partition.process.persistence.VariableServiceConfigurator;
import org.ntrloc.graph.db.partition.process.script.ProcessScriptEngineFactory;
import org.ntrloc.graph.db.partition.process.script.ProcessScriptProperties;
import org.flowable.spring.SpringProcessEngineConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;
import java.util.List;

// Zero-MyBatis (docs/ntrloc-workflow-summary.md Sections 3/6): every Flowable entity this engine
// can actually touch during normal execution -- deployments/resources/process-definitions/
// executions/activity-instances/variables/tasks/task-identity-links/jobs/event-subscriptions --
// flows entirely through our own JDBC-backed DataManagers and process_* tables (see the
// persistence sub-package), never Flowable's MyBatis-managed ACT_*/FLW_* tables. IDM and the Event
// Registry are disabled outright rather than given custom persistence (confirmed zero usage
// anywhere in ntrloc). History stays off -- nothing reads/writes it, so its tables simply never
// get created (see the schema-management override below). The handful of remaining Flowable
// entities (Model, EventLogEntry, ProcessDefinitionInfo, EntityLink, Batch) are each gated behind
// a config flag this class never sets, so they're genuinely unreachable, not just unused.
@Configuration
@DependsOn({"processPersistenceInitializer", "decisionPersistenceInitializer"})
@EnableConfigurationProperties(ProcessScriptProperties.class)
public class ProcessEngineConfig {

    // Exposed as its own bean (rather than constructed inline) so DmnEngineConfig can register
    // the exact same instance on the DMN engine -- see that class's comment for why one shared
    // ProcessSessionFactory correctly serves both engines.
    @Bean
    public ProcessSessionFactory processSessionFactory(JdbcClient jdbcClient) {
        return new ProcessSessionFactory(jdbcClient);
    }

    @Bean
    public SpringProcessEngineConfiguration processEngineConfiguration(
            DataSource dataSource, PlatformTransactionManager transactionManager,
            ApplicationContext applicationContext, JdbcClient jdbcClient, TaskEventListener taskEventListener,
            ProcessRunAsUserListener processRunAsUserListener,
            ProcessSessionFactory processSessionFactory, SpringDmnEngineConfigurator dmnEngineConfigurator,
            ProcessScriptProperties processScriptProperties) {
        SpringProcessEngineConfiguration config = new SpringProcessEngineConfiguration();
        config.setDataSource(dataSource);
        config.setTransactionManager(transactionManager);
        // Feeds TaskEventBroadcaster (-> the /tasks/events SSE stream) whenever a task is
        // created/assigned/completed -- see TaskEventListener for why just these three types.
        // ProcessRunAsUserListener: injects a declared principal for a process started with no
        // HTTP caller (a timer, e.g.) -- see that class's own comment.
        config.setEventListeners(List.of(taskEventListener, processRunAsUserListener));
        // Suppress Flowable's own schema management entirely, rather than DB_SCHEMA_UPDATE_FALSE --
        // that value still issues a real SELECT against ACT_GE_PROPERTY for a version check and
        // hard-fails since that table will never exist. setSchemaManagementCmd pre-set to a literal
        // no-op is the actual extension point (AbstractEngineConfiguration.initSchemaManagementCommand()
        // only builds Flowable's own SchemaOperationsEngineBuild if this field is still null), so no
        // ACT_*/FLW_* table gets created at all -- ProcessPersistenceInitializer owns process_* table
        // DDL entirely independently.
        config.setSchemaManagementCmd(commandContext -> null);
        // Flowable's default DbIdGenerator reads/writes ACT_GE_PROPERTY's id-block key at runtime
        // (not just boot) whenever its in-memory block is exhausted -- a second, independent path to
        // a table that no longer exists. Every DataManager here already bypasses the configured
        // IdGenerator via assignIdIfMissing(); this just makes reaching for it structurally impossible.
        config.setIdGenerator(new org.flowable.common.engine.impl.persistence.StrongUuidGenerator());
        // Discovered live: ValidateExecutionRelatedEntityCountCfgCmd looks up a config property
        // unconditionally during every buildEngine() call -- a third, independent path to
        // ACT_GE_PROPERTY beyond schema management and the id generator above.
        config.setPropertyDataManager(new PropertyDataManagerImpl());
        config.setApplicationContext(applicationContext);
        // Pre-set before buildEngine() runs so SpringProcessEngineConfiguration.initBeans()'s own
        // "if (beans == null)" guard never fires -- otherwise it builds SpringBeanFactoryProxyMap,
        // exposing every bean in the whole ApplicationContext, unrestricted, to ${...} in a script
        // task or delegateExpression. ProcessAccessibleBeansMap closes that off to only beans
        // explicitly marked @ProcessAccessible (ntrloc's positive-assertion convention: existing
        // as a bean never implies being safe/intended for a process author to call directly) --
        // see that class's own comment for the full reasoning, including why this also covers the
        // DMN engine's own expression resolution with no separate wiring there.
        config.setBeans(new ProcessAccessibleBeansMap(applicationContext));
        // customPre-, not customPost-: ProcessEngineConfigurationImpl.initVariableTypes() tries
        // types in registration order and uses the first one whose isAbleToStore() matches, so
        // registering before the ~20 built-in types guarantees an NtrlocPrincipal value is never
        // even offered to them (moot today -- ResolvedPrincipal isn't Serializable, so
        // SerializableType wouldn't claim it either -- but pre- is the position that stays correct
        // regardless of what that type happens to be).
        config.setCustomPreVariableTypes(List.of(new NtrlocPrincipalVariableType()));
        // Pre-set before buildEngine() runs, same reasoning as setBeans() just above --
        // ProcessEngineConfigurationImpl.initScriptingEngines() only builds a default
        // JSR223FlowableScriptEngine via its own initScriptEngine() "if (scriptEngine == null)"
        // guard, called *before* scriptingEngines itself is assembled around it -- so the real
        // scriptBindingsFactory (the @ProcessAccessible bean resolver chain, execution variables,
        // etc., built independently at initScriptBindingsFactory(), unrelated to this) still gets
        // composed in normally around whatever engine is set here. See ProcessScriptEngineFactory
        // for what this buys: ntrloc.process.script.import-packages-configured classes usable by
        // simple name (`new SingleItemProjectionSpec(...)`) from both Groovy and JavaScript
        // process scripts, no fully-qualified name or per-script import needed.
        config.setScriptEngine(ProcessScriptEngineFactory.build(processScriptProperties));
        config.setHistory("none");
        // Activated (July 2026, docs/ntrloc-workflow-summary.md "Job control: AsyncExecutor" section)
        // -- the plain default form (a node polls, claims, and executes its own due jobs), not yet the
        // acquisition-disabled/targeted-push scheme that section designs for real multi-node routing.
        // Correct for today's single-node deployment; needs that follow-on work, not just re-enabling,
        // before this is trusted for genuine multi-node -- see this session's addition to that section
        // for exactly what's verified vs. still open about the custom Job DataManagers' claim safety.
        config.setAsyncExecutorActivate(true);
        // Zero-MyBatis (docs/ntrloc-workflow-summary.md Section 6): neither sub-engine is used
        // anywhere in ntrloc (confirmed via repo-wide grep) -- ntrloc has its own identity/
        // authorization partition, and no process triggers on external Kafka/HTTP/JMS channels.
        // Disabling means the sub-engine (and its own schema management) never gets built at all,
        // not just "unused" -- stronger than giving it custom persistence would achieve.
        config.setDisableIdmEngine(true);
        config.setDisableEventRegistry(true);
        // Deliberately no deploymentResources here -- this engine wires up the process engine and
        // nothing else. What gets deployed is entirely a DomainInitializer concern (see that
        // interface's class comment); this module's own hello-world.bpmn20.xml is test-only seed
        // content, not production seed content, and is deployed by a test-scoped
        // DomainInitializer implementation, same as everything else.

        // Custom Session/SessionFactory: our own low-level persistence unit-of-work, replacing
        // MyBatis's DbSqlSession for the five entity types below.
        config.addCustomSessionFactory(processSessionFactory);

        // DMN engine, for decision-table evaluation from a <serviceTask flowable:type="dmn">
        // (see DmnEngineConfig). Configured here, not built independently -- configure() runs as
        // part of this engine's own buildEngine() below.
        config.addConfigurator(dmnEngineConfigurator);

        // Pre-setting these sticks: ProcessEngineConfigurationImpl.initDataManagers() only
        // creates its default Mybatis*DataManager if the field is still null.
        config.setDeploymentDataManager(new DeploymentDataManagerImpl());
        config.setResourceDataManager(new ResourceDataManagerImpl());
        config.setProcessDefinitionDataManager(new ProcessDefinitionDataManagerImpl());
        // Discovered live: BpmnDeployer.createLocalizationValues() looks this up unconditionally on
        // every deploy, not gated behind isEnableProcessDefinitionInfoCache() as earlier analysis
        // assumed.
        config.setProcessDefinitionInfoDataManager(new ProcessDefinitionInfoDataManagerImpl());
        // Discovered live: DeploymentEntityManagerImpl.deleteDeployment() unconditionally queries
        // models via updateRelatedModels(), regardless of whether modeling is enabled -- see
        // ModelDataManagerImpl's class comment.
        config.setModelDataManager(new ModelDataManagerImpl());
        config.setExecutionDataManager(new ExecutionDataManagerImpl());
        // Mandatory, not optional: ContinueProcessOperation writes an activity-instance row on
        // every activity transition unconditionally, no history-level gate (Section 6).
        config.setActivityInstanceDataManager(new ActivityInstanceDataManagerImpl());

        // VariableServiceConfiguration/TaskServiceConfiguration/IdentityLinkServiceConfiguration
        // are shared cross-engine infrastructure that gets rebuilt from scratch during
        // buildEngine() -- pre-setting a custom instance on any of them directly would be silently
        // overwritten, so each DataManager is installed via the beforeInit() hook instead (see
        // VariableServiceConfigurator for why). With Task and IdentityLink both on their own
        // process_task/process_task_identitylink tables, nothing in this app ever writes a real row
        // into Flowable's native ACT_RU_TASK/ACT_RU_IDENTITYLINK/ACT_RU_EXECUTION/ACT_RE_PROCDEF --
        // no more shadow rows, no more deferred-constraint workaround.
        config.addVariableServiceConfigurator(new VariableServiceConfigurator());
        config.addTaskServiceConfigurator(new TaskServiceConfigurator());
        config.addIdentityLinkServiceConfigurator(new IdentityLinkServiceConfigurator());
        // JobServiceConfiguration is the same kind of shared, rebuilt-from-scratch config object --
        // see JobServiceConfigurator. The async executor is activated above (setAsyncExecutorActivate),
        // so this is now in active use, not just built for zero-MyBatis completeness (Section 6).
        config.addJobServiceConfigurator(new JobServiceConfigurator());
        // Load-bearing, not cleanup -- message/signal start-event dispatch depends on this
        // (docs/ntrloc-workflow-summary.md Sections 4/5).
        config.addEventSubscriptionServiceConfigurator(new EventSubscriptionServiceConfigurator());

        return config;
    }

    @Bean
    public ProcessEngine processEngine(SpringProcessEngineConfiguration processEngineConfiguration) {
        return processEngineConfiguration.buildEngine();
    }

    @Bean
    public RuntimeService runtimeService(ProcessEngine processEngine) {
        return processEngine.getRuntimeService();
    }

    @Bean
    public TaskService taskService(ProcessEngine processEngine) {
        return processEngine.getTaskService();
    }

    @Bean
    public RepositoryService repositoryService(ProcessEngine processEngine) {
        return processEngine.getRepositoryService();
    }

    @Bean
    public HistoryService historyService(ProcessEngine processEngine) {
        return processEngine.getHistoryService();
    }
}
