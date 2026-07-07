package org.ntrloc.graph.db.partition.schema.repository;

import jakarta.annotation.PostConstruct;
import org.ntrloc.graph.db.partition.register.RegisterPartitionManager;
import org.ntrloc.graph.db.partition.schema.SchemaManager;
import org.ntrloc.graph.db.partition.schema.definition.PropertyCardinality;
import org.ntrloc.graph.db.partition.schema.definition.PropertyType;
import org.ntrloc.graph.db.partition.schema.definition.PropertyUsage;
import org.ntrloc.graph.db.partition.schema.definition.mutation.AssignItemPropertyGroupMutation;
import org.ntrloc.graph.db.partition.schema.definition.mutation.CreateItemDefinitionMutation;
import org.ntrloc.graph.db.partition.schema.definition.mutation.CreatePropertyDefinitionMutation;
import org.ntrloc.graph.db.partition.schema.definition.mutation.CreatePropertyGroupMutation;
import org.ntrloc.graph.db.partition.schema.definition.view.admin.AdminItemDefinitionView;
import org.ntrloc.graph.db.partition.schema.definition.view.admin.AdminPropertyDefinitionView;
import org.ntrloc.graph.db.partition.schema.definition.view.admin.AdminPropertyGroupView;
import org.postgresql.util.PGobject;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.DependsOn;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

/**
 * Tracer bullet for grouped-property rendering: one item type ("PropertyGroupTestDoc") with an
 * ungrouped "title" property and an "isbn13" property assigned to the "IDs" group, plus one
 * register row. Created here — after registerInitializer, not alongside AuthorizationTestDataInitializer
 * — because the item type doesn't exist yet when registerInitializer scans schema_item to create
 * per-type register tables, so this initializer creates its own register table explicitly instead
 * of relying on ordering relative to that scan.
 */
@Component
@ConditionalOnProperty(prefix = "ntrloc.security", name = "seed-test-data", havingValue = "true")
@DependsOn({"schemaManager", "registerInitializer"})
public class PropertyGroupTestDataInitializer {

    private final SchemaManager schemaManager;
    private final JdbcClient jdbcClient;

    public PropertyGroupTestDataInitializer(SchemaManager schemaManager, JdbcClient jdbcClient) {
        this.schemaManager = schemaManager;
        this.jdbcClient = jdbcClient;
    }

    @PostConstruct
    void init() {
        schemaManager.applyMutations(List.of(
                new CreateItemDefinitionMutation("PropertyGroupTestDoc", "Tracer bullet for grouped property rendering", List.of(
                        new CreatePropertyDefinitionMutation("title", null, PropertyType.STRING, PropertyCardinality.SINGLE, PropertyUsage.OPTIONAL),
                        new CreatePropertyDefinitionMutation("isbn13", null, PropertyType.STRING, PropertyCardinality.SINGLE, PropertyUsage.OPTIONAL)
                ))
        ));

        AdminItemDefinitionView item = findItem();
        UUID itemId = item.id();
        UUID isbn13Id = findProperty(item, "isbn13").id();

        schemaManager.applyMutations(List.of(new CreatePropertyGroupMutation(item.entityId(), "IDs")));
        UUID groupId = findGroup(findItem(), "IDs").id();

        schemaManager.applyMutations(List.of(new AssignItemPropertyGroupMutation(itemId, isbn13Id, groupId)));

        createRegisterTable(itemId);
        UUID registerItemId = insertRegisterItem(itemId);
        insertProperties(itemId, registerItemId, "{\"title\":\"Some Title\",\"isbn13\":\"9781234567890\"}");
    }

    private void createRegisterTable(UUID itemId) {
        String tableName = RegisterPartitionManager.tableNameFor(itemId);
        jdbcClient.sql("""
                CREATE TABLE %s (
                    register_item_id UUID PRIMARY KEY REFERENCES register_item(id) ON DELETE CASCADE,
                    properties       JSONB
                )
                """.formatted(tableName)).update();
        jdbcClient.sql("CREATE INDEX ON %s USING GIN (properties)".formatted(tableName)).update();
    }

    private UUID insertRegisterItem(UUID itemTypeId) {
        return jdbcClient.sql("""
                INSERT INTO register_item (item_id, item_type_id, state)
                VALUES (gen_random_uuid(), :itemTypeId, 'COMMITTED')
                RETURNING id
                """)
                .param("itemTypeId", itemTypeId)
                .query(UUID.class)
                .single();
    }

    private void insertProperties(UUID itemTypeId, UUID registerItemId, String propertiesJson) {
        try {
            PGobject props = new PGobject();
            props.setType("jsonb");
            props.setValue(propertiesJson);
            jdbcClient.sql("INSERT INTO %s (register_item_id, properties) VALUES (:id, :props)"
                            .formatted(RegisterPartitionManager.tableNameFor(itemTypeId)))
                    .param("id", registerItemId)
                    .param("props", props)
                    .update();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to insert PropertyGroupTestDoc register row", e);
        }
    }

    private AdminItemDefinitionView findItem() {
        return schemaManager.getAdminSchema().items().stream()
                .filter(i -> i.name().equals("PropertyGroupTestDoc"))
                .findFirst()
                .orElseThrow();
    }

    private AdminPropertyDefinitionView findProperty(AdminItemDefinitionView item, String name) {
        return item.properties().stream().filter(p -> p.name().equals(name)).findFirst().orElseThrow();
    }

    private AdminPropertyGroupView findGroup(AdminItemDefinitionView item, String name) {
        return item.groups().stream().filter(g -> g.name().equals(name)).findFirst().orElseThrow();
    }
}
