package org.ntrloc.graph.db.partition.schema;

import org.ntrloc.graph.db.partition.schema.definition.PropertyType;
import org.ntrloc.graph.db.partition.schema.definition.mutation.ReplaceControlledListMutation;
import org.springframework.context.annotation.DependsOn;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Component
@DependsOn("schemaInitializer")
public class ControlledListManager {

    public static final Set<PropertyType> SUPPORTED_TYPES = Set.of(
            PropertyType.STRING, PropertyType.INT, PropertyType.LONG);

    private final JdbcClient jdbcClient;

    public ControlledListManager(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public record ControlledList(UUID id, String name, PropertyType valueType) {}

    public ControlledList createList(String name, PropertyType valueType) {
        if (!SUPPORTED_TYPES.contains(valueType)) {
            throw new IllegalArgumentException("Controlled lists are not supported for property type: " + valueType);
        }
        UUID listId = jdbcClient.sql(
                        "INSERT INTO schema_controlled_list (name, value_type) VALUES (:name, :valueType) RETURNING id")
                .param("name", name)
                .param("valueType", valueType.name())
                .query(UUID.class).single();

        jdbcClient.sql("""
                CREATE TABLE %s (
                    id         UUID PRIMARY KEY DEFAULT uuidv7(),
                    value      %s NOT NULL,
                    label      TEXT,
                    sort_order INT NOT NULL DEFAULT 0
                )
                """.formatted(tableNameFor(listId), ddlColumnType(valueType))).update();

        return new ControlledList(listId, name, valueType);
    }

    public void addValue(UUID listId, Object value, String label, int sortOrder) {
        jdbcClient.sql("INSERT INTO %s (value, label, sort_order) VALUES (:value, :label, :sortOrder)"
                        .formatted(tableNameFor(listId)))
                .param("value", value)
                .param("label", label)
                .param("sortOrder", sortOrder)
                .update();
    }

    public List<AllowedValue> getValues(UUID listId, PropertyType valueType) {
        return jdbcClient.sql("SELECT value, label FROM %s ORDER BY sort_order, value"
                        .formatted(tableNameFor(listId)))
                .query((rs, n) -> {
                    Object val = switch (valueType) {
                        case STRING -> rs.getString("value");
                        case INT    -> rs.getInt("value");
                        case LONG   -> rs.getLong("value");
                        default -> throw new IllegalStateException("Unexpected controlled list type: " + valueType);
                    };
                    return new AllowedValue(val, rs.getString("label"));
                })
                .list();
    }

    public Optional<ControlledList> getListForProperty(UUID propertyId) {
        return jdbcClient.sql("""
                SELECT cl.id, cl.name, cl.value_type
                FROM schema_controlled_list cl
                JOIN schema_property sp ON sp.controlled_list_id = cl.id
                WHERE sp.id = :propertyId
                """)
                .param("propertyId", propertyId)
                .query((rs, n) -> new ControlledList(
                        rs.getObject("id", UUID.class),
                        rs.getString("name"),
                        PropertyType.valueOf(rs.getString("value_type"))))
                .optional();
    }

    public void replaceValues(UUID listId, PropertyType valueType, List<ReplaceControlledListMutation.Entry> entries) {
        String tableName = tableNameFor(listId);
        jdbcClient.sql("DELETE FROM " + tableName).update();
        int order = 0;
        for (var entry : entries) {
            Object typed = switch (valueType) {
                case STRING -> entry.value();
                case INT    -> Integer.parseInt(entry.value());
                case LONG   -> Long.parseLong(entry.value());
                default -> throw new IllegalArgumentException("Unsupported controlled list type: " + valueType);
            };
            jdbcClient.sql("INSERT INTO %s (value, label, sort_order) VALUES (:value, :label, :sortOrder)".formatted(tableName))
                    .param("value", typed)
                    .param("label", entry.label())
                    .param("sortOrder", order++)
                    .update();
        }
    }

    public void setPropertyControlledList(UUID propertyId, UUID listId) {
        jdbcClient.sql("UPDATE schema_property SET controlled_list_id = :listId WHERE id = :id")
                .param("listId", listId)
                .param("id", propertyId)
                .update();
    }

    public static String tableNameFor(UUID listId) {
        return "schema_controlled_list_" + listId.toString().replace("-", "_");
    }

    private String ddlColumnType(PropertyType type) {
        return switch (type) {
            case STRING -> "VARCHAR(4000)";
            case INT    -> "INTEGER";
            case LONG   -> "BIGINT";
            default -> throw new IllegalArgumentException("No DDL column type mapping for: " + type);
        };
    }
}
