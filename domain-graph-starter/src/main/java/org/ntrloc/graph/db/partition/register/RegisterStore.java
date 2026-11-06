package org.ntrloc.graph.db.partition.register;

import org.ntrloc.graph.db.partition.register.ExplicitProjection.Access;
import org.ntrloc.graph.db.partition.register.ExplicitProjection.Comparison;
import org.ntrloc.graph.db.partition.register.ExplicitProjection.Condition;
import org.ntrloc.graph.db.partition.register.ExplicitProjection.Sort;
import org.ntrloc.graph.db.partition.register.ExplicitProjection.SortKey;
import org.ntrloc.graph.db.partition.register.ExplicitProjection.ValueType;
import org.ntrloc.graph.db.partition.register.RegisterRows.Audit;
import org.ntrloc.graph.db.partition.register.RegisterRows.ItemRow;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

// The register's storage: SQL against the register's own tables, and nothing else. It knows the
// physical layout (one properties table per item type, JSONB keyed by property id, the states
// document's shape) and never the schema: every ExplicitProjection it receives is already resolved
// to ids. Being moved here from RegisterPartitionManager one feature at a time
// (docs/ntrloc-architecture-rationalization.md §9, step 1.3).
@Component
public class RegisterStore {

    private static final String STATE_CURRENT_STATE_ID = "currentStateId";

    private final JdbcClient jdbcClient;
    private final ObjectMapper objectMapper;

    public RegisterStore(JdbcClient jdbcClient, ObjectMapper objectMapper) {
        this.jdbcClient = jdbcClient;
        this.objectMapper = objectMapper;
    }

    public static String tableNameFor(UUID itemTypeId) {
        return "register_item_" + itemTypeId.toString().replace("-", "_");
    }

    public static String linkTableNameFor(UUID linkTypeId) {
        return "register_link_" + linkTypeId.toString().replace("-", "_");
    }

    public RegisterRows query(ExplicitProjection projection) {
        if (projection.itemTypeIds().size() != 1) {
            throw new UnsupportedOperationException("Cross-type queries aren't compiled by RegisterStore yet");
        }
        UUID itemTypeId = projection.itemTypeIds().iterator().next();
        SqlFragment where = whereClause(projection.filter(), projection.access());

        List<ItemRow> items = jdbcClient.sql("""
                SELECT ri.id AS register_item_id, ri.item_id, ri.item_type_id,
                       rt.properties::text AS properties, rt.states::text AS states,
                       ri.created_date, ri.created_by, ri.updated_date, ri.updated_by
                FROM register_item ri
                JOIN %s rt ON rt.register_item_id = ri.id
                WHERE ri.item_type_id = :itemTypeId
                  AND ri.state = 'COMMITTED'
                  %s
                """.formatted(tableNameFor(itemTypeId), where.sql()))
                .param("itemTypeId", itemTypeId)
                .params(where.params())
                .query((rs, _) -> new ItemRow(
                        rs.getObject("register_item_id", UUID.class),
                        rs.getObject("item_id", UUID.class),
                        rs.getObject("item_type_id", UUID.class),
                        propertiesById(rs.getString("properties")),
                        currentStateIds(rs.getString("states")),
                        new Audit(rs.getObject("created_date", OffsetDateTime.class), rs.getObject("created_by", UUID.class),
                                rs.getObject("updated_date", OffsetDateTime.class), rs.getObject("updated_by", UUID.class))))
                .list();
        return new RegisterRows(items);
    }

    // The filter and access as "AND ..." clauses (or nothing), over the aliases ri (register_item) and
    // rt (the item type's properties table). Package-private and transitional: the collection queries
    // still built in RegisterPartitionManager (counts, facets, sort, cross-type) embed it until they
    // move here too.
    SqlFragment whereClause(Condition filter, Access access) {
        List<String> clauses = new ArrayList<>();
        Map<String, Object> params = new HashMap<>();
        if (filter != Condition.All.INSTANCE) {
            SqlFragment condition = compile(filter, new AtomicInteger());
            clauses.add("AND " + condition.sql());
            params.putAll(condition.params());
        }
        switch (access) {
            case Access.Unrestricted _ -> { /* no clause */ }
            case Access.AnyMarker a when a.markerIds().isEmpty() -> clauses.add("AND FALSE");
            case Access.AnyMarker a -> {
                clauses.add("AND ri.id IN (SELECT register_item_id FROM register_item_marker WHERE marker_id IN (:accessMarkerIds))");
                params.put("accessMarkerIds", a.markerIds());
            }
        }
        return new SqlFragment(String.join(" ", clauses), params);
    }

    // A boolean SQL expression. An empty And is true and an empty Or is false, as in logic.
    private SqlFragment compile(Condition condition, AtomicInteger paramCounter) {
        return switch (condition) {
            case Condition.All _ -> new SqlFragment("TRUE", Map.of());
            case Condition.ItemIdIs c -> {
                String param = "cond_" + paramCounter.getAndIncrement();
                yield new SqlFragment("ri.item_id = :" + param, Map.of(param, c.itemId()));
            }
            case Condition.And c -> join(c.conditions(), " AND ", "TRUE", paramCounter);
            case Condition.Or c -> join(c.conditions(), " OR ", "FALSE", paramCounter);
            case Condition.Not c -> {
                SqlFragment child = compile(c.condition(), paramCounter);
                yield new SqlFragment("NOT (" + child.sql() + ")", child.params());
            }
            case Condition.PropertyExists c -> new SqlFragment("jsonb_exists(rt.properties::jsonb, '" + c.propertyId() + "')", Map.of());
            case Condition.PropertyCompare c -> {
                String param = "cond_" + paramCounter.getAndIncrement();
                yield new SqlFragment("(rt.properties::jsonb)->>'" + c.propertyId() + "' " + sqlOperator(c.comparison()) + " :" + param,
                        Map.of(param, c.value()));
            }
            case Condition.InState c -> new SqlFragment(
                    "(rt.states::jsonb)->'" + c.stateMachineId() + "'->>'" + STATE_CURRENT_STATE_ID + "' = '" + c.stateId() + "'", Map.of());
        };
    }

    private SqlFragment join(List<Condition> conditions, String operator, String whenEmpty, AtomicInteger paramCounter) {
        if (conditions.isEmpty()) return new SqlFragment(whenEmpty, Map.of());
        List<String> parts = new ArrayList<>();
        Map<String, Object> params = new HashMap<>();
        for (Condition child : conditions) {
            SqlFragment compiled = compile(child, paramCounter);
            parts.add(compiled.sql());
            params.putAll(compiled.params());
        }
        return new SqlFragment("(" + String.join(operator, parts) + ")", params);
    }

    private static String sqlOperator(Comparison comparison) {
        return switch (comparison) {
            case EQUALS -> "=";
            case NOT_EQUALS -> "!=";
            case LESS_THAN -> "<";
            case LESS_THAN_OR_EQUAL -> "<=";
            case GREATER_THAN -> ">";
            case GREATER_THAN_OR_EQUAL -> ">=";
            case LIKE_IGNORING_CASE -> "ILIKE";
        };
    }

    // Which aliases a query's rows are read through: a single item type's register_item (ri) joined to
    // its properties table (rt), or the cross-type UNION's "combined" columns.
    enum RowSource {
        ITEM_ROW("ri.id", "ri.item_id", "ri.item_type_id", "rt.properties", "ri.state", "ri."),
        COMBINED("combined.register_item_id", "combined.item_id", "combined.item_type_id", "combined.properties",
                "combined.visibility_state", "combined.");

        final String registerItemId, itemId, itemTypeId, properties, visibilityState, auditPrefix;

        RowSource(String registerItemId, String itemId, String itemTypeId, String properties, String visibilityState, String auditPrefix) {
            this.registerItemId = registerItemId;
            this.itemId = itemId;
            this.itemTypeId = itemTypeId;
            this.properties = properties;
            this.visibilityState = visibilityState;
            this.auditPrefix = auditPrefix;
        }
    }

    // An ORDER BY clause. Package-private and transitional, like whereClause.
    SqlFragment orderByClause(Sort sort, RowSource source) {
        Map<String, Object> params = new HashMap<>();
        String expression = switch (sort.key()) {
            case SortKey.Column c -> switch (c) {
                case ITEM_ID -> source.itemId;
                case CREATED_DATE -> source.auditPrefix + "created_date";
                case CREATED_BY -> source.auditPrefix + "created_by";
                case UPDATED_DATE -> source.auditPrefix + "updated_date";
                case UPDATED_BY -> source.auditPrefix + "updated_by";
                case VISIBILITY_STATE -> source.visibilityState;
            };
            case SortKey.ItemTypeName k -> {
                StringBuilder cases = new StringBuilder("CASE " + source.itemTypeId);
                int i = 0;
                for (var entry : k.namesByItemTypeId().entrySet()) {
                    cases.append(" WHEN :sortType_").append(i).append(" THEN :sortTypeName_").append(i);
                    params.put("sortType_" + i, entry.getKey());
                    params.put("sortTypeName_" + i, entry.getValue());
                    i++;
                }
                yield cases.append(" END").toString();
            }
            case SortKey.Property k -> cast(source.properties + "->>'" + k.propertyId() + "'", k.type());
            case SortKey.BinaryField k -> cast(k.fieldTextOfBinary().bind(
                    "(SELECT rbp.binary_id FROM register_binary_property rbp WHERE rbp.register_item_id = " + source.registerItemId
                            + " AND rbp.property_id = '" + k.propertyId() + "')"), k.type());
        };
        String direction = sort.descending() ? "DESC" : "ASC";
        return new SqlFragment("ORDER BY " + expression + " " + direction + " NULLS LAST, " + source.itemId + " ASC", params);
    }

    // ->> always yields text, so an INTEGER would sort "1", "10", "2" uncast. NULLIF(_, '') keeps an
    // empty string from failing the cast.
    private static String cast(String text, ValueType type) {
        return switch (type) {
            case TEXT -> text;
            case INTEGER -> "NULLIF(" + text + ", '')::bigint";
            case DECIMAL -> "NULLIF(" + text + ", '')::double precision";
            case DATE -> "NULLIF(" + text + ", '')::date";
            case DATETIME -> "NULLIF(" + text + ", '')::timestamptz";
            case BOOLEAN -> "NULLIF(" + text + ", '')::boolean";
        };
    }

    private Map<UUID, Object> propertiesById(String json) {
        Map<UUID, Object> result = new HashMap<>();
        parseJson(json, "properties").forEach((id, value) -> result.put(UUID.fromString(id), value));
        return result;
    }

    // The states document is {stateMachineId: {"currentStateId": stateId}}.
    private Map<UUID, UUID> currentStateIds(String json) {
        Map<UUID, UUID> result = new HashMap<>();
        parseJson(json, "states").forEach((stateMachineId, entry) -> {
            if (entry instanceof Map<?, ?> m && m.get(STATE_CURRENT_STATE_ID) != null) {
                result.put(UUID.fromString(stateMachineId), UUID.fromString(m.get(STATE_CURRENT_STATE_ID).toString()));
            }
        });
        return result;
    }

    // A jsonb column always holds valid JSON, but not necessarily an object; fail loudly if not.
    @SuppressWarnings("unchecked")
    private Map<String, Object> parseJson(String json, String column) {
        if (json == null) return Map.of();
        try {
            return objectMapper.readValue(json, Map.class);
        } catch (RuntimeException e) {
            throw new IllegalStateException("Failed to parse " + column + " JSONB", e);
        }
    }
}
