package org.ntrloc.graph.db.partition.schema;

import org.ntrloc.graph.db.partition.schema.definition.view.admin.AdminPropertyDefinitionView;
import org.ntrloc.graph.db.partition.schema.definition.PropertyType;
import org.ntrloc.graph.db.partition.schema.definition.mutation.ReplaceControlledListMutation;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
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
    private static final String COL_VALUE_TYPE = "value_type";

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

    // Single choke point for every real write path: replaceValues (UpdateControlledListMutation,
    // the admin-UI's own "replace" flow) does DELETE-then-addValues, and CreateControlledListMutation
    // seeds through here directly -- so validating here, once, covers both without the two callers
    // needing their own copy of the same checks. addValue (singular) deliberately isn't covered --
    // it's a test-fixture-seeding helper only, never used by a real mutation path (see its own
    // callers), and validating it would mean an extra round trip to check against rows already in
    // the table for every single call, not worth paying for code that only ever runs in tests.
    public void addValues(UUID listId, List<ValueEntry> entries) {
        if (entries.isEmpty()) {
            return;
        }
        validateEntries(entries);
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

    // Three invariants a controlled list's own values must hold, checked together against the
    // *whole* submitted batch -- every real write replaces the entire value set from scratch (see
    // addValues' own comment), so there's never a need to diff against rows already in the table.
    // (1) Values must be unique -- a duplicate would make list membership itself ambiguous (which
    //     row is "the" match?). (2) Labeling is all-or-nothing: either every value has one or none
    //     do, never a mix -- this is what lets a client learn "does this property's values carry a
    //     label" once, from the schema, rather than having to inspect each value's own projected
    //     shape to find out. (3) Labels must be unique too, whenever they're used at all -- two
    //     values sharing a label would be indistinguishable to the human the label exists for in
    //     the first place, even though nothing downstream of submission ever reads a label back
    //     into a value (see ItemPanePropertyGroupsEditView's own comment on why only `value` is
    //     ever what's actually stored/validated against).
    private void validateEntries(List<ValueEntry> entries) {
        Set<Object> seenValues = new HashSet<>();
        for (ValueEntry entry : entries) {
            if (!seenValues.add(entry.value())) {
                throw new IllegalArgumentException("Duplicate controlled list value: " + entry.value());
            }
        }

        long labeledCount = entries.stream().filter(e -> hasLabel(e.label())).count();
        if (labeledCount > 0 && labeledCount < entries.size()) {
            throw new IllegalArgumentException(
                    "A controlled list's values must either all have a label or all have none -- mixing labeled and unlabeled values isn't allowed");
        }
        if (labeledCount > 0) {
            Set<String> seenLabels = new HashSet<>();
            for (ValueEntry entry : entries) {
                if (!seenLabels.add(entry.label())) {
                    throw new IllegalArgumentException("Duplicate controlled list label: " + entry.label());
                }
            }
        }
    }

    private boolean hasLabel(String label) {
        return label != null && !label.isBlank();
    }

    public List<AllowedValue> getValues(UUID listId, PropertyType valueType) {
        return jdbcClient.sql("SELECT value, label FROM %s ORDER BY sort_order, value"
                        .formatted(tableNameFor(listId)))
                .query((rs, _) -> {
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

    // Projection rule (4): a controlled-list-backed property returns a bare scalar when its list
    // carries no labels at all, or a {value,label} object when it does. validateEntries enforces
    // that a list is either fully labeled or fully unlabeled (never a mix), so "does any value have
    // a label" is equivalent to "do they all" -- callers can treat an empty map back as "don't wrap
    // anything for this property" with no extra branch. Keyed by value.toString() rather than the
    // raw typed value: callers resolve this against a JSONB ->>'...' extraction (always text) or a
    // Jackson-deserialized number whose exact boxed type (Integer vs Long) doesn't always match
    // this list's own valueType, same leniency containsValue already needed.
    public Map<String, String> getLabelsByValue(UUID listId, PropertyType valueType) {
        List<AllowedValue> values = getValues(listId, valueType);
        boolean labeled = values.stream().anyMatch(v -> hasLabel(v.label()));
        if (!labeled) {
            return Map.of();
        }
        Map<String, String> byValue = new HashMap<>();
        for (AllowedValue v : values) {
            byValue.put(String.valueOf(v.value()), v.label());
        }
        return byValue;
    }

    // A value's own instance type at the JSON boundary doesn't always match the list's declared
    // valueType -- e.g. Jackson hands LONG values through as Integer when they fit, same leniency
    // validateScalar already allows -- so this normalizes before binding, rather than requiring the
    // caller to.
    public boolean containsValue(UUID listId, PropertyType valueType, Object value) {
        Object normalized = valueType == PropertyType.LONG && value instanceof Integer i ? i.longValue() : value;
        return jdbcClient.sql("SELECT 1 FROM %s WHERE value = :value LIMIT 1".formatted(tableNameFor(listId)))
                .param(PARAM_VALUE, normalized)
                .query(Integer.class)
                .optional()
                .isPresent();
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

    public void renameList(UUID listId, String name) {
        jdbcClient.sql("UPDATE schema_controlled_list SET name = :name WHERE id = :id")
                .param("name", name).param("id", listId).update();
    }

    // Delete the catalog row FIRST -- the schema_property.controlled_list_id FK is ON DELETE SET
    // NULL, so this detaches every property using the list in one statement. Only then drop the
    // per-list value table. applyMutations is not transactional: if the DROP failed after the row
    // was gone we'd have a harmless orphan table; the reverse order would leave properties pointing
    // at a list whose value table no longer exists, and SchemaViewBuilder.allowedValuesFor would
    // throw on the next GET /api/admin/schema.
    public void deleteList(UUID listId) {
        jdbcClient.sql("DELETE FROM schema_controlled_list WHERE id = :id").param("id", listId).update();
        jdbcClient.sql("DROP TABLE IF EXISTS " + tableNameFor(listId)).update();
    }

    public void clearPropertyControlledList(UUID propertyId) {
        jdbcClient.sql("UPDATE schema_property SET controlled_list_id = NULL WHERE id = :id")
                .param("id", propertyId).update();
    }

    public int countValues(UUID listId) {
        return jdbcClient.sql("SELECT count(*) FROM " + tableNameFor(listId))
                .query(Integer.class).single();
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

    // Attaches a list to a property, after checking the property's type can have one and matches the
    // list's value type.
    public void attachToProperty(AdminPropertyDefinitionView property, UUID listId) {
        if (!SUPPORTED_TYPES.contains(property.type())) {
            throw new IllegalArgumentException(
                    "Controlled lists are not supported for property type: " + property.type());
        }
        var list = getListById(listId)
                .orElseThrow(() -> new IllegalArgumentException("No controlled list: " + listId));
        if (list.valueType() != property.type()) {
            throw new IllegalArgumentException("Controlled list '" + list.name() + "' is " + list.valueType()
                    + " but property '" + property.name() + "' is " + property.type());
        }
        setPropertyControlledList(property.id(), listId);
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
