package org.ntrloc.graph.db.partition.register;

import jakarta.annotation.PostConstruct;
import org.ntrloc.graph.db.partition.binary.BinaryPartitionManager;
import org.ntrloc.graph.domain.DomainInitializer;
import org.springframework.context.annotation.DependsOn;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Component
@DependsOn("schemaManager")
public class RegisterInitializer {

    private final JdbcClient jdbcClient;
    private final BinaryPartitionManager binaryPartitionManager;
    private final Optional<DomainInitializer> domainInitializer;

    public RegisterInitializer(JdbcClient jdbcClient, BinaryPartitionManager binaryPartitionManager, Optional<DomainInitializer> domainInitializer) {
        this.jdbcClient = jdbcClient;
        this.binaryPartitionManager = binaryPartitionManager;
        this.domainInitializer = domainInitializer;
    }

    @PostConstruct
    void init() {
        dropAllRegisterTables();
        createRegisterItemTable();
        createRegisterLinkTable();
        createPerItemTypeTables();
        createPerLinkTypeTables();
        createItemLinkPerspectiveTable();
        createBinaryPropertyTable();
        domainInitializer.ifPresent(d -> d.initData(jdbcClient, binaryPartitionManager));
    }

    // --- Drop ---

    private void dropAllRegisterTables() {
        jdbcClient.sql("DROP TABLE IF EXISTS register_binary_property").update();
        jdbcClient.sql("DROP TABLE IF EXISTS register_item_link_perspective").update();

        List<String> allTables = jdbcClient.sql("""
                SELECT table_name FROM information_schema.tables
                WHERE table_schema = 'public'
                  AND (table_name LIKE 'register_link_%' OR table_name LIKE 'register_item_%')
                ORDER BY table_name
                """)
                .query(String.class)
                .list();

        allTables.stream()
                .filter(t -> t.matches("register_link_[0-9a-f_]+"))
                .forEach(t -> jdbcClient.sql("DROP TABLE IF EXISTS " + t).update());

        jdbcClient.sql("DROP TABLE IF EXISTS register_link").update();

        allTables.stream()
                .filter(t -> t.matches("register_item_[0-9a-f_]+"))
                .forEach(t -> jdbcClient.sql("DROP TABLE IF EXISTS " + t).update());

        jdbcClient.sql("DROP TABLE IF EXISTS register_item").update();
    }

    // --- Create ---

    private void createRegisterItemTable() {
        jdbcClient.sql("""
                CREATE TABLE register_item (
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
                CREATE TABLE register_link (
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
        jdbcClient.sql("CREATE INDEX ON register_link (link_id)").update();
    }

    private void createItemLinkPerspectiveTable() {
        jdbcClient.sql("""
                CREATE TABLE register_item_link_perspective (
                    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                    register_link_id    UUID NOT NULL REFERENCES register_link(id) ON DELETE CASCADE,
                    perspective_id      UUID NOT NULL REFERENCES schema_entity_link_perspective(id),
                    register_item_id    UUID NOT NULL REFERENCES register_item(id)
                )
                """).update();
    }

    private void createPerItemTypeTables() {
        getItemTypeIds().forEach(typeId -> {
            String tableName = RegisterPartitionManager.tableNameFor(typeId);
            jdbcClient.sql("""
                    CREATE TABLE %s (
                        register_item_id UUID PRIMARY KEY REFERENCES register_item(id) ON DELETE CASCADE,
                        properties       JSONB
                    )
                    """.formatted(tableName)).update();
            jdbcClient.sql("CREATE INDEX ON %s USING GIN (properties)".formatted(tableName)).update();
        });
    }

    private void createPerLinkTypeTables() {
        getLinkTypeIds().forEach(linkTypeId -> {
            String tableName = RegisterPartitionManager.linkTableNameFor(linkTypeId);
            jdbcClient.sql("""
                    CREATE TABLE %s (
                        register_link_id UUID PRIMARY KEY REFERENCES register_link(id) ON DELETE CASCADE,
                        properties       JSONB
                    )
                    """.formatted(tableName)).update();
            jdbcClient.sql("CREATE INDEX ON %s USING GIN (properties)".formatted(tableName)).update();
        });
    }

    private void createBinaryPropertyTable() {
        jdbcClient.sql("""
                CREATE TABLE register_binary_property (
                    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                    register_item_id UUID NOT NULL REFERENCES register_item(id) ON DELETE CASCADE,
                    property_id      UUID NOT NULL REFERENCES schema_property(id),
                    binary_id        UUID NOT NULL
                )
                """).update();
        jdbcClient.sql("CREATE INDEX ON register_binary_property (register_item_id)").update();
    }

    private List<UUID> getItemTypeIds() {
        return jdbcClient.sql("SELECT id FROM schema_item")
                .query((rs, n) -> rs.getObject("id", UUID.class))
                .list();
    }

    private List<UUID> getLinkTypeIds() {
        return jdbcClient.sql("SELECT id FROM schema_link")
                .query((rs, n) -> rs.getObject("id", UUID.class))
                .list();
    }
}
