package org.ntrloc.graph.db.partition.process.dmn.persistence;

import jakarta.annotation.PostConstruct;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

// DMN-side equivalent of ProcessPersistenceInitializer -- owns the tables backing the custom
// Flowable DMN DataManagers (deployments, resources, decision/decision-table definitions, and the
// audit trail Flowable writes on every decision evaluation). Drop-and-recreate on every boot,
// matching every other partition's dev-mode initializer in this codebase. Mirrors
// process_deployment/process_resource/process_definition's column set and naming convention
// exactly; decision_historic_execution has no process-engine analog (history is disabled there)
// so its columns instead mirror Flowable's own ACT_DMN_HI_DECISION_EXECUTION, trimmed to what
// DmnActivityBehavior's executeWithAuditTrail() actually writes.
@Component
public class DecisionPersistenceInitializer {

    private final JdbcClient jdbcClient;

    public DecisionPersistenceInitializer(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @PostConstruct
    void init() {
        jdbcClient.sql("DROP TABLE IF EXISTS decision_historic_execution").update();
        jdbcClient.sql("DROP TABLE IF EXISTS decision_definition").update();
        jdbcClient.sql("DROP TABLE IF EXISTS decision_resource").update();
        jdbcClient.sql("DROP TABLE IF EXISTS decision_deployment").update();

        jdbcClient.sql("""
                CREATE TABLE decision_deployment (
                    id              VARCHAR(64) PRIMARY KEY,
                    name            VARCHAR(255),
                    deployment_time TIMESTAMPTZ
                )
                """).update();

        jdbcClient.sql("""
                CREATE TABLE decision_resource (
                    id            VARCHAR(64) PRIMARY KEY,
                    deployment_id VARCHAR(64) NOT NULL,
                    name          VARCHAR(255) NOT NULL,
                    bytes         BYTEA NOT NULL
                )
                """).update();

        // No revision column: unlike ProcessDefinitionEntity, Flowable's DecisionEntity does not
        // implement HasRevision (verified via javap) -- no optimistic-locking support to back.
        jdbcClient.sql("""
                CREATE TABLE decision_definition (
                    id            VARCHAR(64) PRIMARY KEY,
                    deployment_id VARCHAR(64),
                    decision_key  VARCHAR(255) NOT NULL,
                    name          VARCHAR(255),
                    version       INT NOT NULL,
                    resource_name VARCHAR(255)
                )
                """).update();

        // execution_json holds Flowable's own serialized audit-trail payload (rule/input/output
        // values for the evaluation) -- written but never read back in this app; no UI surfaces it.
        jdbcClient.sql("""
                CREATE TABLE decision_historic_execution (
                    id                     VARCHAR(64) PRIMARY KEY,
                    decision_definition_id VARCHAR(64),
                    deployment_id          VARCHAR(64),
                    start_time             TIMESTAMPTZ,
                    end_time               TIMESTAMPTZ,
                    instance_id            VARCHAR(64),
                    execution_id           VARCHAR(64),
                    activity_id            VARCHAR(255),
                    scope_type             VARCHAR(255),
                    failed                 BOOLEAN NOT NULL DEFAULT FALSE,
                    tenant_id              VARCHAR(255),
                    execution_json         TEXT
                )
                """).update();
        jdbcClient.sql("CREATE INDEX ON decision_historic_execution (decision_definition_id)").update();
        jdbcClient.sql("CREATE INDEX ON decision_historic_execution (instance_id)").update();
    }
}
