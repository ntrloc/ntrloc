package org.ntrloc.graph.db.partition.process.persistence;

import jakarta.annotation.PostConstruct;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

// Owns the tables backing the custom Flowable DataManagers (docs/ntrloc-workflow-summary.md
// Section 3) -- deployments, resources (the raw BPMN/DMN/CMMN bytes), process definitions,
// executions, variables, tasks, and task identity links (candidate/assignee records). Drop-and-
// recreate on every boot, matching every other partition's dev-mode initializer (schema/register/
// security/ledger) in this codebase. Deliberately minimal columns: only what this app's processes
// actually exercise; everything else round-trips as Java defaults for now. No foreign keys between
// these tables (matching the existing convention here) -- Flowable's own native ACT_* tables are
// where real FK enforcement lives, and Task/IdentityLink no longer touch those at all now that
// they're on process_task/process_task_identitylink instead.
@Component
public class ProcessPersistenceInitializer {

    private final JdbcClient jdbcClient;

    public ProcessPersistenceInitializer(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @PostConstruct
    void init() {
        jdbcClient.sql("DROP TABLE IF EXISTS process_definition_info").update();
        jdbcClient.sql("DROP TABLE IF EXISTS process_property").update();
        jdbcClient.sql("DROP TABLE IF EXISTS process_activity_instance").update();
        jdbcClient.sql("DROP TABLE IF EXISTS process_event_subscription").update();
        jdbcClient.sql("DROP TABLE IF EXISTS process_job").update();
        jdbcClient.sql("DROP TABLE IF EXISTS process_task_identitylink").update();
        jdbcClient.sql("DROP TABLE IF EXISTS process_task").update();
        jdbcClient.sql("DROP TABLE IF EXISTS process_variable").update();
        jdbcClient.sql("DROP TABLE IF EXISTS process_execution").update();
        jdbcClient.sql("DROP TABLE IF EXISTS process_definition").update();
        jdbcClient.sql("DROP TABLE IF EXISTS process_resource").update();
        jdbcClient.sql("DROP TABLE IF EXISTS process_deployment").update();

        jdbcClient.sql("""
                CREATE TABLE process_deployment (
                    id              VARCHAR(64) PRIMARY KEY,
                    name            VARCHAR(255),
                    deployment_time TIMESTAMPTZ
                )
                """).update();

        jdbcClient.sql("""
                CREATE TABLE process_resource (
                    id            VARCHAR(64) PRIMARY KEY,
                    deployment_id VARCHAR(64) NOT NULL,
                    name          VARCHAR(255) NOT NULL,
                    bytes         BYTEA NOT NULL
                )
                """).update();

        jdbcClient.sql("""
                CREATE TABLE process_definition (
                    id            VARCHAR(64) PRIMARY KEY,
                    deployment_id VARCHAR(64),
                    process_key   VARCHAR(255) NOT NULL,
                    name          VARCHAR(255),
                    version       INT NOT NULL,
                    resource_name VARCHAR(255),
                    revision      INT NOT NULL DEFAULT 1
                )
                """).update();

        jdbcClient.sql("""
                CREATE TABLE process_execution (
                    id                       VARCHAR(64) PRIMARY KEY,
                    revision                 INT NOT NULL DEFAULT 1,
                    process_instance_id      VARCHAR(64),
                    parent_id                VARCHAR(64),
                    root_process_instance_id VARCHAR(64),
                    super_execution_id       VARCHAR(64),
                    process_definition_id    VARCHAR(64),
                    business_key             VARCHAR(255),
                    activity_id              VARCHAR(255),
                    is_active                BOOLEAN NOT NULL DEFAULT TRUE,
                    is_scope                 BOOLEAN NOT NULL DEFAULT FALSE,
                    is_concurrent            BOOLEAN NOT NULL DEFAULT FALSE,
                    is_ended                 BOOLEAN NOT NULL DEFAULT FALSE
                )
                """).update();
        jdbcClient.sql("CREATE INDEX ON process_execution (process_instance_id)").update();
        jdbcClient.sql("CREATE INDEX ON process_execution (parent_id)").update();

        jdbcClient.sql("""
                CREATE TABLE process_variable (
                    id                  VARCHAR(64) PRIMARY KEY,
                    execution_id        VARCHAR(64),
                    process_instance_id VARCHAR(64),
                    name                VARCHAR(255) NOT NULL,
                    type_name           VARCHAR(64),
                    text_value          TEXT,
                    long_value          BIGINT,
                    double_value        DOUBLE PRECISION
                )
                """).update();
        jdbcClient.sql("CREATE INDEX ON process_variable (execution_id)").update();

        jdbcClient.sql("""
                CREATE TABLE process_task (
                    id                     VARCHAR(64) PRIMARY KEY,
                    name                   VARCHAR(255),
                    assignee               VARCHAR(255),
                    create_time            TIMESTAMPTZ,
                    execution_id           VARCHAR(64),
                    process_instance_id    VARCHAR(64),
                    process_definition_id  VARCHAR(64),
                    task_definition_key    VARCHAR(255)
                )
                """).update();
        jdbcClient.sql("CREATE INDEX ON process_task (execution_id)").update();
        jdbcClient.sql("CREATE INDEX ON process_task (process_instance_id)").update();

        // type distinguishes "candidate" (a task's pool of eligible claimants) from "assignee"/
        // "owner" (Flowable also records the current assignee as an identity link, in addition to
        // process_task.assignee itself) -- matches Flowable's own IdentityLinkType convention.
        // task_id/process_instance_id are both nullable and mutually exclusive in practice:
        // Flowable also writes process-instance-scoped links here (type "participant", recording
        // that a user claimed/completed *some* task in the instance, not tied to any one task) --
        // confirmed empirically when claim/complete started failing with a NOT NULL violation on
        // task_id for exactly these rows.
        jdbcClient.sql("""
                CREATE TABLE process_task_identitylink (
                    id                  VARCHAR(64) PRIMARY KEY,
                    task_id             VARCHAR(64),
                    process_instance_id VARCHAR(64),
                    type                VARCHAR(64) NOT NULL,
                    user_id             VARCHAR(255),
                    group_id            VARCHAR(255)
                )
                """).update();
        jdbcClient.sql("CREATE INDEX ON process_task_identitylink (task_id)").update();
        jdbcClient.sql("CREATE INDEX ON process_task_identitylink (process_instance_id)").update();

        // Backs all six of Flowable's job kinds (ACT_RU_JOB/_TIMER_JOB/_SUSPENDED_JOB/
        // _DEADLETTER_JOB/_HISTORY_JOB/_EXTERNAL_JOB) with one discriminated table -- a "move"
        // between kinds (e.g. a job exhausting its retries and going to dead-letter) is then a
        // plain UPDATE of job_kind rather than a delete+insert across separate tables, matching
        // this table's existing process_task_identitylink discriminator convention. The async
        // executor is never activated (see ProcessEngineConfig), so this table stays empty in
        // practice today; it exists so a future timer/async-marked process element has somewhere
        // real to write instead of falling through to Flowable's own ACT_RU_JOB.
        jdbcClient.sql("""
                CREATE TABLE process_job (
                    id                          VARCHAR(64) PRIMARY KEY,
                    revision                    INT NOT NULL DEFAULT 1,
                    job_kind                    VARCHAR(32) NOT NULL,
                    category                    VARCHAR(255),
                    job_type                    VARCHAR(255),
                    job_handler_type            VARCHAR(255),
                    job_handler_configuration   TEXT,
                    lock_owner                  VARCHAR(255),
                    lock_expiration_time        TIMESTAMPTZ,
                    is_exclusive                BOOLEAN NOT NULL DEFAULT TRUE,
                    execution_id                VARCHAR(64),
                    process_instance_id         VARCHAR(64),
                    process_definition_id       VARCHAR(64),
                    element_id                  VARCHAR(255),
                    element_name                VARCHAR(255),
                    scope_id                    VARCHAR(255),
                    sub_scope_id                VARCHAR(255),
                    scope_type                  VARCHAR(255),
                    scope_definition_id         VARCHAR(255),
                    correlation_id               VARCHAR(255),
                    retries                     INT NOT NULL DEFAULT 0,
                    exception_message            TEXT,
                    due_date                     TIMESTAMPTZ,
                    repeat_cycle                 VARCHAR(255),
                    end_date                     TIMESTAMPTZ,
                    max_iterations               INT,
                    create_time                  TIMESTAMPTZ
                )
                """).update();
        jdbcClient.sql("CREATE INDEX ON process_job (job_kind, lock_expiration_time)").update();
        jdbcClient.sql("CREATE INDEX ON process_job (execution_id)").update();
        jdbcClient.sql("CREATE INDEX ON process_job (process_instance_id)").update();
        jdbcClient.sql("CREATE INDEX ON process_job (correlation_id)").update();

        // Backs message/signal start-event subscriptions (ACT_RU_EVENT_SUBSCR) -- load-bearing for
        // RuntimeService.startProcessInstanceByMessage and signal dispatch (docs/
        // ntrloc-workflow-summary.md Sections 4/5), not just cleanup. Only the columns BPMN
        // message/signal start events actually exercise -- CMMN-scope and lock-time columns
        // Flowable's own ACT_RU_EVENT_SUBSCR carries are omitted.
        jdbcClient.sql("""
                CREATE TABLE process_event_subscription (
                    id                     VARCHAR(64) PRIMARY KEY,
                    revision               INT NOT NULL DEFAULT 1,
                    event_type             VARCHAR(64) NOT NULL,
                    event_name             VARCHAR(255),
                    execution_id           VARCHAR(64),
                    process_instance_id    VARCHAR(64),
                    activity_id            VARCHAR(255),
                    configuration          VARCHAR(255),
                    created                TIMESTAMPTZ NOT NULL,
                    process_definition_id  VARCHAR(64)
                )
                """).update();
        jdbcClient.sql("CREATE INDEX ON process_event_subscription (event_type, event_name)").update();
        jdbcClient.sql("CREATE INDEX ON process_event_subscription (execution_id)").update();
        jdbcClient.sql("CREATE INDEX ON process_event_subscription (process_instance_id)").update();

        // Backs live "currently active activity" tracking (ACT_RU_ACTINST) -- unlike the historic
        // ACT_HI_ACTINST equivalent (which stays off, history is disabled), this one is written
        // unconditionally by Flowable's own ContinueProcessOperation on every activity transition,
        // no history-level gate -- mandatory, not optional cleanup.
        jdbcClient.sql("""
                CREATE TABLE process_activity_instance (
                    id                        VARCHAR(64) PRIMARY KEY,
                    revision                  INT NOT NULL DEFAULT 1,
                    process_instance_id       VARCHAR(64) NOT NULL,
                    process_definition_id     VARCHAR(64) NOT NULL,
                    execution_id              VARCHAR(64) NOT NULL,
                    activity_id               VARCHAR(255) NOT NULL,
                    activity_name             VARCHAR(255),
                    activity_type             VARCHAR(255) NOT NULL,
                    task_id                   VARCHAR(64),
                    assignee                  VARCHAR(255),
                    completed_by              VARCHAR(255),
                    start_time                TIMESTAMPTZ NOT NULL,
                    end_time                  TIMESTAMPTZ,
                    duration_millis           BIGINT,
                    transaction_order         INT,
                    delete_reason             VARCHAR(4000),
                    called_process_instance_id VARCHAR(64)
                )
                """).update();
        jdbcClient.sql("CREATE INDEX ON process_activity_instance (process_instance_id)").update();
        jdbcClient.sql("CREATE INDEX ON process_activity_instance (execution_id, activity_id)").update();

        // Backs Flowable's generic config-property bookkeeping (ACT_GE_PROPERTY) -- discovered
        // live, not anticipated: ValidateExecutionRelatedEntityCountCfgCmd looks up
        // "cfg.execution-related-entities-count" unconditionally during every buildEngine() call,
        // completely independent of schema management or the id generator (both already handled in
        // ProcessEngineConfig) -- a third, separate path to this table. PropertyEntity's own id IS
        // its name (PropertyEntityImpl.setId() throws; getId() returns the name field directly), so
        // name is the primary key, not a generated id.
        jdbcClient.sql("""
                CREATE TABLE process_property (
                    name     VARCHAR(255) PRIMARY KEY,
                    revision INT NOT NULL DEFAULT 1,
                    value    TEXT
                )
                """).update();

        // Backs process-definition "info cache" overrides (ACT_PROCDEF_INFO) -- also discovered
        // live: BpmnDeployer.createLocalizationValues() calls
        // DynamicBpmnServiceImpl.getProcessDefinitionInfo() unconditionally on every deploy
        // (including our own hello-world.bpmn20.xml resource loaded at boot), not gated behind
        // isEnableProcessDefinitionInfoCache() the way earlier analysis assumed -- that flag turned
        // out to control something narrower than the lookup itself. Nothing in ntrloc ever creates
        // an override, so this table stays empty; it just needs to exist and answer "no override"
        // (null) correctly.
        jdbcClient.sql("""
                CREATE TABLE process_definition_info (
                    id                    VARCHAR(64) PRIMARY KEY,
                    revision              INT NOT NULL DEFAULT 1,
                    process_definition_id VARCHAR(64) NOT NULL,
                    info_json_id          VARCHAR(64)
                )
                """).update();
        jdbcClient.sql("CREATE INDEX ON process_definition_info (process_definition_id)").update();
    }
}
