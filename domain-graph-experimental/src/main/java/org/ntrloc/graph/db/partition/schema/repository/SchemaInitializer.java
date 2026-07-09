package org.ntrloc.graph.db.partition.schema.repository;

import jakarta.annotation.PostConstruct;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

@Component
public class SchemaInitializer {

    private final JdbcClient jdbcClient;

    public SchemaInitializer(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @PostConstruct
    void initSchema() {
        dropAllTables();
        initEntityTable();
        initItemTable();
        initTraitTable();
        initLinkTable();
        initControlledListTable();
        initPropertyTable();
        initItemPropertyTable();
        initTraitPropertyTable();
        initItemTraitTable();
        initLinkPropertyTable();
        initEntityLinkPerspectiveTable();
    }

    void dropAllTables() {
        jdbcClient.sql("DROP TABLE IF EXISTS schema_entity_link_perspective CASCADE").update();
        jdbcClient.sql("DROP TABLE IF EXISTS schema_item_link_perspective CASCADE").update();
        jdbcClient.sql("DROP TABLE IF EXISTS schema_item_property CASCADE").update();
        jdbcClient.sql("DROP TABLE IF EXISTS schema_trait_property CASCADE").update();
        jdbcClient.sql("DROP TABLE IF EXISTS schema_item_trait CASCADE").update();
        jdbcClient.sql("DROP TABLE IF EXISTS schema_link_property CASCADE").update();
        jdbcClient.sql("DROP TABLE IF EXISTS schema_property CASCADE").update();
        jdbcClient.sql("DROP TABLE IF EXISTS schema_link CASCADE").update();
        jdbcClient.sql("DROP TABLE IF EXISTS schema_item CASCADE").update();
        jdbcClient.sql("DROP TABLE IF EXISTS schema_trait CASCADE").update();
        jdbcClient.sql("DROP TABLE IF EXISTS schema_entity CASCADE").update();
        jdbcClient.sql("""
                SELECT table_name FROM information_schema.tables
                WHERE table_schema = 'public' AND table_name LIKE 'schema_controlled_list_%'
                """)
                .query(String.class).list()
                .forEach(t -> jdbcClient.sql("DROP TABLE IF EXISTS " + t).update());
        jdbcClient.sql("DROP TABLE IF EXISTS schema_controlled_list CASCADE").update();
    }

    void initEntityTable() {
        jdbcClient.sql("""
                CREATE TABLE IF NOT EXISTS schema_entity (
                    id UUID PRIMARY KEY DEFAULT uuidv7()
                )
                """).update();
    }

    void initItemTable() {
        jdbcClient.sql("""
                CREATE TABLE IF NOT EXISTS schema_item (
                    id          UUID PRIMARY KEY DEFAULT uuidv7(),
                    entity_id   UUID NOT NULL UNIQUE REFERENCES schema_entity(id) ON DELETE CASCADE,
                    name        TEXT NOT NULL UNIQUE,
                    description TEXT
                )
                """).update();
    }

    void initTraitTable() {
        jdbcClient.sql("""
                CREATE TABLE IF NOT EXISTS schema_trait (
                    id          UUID PRIMARY KEY DEFAULT uuidv7(),
                    entity_id   UUID NOT NULL UNIQUE REFERENCES schema_entity(id) ON DELETE CASCADE,
                    name        TEXT NOT NULL UNIQUE,
                    description TEXT
                )
                """).update();
    }

    void initLinkTable() {
        jdbcClient.sql("""
                CREATE TABLE IF NOT EXISTS schema_link (
                    id UUID PRIMARY KEY DEFAULT uuidv7()
                )
                """).update();
    }

    void initControlledListTable() {
        jdbcClient.sql("""
                CREATE TABLE IF NOT EXISTS schema_controlled_list (
                    id         UUID PRIMARY KEY DEFAULT uuidv7(),
                    name       TEXT NOT NULL,
                    value_type TEXT NOT NULL
                )
                """).update();
    }

    void initPropertyTable() {
        jdbcClient.sql("""
                CREATE TABLE IF NOT EXISTS schema_property (
                    id                 UUID PRIMARY KEY DEFAULT uuidv7(),
                    name               TEXT NOT NULL UNIQUE,
                    description        TEXT,
                    type               TEXT NOT NULL,
                    cardinality        TEXT NOT NULL,
                    usage              TEXT NOT NULL,
                    controlled_list_id UUID REFERENCES schema_controlled_list(id) ON DELETE SET NULL
                )
                """).update();
    }

    void initItemPropertyTable() {
        jdbcClient.sql("""
                CREATE TABLE IF NOT EXISTS schema_item_property (
                    item_definition_id UUID NOT NULL REFERENCES schema_item(id) ON DELETE CASCADE,
                    property_id        UUID NOT NULL REFERENCES schema_property(id) ON DELETE CASCADE,
                    PRIMARY KEY (item_definition_id, property_id)
                )
                """).update();
    }

    void initTraitPropertyTable() {
        jdbcClient.sql("""
                CREATE TABLE IF NOT EXISTS schema_trait_property (
                    trait_id    UUID NOT NULL REFERENCES schema_trait(id) ON DELETE CASCADE,
                    property_id UUID NOT NULL REFERENCES schema_property(id) ON DELETE CASCADE,
                    PRIMARY KEY (trait_id, property_id)
                )
                """).update();
    }

    void initItemTraitTable() {
        jdbcClient.sql("""
                CREATE TABLE IF NOT EXISTS schema_item_trait (
                    item_id  UUID NOT NULL REFERENCES schema_item(id) ON DELETE CASCADE,
                    trait_id UUID NOT NULL REFERENCES schema_trait(id) ON DELETE CASCADE,
                    PRIMARY KEY (item_id, trait_id)
                )
                """).update();
    }

    void initLinkPropertyTable() {
        jdbcClient.sql("""
                CREATE TABLE IF NOT EXISTS schema_link_property (
                    link_definition_id UUID NOT NULL REFERENCES schema_link(id) ON DELETE CASCADE,
                    property_id        UUID NOT NULL REFERENCES schema_property(id) ON DELETE CASCADE,
                    PRIMARY KEY (link_definition_id, property_id)
                )
                """).update();
    }

    void initEntityLinkPerspectiveTable() {
        jdbcClient.sql("""
                CREATE TABLE IF NOT EXISTS schema_entity_link_perspective (
                    id                  UUID PRIMARY KEY DEFAULT uuidv7(),
                    entity_id           UUID NOT NULL REFERENCES schema_entity(id) ON DELETE CASCADE,
                    link_definition_id  UUID NOT NULL REFERENCES schema_link(id) ON DELETE CASCADE,
                    name                TEXT NOT NULL,
                    description         TEXT,
                    minimum_cardinality INT NOT NULL CHECK (minimum_cardinality >= 0),
                    maximum_cardinality INT CHECK (maximum_cardinality IS NULL OR maximum_cardinality >= minimum_cardinality),
                    UNIQUE (entity_id, link_definition_id)
                )
                """).update();
    }
}
