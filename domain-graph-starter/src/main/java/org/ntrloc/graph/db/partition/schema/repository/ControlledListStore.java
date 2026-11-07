package org.ntrloc.graph.db.partition.schema.repository;

import org.ntrloc.graph.db.partition.schema.AllowedValue;
import org.ntrloc.graph.db.partition.schema.ControlledListValues;
import org.ntrloc.graph.db.partition.schema.definition.PropertyType;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

// Controlled lists' storage: the schema_controlled_list catalog, one value table per list, and
// schema_property.controlled_list_id. No checks -- ControlledListService validates before writing.
@Component
@DependsOnDatabaseInitialization
public class ControlledListStore {

    private static final String PARAM_VALUE = "value";
    private static final String PARAM_LABEL = "label";
    private static final String COL_VALUE_TYPE = "value_type";

    // Batches into multi-row INSERTs so seeding a large list doesn't pay a round trip per value.
    private static final int BATCH_SIZE = 1000;

    private final JdbcClient jdbcClient;

    public ControlledListStore(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public record ControlledList(UUID id, String name, PropertyType valueType) {}

    public record ValueEntry(Object value, String label) {}

    public ControlledList createList(String name, PropertyType valueType) {
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
                """.formatted(tableNameFor(listId), ControlledListValues.columnType(valueType))).update();

        return new ControlledList(listId, name, valueType);
    }

    // Unchecked single insert, for test fixtures.
    public void addValue(UUID listId, Object value, String label, int sortOrder) {
        jdbcClient.sql("INSERT INTO %s (value, label, sort_order) VALUES (:value, :label, :sortOrder)"
                        .formatted(tableNameFor(listId)))
                .param(PARAM_VALUE, value)
                .param(PARAM_LABEL, label)
                .param("sortOrder", sortOrder)
                .update();
    }

    // Sort order is each entry's position in the list.
    public void addValues(UUID listId, List<ValueEntry> entries) {
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

    public void deleteValues(UUID listId) {
        jdbcClient.sql("DELETE FROM " + tableNameFor(listId)).update();
    }

    public List<AllowedValue> getValues(UUID listId, PropertyType valueType) {
        return jdbcClient.sql("SELECT value, label FROM %s ORDER BY sort_order, value"
                        .formatted(tableNameFor(listId)))
                .query((rs, _) -> new AllowedValue(ControlledListValues.read(rs, PARAM_VALUE, valueType), rs.getString(PARAM_LABEL)))
                .list();
    }

    // The value must already be in the column's type.
    public boolean containsValue(UUID listId, Object value) {
        return jdbcClient.sql("SELECT 1 FROM %s WHERE value = :value LIMIT 1".formatted(tableNameFor(listId)))
                .param(PARAM_VALUE, value)
                .query(Integer.class)
                .optional()
                .isPresent();
    }

    public int countValues(UUID listId) {
        return jdbcClient.sql("SELECT count(*) FROM " + tableNameFor(listId))
                .query(Integer.class).single();
    }

    public List<ControlledList> getAllLists() {
        return jdbcClient.sql("SELECT id, name, value_type FROM schema_controlled_list ORDER BY name")
                .query((rs, _) -> new ControlledList(
                        rs.getObject("id", UUID.class),
                        rs.getString("name"),
                        PropertyType.valueOf(rs.getString(COL_VALUE_TYPE))))
                .list();
    }

    public Optional<ControlledList> getListById(UUID listId) {
        return jdbcClient.sql("SELECT id, name, value_type FROM schema_controlled_list WHERE id = :id")
                .param("id", listId)
                .query((rs, _) -> new ControlledList(
                        rs.getObject("id", UUID.class),
                        rs.getString("name"),
                        PropertyType.valueOf(rs.getString(COL_VALUE_TYPE))))
                .optional();
    }

    // The list's row, locked until the transaction ends, so what's checked against it can't change
    // before the batch commits.
    public Optional<ControlledList> lockList(UUID listId) {
        return jdbcClient.sql("SELECT id, name, value_type FROM schema_controlled_list WHERE id = :id FOR UPDATE")
                .param("id", listId)
                .query((rs, _) -> new ControlledList(
                        rs.getObject("id", UUID.class),
                        rs.getString("name"),
                        PropertyType.valueOf(rs.getString(COL_VALUE_TYPE))))
                .optional();
    }

    public Optional<ControlledList> getListForProperty(UUID propertyId) {
        return jdbcClient.sql("""
                SELECT cl.id, cl.name, cl.value_type
                FROM schema_controlled_list cl
                JOIN schema_property sp ON sp.controlled_list_id = cl.id
                WHERE sp.id = :propertyId
                """)
                .param("propertyId", propertyId)
                .query((rs, _) -> new ControlledList(
                        rs.getObject("id", UUID.class),
                        rs.getString("name"),
                        PropertyType.valueOf(rs.getString(COL_VALUE_TYPE))))
                .optional();
    }

    public void renameList(UUID listId, String name) {
        jdbcClient.sql("UPDATE schema_controlled_list SET name = :name WHERE id = :id")
                .param("name", name).param("id", listId).update();
    }

    // Delete the catalog row first: the schema_property.controlled_list_id FK is ON DELETE SET NULL,
    // so this detaches every property using the list. Dropping the value table second means a
    // failure in between leaves a harmless orphan table, never a property pointing at a list whose
    // values are gone.
    public void deleteList(UUID listId) {
        jdbcClient.sql("DELETE FROM schema_controlled_list WHERE id = :id").param("id", listId).update();
        jdbcClient.sql("DROP TABLE IF EXISTS " + tableNameFor(listId)).update();
    }

    public void setPropertyControlledList(UUID propertyId, UUID listId) {
        jdbcClient.sql("UPDATE schema_property SET controlled_list_id = :listId WHERE id = :id")
                .param("listId", listId)
                .param("id", propertyId)
                .update();
    }

    public void clearPropertyControlledList(UUID propertyId) {
        jdbcClient.sql("UPDATE schema_property SET controlled_list_id = NULL WHERE id = :id")
                .param("id", propertyId).update();
    }

    private static String tableNameFor(UUID listId) {
        return "schema_controlled_list_" + listId.toString().replace("-", "_");
    }
}
