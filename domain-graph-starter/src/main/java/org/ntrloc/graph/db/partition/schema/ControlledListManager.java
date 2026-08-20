package org.ntrloc.graph.db.partition.schema;

import org.ntrloc.graph.db.partition.schema.definition.PropertyType;
import org.ntrloc.graph.db.partition.schema.definition.mutation.ReplaceControlledListMutation;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Component
@DependsOnDatabaseInitialization
public class ControlledListManager {

    private static final String PARAM_VALUE = "value";
    private static final String PARAM_LABEL = "label";

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
                .param(PARAM_VALUE, value)
                .param(PARAM_LABEL, label)
                .param("sortOrder", sortOrder)
                .update();
    }

    public record ValueEntry(Object value, String label) {}

    // Batches into multi-row INSERTs so seeding large lists doesn't pay a network
    // round trip per value (49k individual inserts over a tunneled connection is minutes;
    // batched it's seconds).
    private static final int BATCH_SIZE = 1000;

    public void addValues(UUID listId, List<ValueEntry> entries) {
        if (entries.isEmpty()) {
            return;
        }
        String tableName = tableNameFor(listId);
        for (int start = 0; start < entries.size(); start += BATCH_SIZE) {
            List<ValueEntry> batch = entries.subList(start, Math.min(start + BATCH_SIZE, entries.size()));
            StringBuilder sql = new StringBuilder("INSERT INTO ").append(tableName)
                    .append(" (value, label, sort_order) VALUES ");
            Map<String, Object> params = new HashMap<>();
            for (int i = 0; i < batch.size(); i++) {
                if (i > 0) {
                    sql.append(", ");
                }
                sql.append("(:value").append(i).append(", :label").append(i).append(", :sortOrder").append(i).append(")");
                ValueEntry entry = batch.get(i);
                params.put(PARAM_VALUE + i, entry.value());
                params.put(PARAM_LABEL + i, entry.label());
                params.put("sortOrder" + i, start + i);
            }
            jdbcClient.sql(sql.toString()).params(params).update();
        }
    }

    public List<AllowedValue> getValues(UUID listId, PropertyType valueType) {
        return jdbcClient.sql("SELECT value, label FROM %s ORDER BY sort_order, value"
                        .formatted(tableNameFor(listId)))
                .query((rs, n) -> {
                    Object val = switch (valueType) {
                        case STRING -> rs.getString(PARAM_VALUE);
                        case INT    -> rs.getInt(PARAM_VALUE);
                        case LONG   -> rs.getLong(PARAM_VALUE);
                        default -> throw new IllegalStateException("Unexpected controlled list type: " + valueType);
                    };
                    return new AllowedValue(val, rs.getString(PARAM_LABEL));
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
        jdbcClient.sql("DELETE FROM " + tableNameFor(listId)).update();
        List<ValueEntry> typed = new ArrayList<>(entries.size());
        for (var entry : entries) {
            Object value = switch (valueType) {
                case STRING -> entry.value();
                case INT    -> Integer.parseInt(entry.value());
                case LONG   -> Long.parseLong(entry.value());
                default -> throw new IllegalArgumentException("Unsupported controlled list type: " + valueType);
            };
            typed.add(new ValueEntry(value, entry.label()));
        }
        addValues(listId, typed);
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
