package org.ntrloc.graph.db.partition.register;

import jakarta.annotation.PostConstruct;
import org.springframework.context.annotation.DependsOn;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

@Component
@DependsOn("schemaManager")
public class RegisterInitializer {

    private final JdbcClient jdbcClient;

    public RegisterInitializer(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @PostConstruct
    void init() {
        createRegisterItemTable();
        createRegisterLinkTable();
        createItemLinkPerspectiveTable();
        createBinaryPropertyTable();
    }

    private void createRegisterItemTable() {
        jdbcClient.sql("""
                CREATE TABLE IF NOT EXISTS register_item (
                    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                    item_id         UUID NOT NULL,
                    item_type_id    UUID NOT NULL REFERENCES schema_item(id),
                    state           TEXT NOT NULL,
                    transaction_id  UUID,
                    commit_id       UUID,
                    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
                )
                """).update();
    }

    private void createRegisterLinkTable() {
        jdbcClient.sql("""
                CREATE TABLE IF NOT EXISTS register_link (
                    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                    link_id             UUID NOT NULL,
                    link_definition_id  UUID NOT NULL REFERENCES schema_link(id),
                    state               TEXT NOT NULL,
                    transaction_id      UUID,
                    commit_id           UUID,
                    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                    updated_at          TIMESTAMPTZ NOT NULL DEFAULT NOW()
                )
                """).update();
        jdbcClient.sql("CREATE INDEX IF NOT EXISTS register_link_link_id_idx ON register_link (link_id)").update();
    }

    private void createItemLinkPerspectiveTable() {
        jdbcClient.sql("""
                CREATE TABLE IF NOT EXISTS register_item_link_perspective (
                    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                    register_link_id    UUID NOT NULL REFERENCES register_link(id) ON DELETE CASCADE,
                    perspective_id      UUID NOT NULL REFERENCES schema_entity_link_perspective(id),
                    register_item_id    UUID NOT NULL REFERENCES register_item(id)
                )
                """).update();
    }

    private void createBinaryPropertyTable() {
        jdbcClient.sql("""
                CREATE TABLE IF NOT EXISTS register_binary_property (
                    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                    register_item_id UUID NOT NULL REFERENCES register_item(id) ON DELETE CASCADE,
                    property_id      UUID NOT NULL REFERENCES schema_property(id),
                    binary_id        UUID NOT NULL
                )
                """).update();
        jdbcClient.sql("CREATE INDEX IF NOT EXISTS register_binary_property_item_idx ON register_binary_property (register_item_id)").update();
    }
}
