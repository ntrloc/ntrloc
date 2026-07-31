package org.ntrloc.graph.db.partition.process.dmn.persistence;

import jakarta.annotation.PostConstruct;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

@Component
public class DecisionPersistenceInitializer {

    private final JdbcClient jdbcClient;

    public DecisionPersistenceInitializer(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @PostConstruct
    void init() {
        jdbcClient.sql("""
                CREATE TABLE IF NOT EXISTS decision_deployment (
                    id              VARCHAR(64) PRIMARY KEY,
                    name            VARCHAR(255),
                    deployment_time TIMESTAMPTZ
                )
                """).update();

        jdbcClient.sql("""
                CREATE TABLE IF NOT EXISTS decision_resource (
                    id            VARCHAR(64) PRIMARY KEY,
                    deployment_id VARCHAR(64) NOT NULL,
                    name          VARCHAR(255) NOT NULL,
                    bytes         BYTEA NOT NULL
                )
                """).update();

        jdbcClient.sql("""
                CREATE TABLE IF NOT EXISTS decision_definition (
                    id            VARCHAR(64) PRIMARY KEY,
                    deployment_id VARCHAR(64),
                    decision_key  VARCHAR(255) NOT NULL,
                    name          VARCHAR(255),
                    version       INT NOT NULL,
                    resource_name VARCHAR(255)
                )
                """).update();

        jdbcClient.sql("""
                CREATE TABLE IF NOT EXISTS decision_historic_execution (
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
        jdbcClient.sql("CREATE INDEX IF NOT EXISTS decision_hist_exec_def_idx ON decision_historic_execution (decision_definition_id)").update();
        jdbcClient.sql("CREATE INDEX IF NOT EXISTS decision_hist_exec_inst_idx ON decision_historic_execution (instance_id)").update();
    }
}
