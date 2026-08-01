package org.ntrloc.graph.db.partition.process.persistence;

import jakarta.annotation.PostConstruct;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

@Component
public class ProcessPersistenceInitializer {

    private final JdbcClient jdbcClient;

    public ProcessPersistenceInitializer(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @PostConstruct
    void init() {
        jdbcClient.sql("""
                CREATE TABLE IF NOT EXISTS process_deployment (
                    id              VARCHAR(64) PRIMARY KEY,
                    name            VARCHAR(255),
                    deployment_time TIMESTAMPTZ
                )
                """).update();

        jdbcClient.sql("""
                CREATE TABLE IF NOT EXISTS process_resource (
                    id            VARCHAR(64) PRIMARY KEY,
                    deployment_id VARCHAR(64) NOT NULL,
                    name          VARCHAR(255) NOT NULL,
                    bytes         BYTEA NOT NULL
                )
                """).update();

        jdbcClient.sql("""
                CREATE TABLE IF NOT EXISTS process_definition (
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
                CREATE TABLE IF NOT EXISTS process_execution (
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
        jdbcClient.sql("CREATE INDEX IF NOT EXISTS process_execution_proc_inst_idx ON process_execution (process_instance_id)").update();
        jdbcClient.sql("CREATE INDEX IF NOT EXISTS process_execution_parent_idx ON process_execution (parent_id)").update();

        jdbcClient.sql("""
                CREATE TABLE IF NOT EXISTS process_variable (
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
        jdbcClient.sql("CREATE INDEX IF NOT EXISTS process_variable_execution_idx ON process_variable (execution_id)").update();

        jdbcClient.sql("""
                CREATE TABLE IF NOT EXISTS process_task (
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
        jdbcClient.sql("CREATE INDEX IF NOT EXISTS process_task_execution_idx ON process_task (execution_id)").update();
        jdbcClient.sql("CREATE INDEX IF NOT EXISTS process_task_proc_inst_idx ON process_task (process_instance_id)").update();

        jdbcClient.sql("""
                CREATE TABLE IF NOT EXISTS process_task_identitylink (
                    id                  VARCHAR(64) PRIMARY KEY,
                    task_id             VARCHAR(64),
                    process_instance_id VARCHAR(64),
                    type                VARCHAR(64) NOT NULL,
                    user_id             VARCHAR(255),
                    group_id            VARCHAR(255)
                )
                """).update();
        jdbcClient.sql("CREATE INDEX IF NOT EXISTS process_task_identitylink_task_idx ON process_task_identitylink (task_id)").update();
        jdbcClient.sql("CREATE INDEX IF NOT EXISTS process_task_identitylink_proc_inst_idx ON process_task_identitylink (process_instance_id)").update();

        jdbcClient.sql("""
                CREATE TABLE IF NOT EXISTS process_job (
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
        jdbcClient.sql("CREATE INDEX IF NOT EXISTS process_job_kind_lock_idx ON process_job (job_kind, lock_expiration_time)").update();
        jdbcClient.sql("CREATE INDEX IF NOT EXISTS process_job_execution_idx ON process_job (execution_id)").update();
        jdbcClient.sql("CREATE INDEX IF NOT EXISTS process_job_proc_inst_idx ON process_job (process_instance_id)").update();
        jdbcClient.sql("CREATE INDEX IF NOT EXISTS process_job_correlation_idx ON process_job (correlation_id)").update();

        jdbcClient.sql("""
                CREATE TABLE IF NOT EXISTS process_event_subscription (
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
        jdbcClient.sql("CREATE INDEX IF NOT EXISTS process_event_sub_type_name_idx ON process_event_subscription (event_type, event_name)").update();
        jdbcClient.sql("CREATE INDEX IF NOT EXISTS process_event_sub_execution_idx ON process_event_subscription (execution_id)").update();
        jdbcClient.sql("CREATE INDEX IF NOT EXISTS process_event_sub_proc_inst_idx ON process_event_subscription (process_instance_id)").update();

        jdbcClient.sql("""
                CREATE TABLE IF NOT EXISTS process_activity_instance (
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
        jdbcClient.sql("CREATE INDEX IF NOT EXISTS process_activity_inst_proc_inst_idx ON process_activity_instance (process_instance_id)").update();
        jdbcClient.sql("CREATE INDEX IF NOT EXISTS process_activity_inst_exec_act_idx ON process_activity_instance (execution_id, activity_id)").update();

        jdbcClient.sql("""
                CREATE TABLE IF NOT EXISTS process_property (
                    name     VARCHAR(255) PRIMARY KEY,
                    revision INT NOT NULL DEFAULT 1,
                    value    TEXT
                )
                """).update();

        jdbcClient.sql("""
                CREATE TABLE IF NOT EXISTS process_definition_info (
                    id                    VARCHAR(64) PRIMARY KEY,
                    revision              INT NOT NULL DEFAULT 1,
                    process_definition_id VARCHAR(64) NOT NULL,
                    info_json_id          VARCHAR(64)
                )
                """).update();
        jdbcClient.sql("CREATE INDEX IF NOT EXISTS process_def_info_proc_def_idx ON process_definition_info (process_definition_id)").update();
    }
}
