package org.ntrloc.graph.schema.repository.impl;

import jakarta.annotation.PostConstruct;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

@Component
public class JdbcSchemaInitializer {

    private final JdbcClient jdbcClient;

    public JdbcSchemaInitializer(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @PostConstruct
    void initSchema() {
        dropAllTables();
        initItemTable();
        initLinkTable();
        initPropertyTable();
        initItemPropertyTable();
        initLinkPropertyTable();
        initItemLinkPerspectiveTable();
    }

    void dropAllTables() {
        jdbcClient.sql("DROP TABLE IF EXISTS schema_item_link_perspective").update();
        jdbcClient.sql("DROP TABLE IF EXISTS schema_item_property").update();
        jdbcClient.sql("DROP TABLE IF EXISTS schema_link_property").update();
        jdbcClient.sql("DROP TABLE IF EXISTS schema_property").update();
        jdbcClient.sql("DROP TABLE IF EXISTS schema_link").update();
        jdbcClient.sql("DROP TABLE IF EXISTS schema_item").update();
    }

    void initItemTable() {
        jdbcClient.sql("""
                CREATE TABLE IF NOT EXISTS schema_item (
                    id          UUID PRIMARY KEY DEFAULT uuidv7(),
                    name        TEXT NOT NULL UNIQUE,
                    description TEXT
                )
                """)
                .update();
    }

    void initLinkTable() {
        jdbcClient.sql("""
                CREATE TABLE IF NOT EXISTS schema_link (
                    id UUID PRIMARY KEY DEFAULT uuidv7()
                )
                """)
                .update();
    }

    void initPropertyTable() {
        jdbcClient.sql("""
                CREATE TABLE IF NOT EXISTS schema_property (
                    id          UUID PRIMARY KEY DEFAULT uuidv7(),
                    name        TEXT NOT NULL UNIQUE,
                    description TEXT,
                    type        TEXT NOT NULL,
                    cardinality TEXT NOT NULL,
                    requirement TEXT NOT NULL
                )
                """)
                .update();
    }

    void initItemPropertyTable() {
        jdbcClient.sql("""
                CREATE TABLE IF NOT EXISTS schema_item_property (
                    item_definition_id UUID NOT NULL REFERENCES schema_item(id) ON DELETE CASCADE,
                    property_id        UUID NOT NULL REFERENCES schema_property(id) ON DELETE CASCADE,
                    PRIMARY KEY (item_definition_id, property_id)
                )
                """)
                .update();
    }

    void initLinkPropertyTable() {
        jdbcClient.sql("""
                CREATE TABLE IF NOT EXISTS schema_link_property (
                    link_definition_id UUID NOT NULL REFERENCES schema_link(id) ON DELETE CASCADE,
                    property_id        UUID NOT NULL REFERENCES schema_property(id) ON DELETE CASCADE,
                    PRIMARY KEY (link_definition_id, property_id)
                )
                """)
                .update();
    }

    void initItemLinkPerspectiveTable() {
        jdbcClient.sql("""
                CREATE TABLE IF NOT EXISTS schema_item_link_perspective (
                    id                  UUID PRIMARY KEY DEFAULT uuidv7(),
                    item_definition_id  UUID NOT NULL REFERENCES schema_item(id) ON DELETE CASCADE,
                    link_definition_id  UUID NOT NULL REFERENCES schema_link(id) ON DELETE CASCADE,
                    name                TEXT NOT NULL,
                    description         TEXT,
                    minimum_cardinality INT NOT NULL CHECK (minimum_cardinality >= 0),
                    maximum_cardinality INT CHECK (maximum_cardinality IS NULL OR maximum_cardinality >= minimum_cardinality),
                    UNIQUE (item_definition_id, link_definition_id)
                )
                """)
                .update();
    }

}
