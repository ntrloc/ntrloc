package org.ntrloc.graph.db.partition.register;

import org.ntrloc.graph.db.partition.register.ExplicitProjection.Access;
import org.ntrloc.graph.db.partition.register.ExplicitProjection.Comparison;
import org.ntrloc.graph.db.partition.register.ExplicitProjection.Condition;
import org.ntrloc.graph.db.partition.register.ExplicitProjection.LinkAccess;
import org.ntrloc.graph.db.partition.register.ExplicitProjection.LinkSelection;
import org.ntrloc.graph.db.partition.register.ExplicitProjection.Page;
import org.ntrloc.graph.db.partition.register.ExplicitProjection.Sort;
import org.ntrloc.graph.db.partition.register.ExplicitProjection.SortKey;
import org.ntrloc.graph.db.partition.register.ExplicitProjection.StateMachineFacet;
import org.ntrloc.graph.db.partition.register.ExplicitProjection.TermsFacet;
import org.ntrloc.graph.db.partition.register.ExplicitProjection.TermsFilter;
import org.ntrloc.graph.db.partition.register.ExplicitProjection.ValueType;
import org.ntrloc.graph.db.partition.register.RegisterRows.Audit;
import org.ntrloc.graph.db.partition.register.RegisterRows.Counts;
import org.ntrloc.graph.db.partition.register.RegisterRows.ItemRow;
import org.ntrloc.graph.db.partition.register.RegisterRows.LinkRow;
import org.ntrloc.graph.db.partition.register.RegisterRows.ValueCount;
import org.ntrloc.graph.db.partition.schema.event.SchemaChangeEvent;
import org.ntrloc.graph.db.partition.schema.event.SchemaChangeListener;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

// The register's storage: SQL against the register's own tables, and nothing else. It knows the
// physical layout (one properties table per item type, JSONB keyed by property id, the states
// document's shape) and never the schema. Projections arrive as an ExplicitProjection already
// resolved to ids (ProjectionEngine resolves them); writes arrive from the coordinator, the only
// writer. Also the FactResolver marker pipelines read current values through, and the keeper of each
// type's table as the schema changes.
@Component
public class RegisterStore implements RegisterStoreReader, RegisterStoreWriter, SchemaChangeListener {


    private static final String STATE_CURRENT_STATE_ID = "currentStateId";

    private static final String PARAM_ITEM_ID = "itemId";
    private static final String PARAM_ITEM_TYPE_ID = "itemTypeId";
    private static final String COL_ITEM_ID = "item_id";
    private static final String COL_PROPERTIES = "properties";
    private static final String COL_STATES = "states";
    private static final String PARAM_TRANSACTION_ID = "transactionId";
    private static final String PARAM_LINK_ID = "linkId";
    private static final String PARAM_OLD_ID = "oldId";
    private static final String PARAM_REGISTER_ITEM_ID = "registerItemId";
    private static final String PARAM_CREATED_DATE = "createdDate";
    private static final String PARAM_CREATED_BY = "createdBy";
    private static final String PARAM_UPDATED_DATE = "updatedDate";
    private static final String PARAM_UPDATED_BY = "updatedBy";
    private static final String COL_CREATED_DATE = "created_date";
    private static final String COL_CREATED_BY = "created_by";
    private static final String PARAM_PERSPECTIVE_ID = "perspectiveId";
    private static final String COL_PERSPECTIVE_ID = "perspective_id";

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

    @Override
    public RegisterRows query(ExplicitProjection projection) {
        List<UUID> itemTypeIds = List.copyOf(projection.itemTypeIds());
        SqlFragment where = whereClause(projection.filter(), projection.access());
        SqlFragment allTermsFilters = termsFiltersClause(projection.termsFilters());

        Counts counts = null;
        if (projection.count()) {
            long total = count(rows(itemTypeIds, where));
            long faceted = projection.termsFilters().isEmpty() ? total : count(rows(itemTypeIds, combine(where, allTermsFilters)));
            counts = new Counts(total, faceted);
        }

        // Disjunctive: a facet is narrowed by every terms filter except its own property's, so a client
        // can see the other values it could switch to.
        Map<String, List<ValueCount>> termsFacetCounts = new LinkedHashMap<>();
        for (TermsFacet facet : projection.termsFacets()) {
            List<TermsFilter> others = projection.termsFilters().stream().filter(f -> !f.propertyId().equals(facet.propertyId())).toList();
            termsFacetCounts.put(facet.key(), termsFacet(rows(itemTypeIds, combine(where, termsFiltersClause(others))), facet.propertyId()));
        }
        Map<String, List<ValueCount>> stateMachineFacetCounts = new LinkedHashMap<>();
        for (StateMachineFacet facet : projection.stateMachineFacets()) {
            stateMachineFacetCounts.put(facet.key(), stateMachineFacet(rows(itemTypeIds, where), facet.stateMachineId()));
        }

        List<ItemRow> items = counts != null && counts.faceted() == 0 ? List.of()
                : selectItems(rows(itemTypeIds, combine(where, allTermsFilters)), projection.sort(), projection.page());
        return new RegisterRows(withDetails(items, projection.links(), projection.linkAccess()), counts, termsFacetCounts, stateMachineFacetCounts);
    }

    // Adds each row's markers, binary ids and selected links: one batched query apiece per level of
    // rows, never one per row.
    private List<ItemRow> withDetails(List<ItemRow> rows, LinkSelection links, LinkAccess linkAccess) {
        if (rows.isEmpty()) return rows;
        List<UUID> ids = rows.stream().map(ItemRow::registerItemId).distinct().toList();
        Map<UUID, Set<UUID>> markerIds = markerIds(ids);
        Map<UUID, Map<UUID, UUID>> binaryIds = binaryIds(ids);
        Map<UUID, Map<UUID, List<LinkRow>>> linksBySource = links.isNone() ? Map.of() : links(ids, links, linkAccess);
        return rows.stream()
                .map(r -> new ItemRow(r.registerItemId(), r.itemId(), r.itemTypeId(), r.properties(), r.currentStateIds(), r.audit(),
                        markerIds.getOrDefault(r.registerItemId(), Set.of()),
                        binaryIds.getOrDefault(r.registerItemId(), Map.of()),
                        linksBySource.getOrDefault(r.registerItemId(), Map.of())))
                .toList();
    }

    private Map<UUID, Set<UUID>> markerIds(List<UUID> registerItemIds) {
        return jdbcClient.sql("SELECT register_item_id, marker_id FROM register_item_marker WHERE register_item_id IN (:ids)")
                .param("ids", registerItemIds)
                .query((rs, _) -> Map.entry(rs.getObject("register_item_id", UUID.class), rs.getObject("marker_id", UUID.class)))
                .list().stream()
                .collect(Collectors.groupingBy(Map.Entry::getKey, Collectors.mapping(Map.Entry::getValue, Collectors.toSet())));
    }

    private Map<UUID, Map<UUID, UUID>> binaryIds(List<UUID> registerItemIds) {
        Map<UUID, Map<UUID, UUID>> result = new HashMap<>();
        jdbcClient.sql("SELECT register_item_id, property_id, binary_id FROM register_binary_property WHERE register_item_id IN (:ids)")
                .param("ids", registerItemIds)
                .query((RowCallbackHandler) rs -> result.computeIfAbsent(rs.getObject("register_item_id", UUID.class), k -> new HashMap<>())
                        .put(rs.getObject("property_id", UUID.class), rs.getObject("binary_id", UUID.class)));
        return result;
    }

    private record LinkHeader(UUID sourceRegisterItemId, UUID perspectiveId, UUID registerLinkId, UUID linkId, UUID linkTypeId, Audit linkAudit,
                              UUID targetRegisterItemId, UUID targetItemId, UUID targetItemTypeId, Audit targetAudit) {}

    private record PropertiesAndStates(Map<UUID, Object> properties, Map<UUID, UUID> currentStateIds) {}

    // The visible links from these source items through the selected perspectives, with each target's
    // own row and, through each perspective the selection nests under, the target's own links.
    private Map<UUID, Map<UUID, List<LinkRow>>> links(List<UUID> sourceIds, LinkSelection selection, LinkAccess access) {
        SqlFragment visibility = linkVisibility(access);
        Map<String, Object> params = new HashMap<>(visibility.params());
        params.put("sourceIds", sourceIds);
        String perspectiveFilter = "";
        if (selection.perspectiveIds() != null) {
            perspectiveFilter = "AND rilp_mine.perspective_id IN (:perspectiveIds)";
            params.put("perspectiveIds", selection.perspectiveIds());
        }
        // Every row involved is COMMITTED -- the source, the link and the target -- stated for each
        // rather than left to hold through how commits repoint perspective rows.
        List<LinkHeader> headers = jdbcClient.sql("""
                SELECT rilp_mine.register_item_id AS source_register_item_id, rilp_mine.perspective_id,
                       rl.id AS register_link_id, rl.link_id, rl.link_definition_id,
                       rl.created_date AS link_created_date, rl.created_by AS link_created_by,
                       rl.updated_date AS link_updated_date, rl.updated_by AS link_updated_by,
                       ri_other.id AS target_register_item_id, ri_other.item_id AS target_item_id, ri_other.item_type_id AS target_item_type_id,
                       ri_other.created_date AS target_created_date, ri_other.created_by AS target_created_by,
                       ri_other.updated_date AS target_updated_date, ri_other.updated_by AS target_updated_by
                FROM register_item ri_mine
                JOIN register_item_link_perspective rilp_mine  ON rilp_mine.register_item_id = ri_mine.id
                JOIN register_link                  rl         ON rl.id = rilp_mine.register_link_id AND rl.state = 'COMMITTED'
                JOIN register_item_link_perspective rilp_other ON rilp_other.register_link_id = rl.id AND rilp_other.id != rilp_mine.id
                JOIN register_item                  ri_other   ON ri_other.id = rilp_other.register_item_id AND ri_other.state = 'COMMITTED'
                WHERE ri_mine.id IN (:sourceIds)
                  AND ri_mine.state = 'COMMITTED'
                  %s
                  %s
                """.formatted(perspectiveFilter, visibility.sql()))
                .params(params)
                .query((rs, _) -> new LinkHeader(
                        rs.getObject("source_register_item_id", UUID.class), rs.getObject("perspective_id", UUID.class),
                        rs.getObject("register_link_id", UUID.class), rs.getObject("link_id", UUID.class), rs.getObject("link_definition_id", UUID.class),
                        new Audit(rs.getObject("link_created_date", OffsetDateTime.class), rs.getObject("link_created_by", UUID.class),
                                rs.getObject("link_updated_date", OffsetDateTime.class), rs.getObject("link_updated_by", UUID.class)),
                        rs.getObject("target_register_item_id", UUID.class), rs.getObject("target_item_id", UUID.class),
                        rs.getObject("target_item_type_id", UUID.class),
                        new Audit(rs.getObject("target_created_date", OffsetDateTime.class), rs.getObject("target_created_by", UUID.class),
                                rs.getObject("target_updated_date", OffsetDateTime.class), rs.getObject("target_updated_by", UUID.class))))
                .list();
        if (headers.isEmpty()) return Map.of();

        Map<UUID, PropertiesAndStates> targetRows = targetPropertiesAndStates(headers);
        Map<UUID, Map<UUID, Object>> linkProperties = linkProperties(headers);
        List<UUID> targetIds = headers.stream().map(LinkHeader::targetRegisterItemId).distinct().toList();
        Map<UUID, Set<UUID>> targetMarkerIds = markerIds(targetIds);
        Map<UUID, Map<UUID, UUID>> targetBinaryIds = binaryIds(targetIds);

        Map<UUID, Map<UUID, Map<UUID, List<LinkRow>>>> nestedByPerspective = new HashMap<>();
        headers.stream().collect(Collectors.groupingBy(LinkHeader::perspectiveId)).forEach((perspectiveId, perspectiveHeaders) -> {
            LinkSelection nested = selection.nested().get(perspectiveId);
            if (nested != null && !nested.isNone()) {
                nestedByPerspective.put(perspectiveId, links(
                        perspectiveHeaders.stream().map(LinkHeader::targetRegisterItemId).distinct().toList(), nested, access));
            }
        });

        Map<UUID, Map<UUID, List<LinkRow>>> result = new LinkedHashMap<>();
        for (LinkHeader h : headers) {
            PropertiesAndStates target = targetRows.getOrDefault(h.targetRegisterItemId(), new PropertiesAndStates(Map.of(), Map.of()));
            ItemRow targetRow = new ItemRow(h.targetRegisterItemId(), h.targetItemId(), h.targetItemTypeId(),
                    target.properties(), target.currentStateIds(), h.targetAudit(),
                    targetMarkerIds.getOrDefault(h.targetRegisterItemId(), Set.of()),
                    targetBinaryIds.getOrDefault(h.targetRegisterItemId(), Map.of()),
                    nestedByPerspective.getOrDefault(h.perspectiveId(), Map.of()).getOrDefault(h.targetRegisterItemId(), Map.of()));
            result.computeIfAbsent(h.sourceRegisterItemId(), k -> new LinkedHashMap<>())
                    .computeIfAbsent(h.perspectiveId(), k -> new ArrayList<>())
                    .add(new LinkRow(h.linkId(), h.registerLinkId(), h.linkTypeId(), h.perspectiveId(),
                            linkProperties.getOrDefault(h.registerLinkId(), Map.of()), h.linkAudit(), targetRow));
        }
        return result;
    }

    // Each target's properties and states, one query per target item type (each its own table).
    private Map<UUID, PropertiesAndStates> targetPropertiesAndStates(List<LinkHeader> headers) {
        Map<UUID, PropertiesAndStates> result = new HashMap<>();
        headers.stream().collect(Collectors.groupingBy(LinkHeader::targetItemTypeId,
                        Collectors.mapping(LinkHeader::targetRegisterItemId, Collectors.toSet())))
                .forEach((itemTypeId, ids) -> jdbcClient.sql(
                                "SELECT register_item_id, properties::text AS properties, states::text AS states FROM "
                                        + tableNameFor(itemTypeId) + " WHERE register_item_id IN (:ids)")
                        .param("ids", ids)
                        .query((RowCallbackHandler) rs -> result.put(rs.getObject("register_item_id", UUID.class), new PropertiesAndStates(
                                propertiesById(rs.getString("properties")), currentStateIdsOf(rs.getString("states"))))));
        return result;
    }

    // Each link's own properties, one query per link type (each its own table).
    private Map<UUID, Map<UUID, Object>> linkProperties(List<LinkHeader> headers) {
        Map<UUID, Map<UUID, Object>> result = new HashMap<>();
        headers.stream().collect(Collectors.groupingBy(LinkHeader::linkTypeId,
                        Collectors.mapping(LinkHeader::registerLinkId, Collectors.toSet())))
                .forEach((linkTypeId, ids) -> jdbcClient.sql(
                                "SELECT register_link_id, properties::text AS properties FROM " + linkTableNameFor(linkTypeId)
                                        + " WHERE register_link_id IN (:ids)")
                        .param("ids", ids)
                        .query((RowCallbackHandler) rs -> result.put(rs.getObject("register_link_id", UUID.class),
                                propertiesById(rs.getString("properties")))));
        return result;
    }

    // LinkAccess as "AND ..." clauses over the link query's aliases: ri_mine (source item), rilp_mine
    // (the perspective followed) and ri_other (target item).
    private SqlFragment linkVisibility(LinkAccess access) {
        return switch (access) {
            case LinkAccess.Unrestricted _ -> SqlFragment.empty();
            case LinkAccess.Granted g -> {
                List<String> clauses = new ArrayList<>();
                Map<String, Object> params = new HashMap<>();
                List<String> pairs = new ArrayList<>();
                g.readablePerspectiveIdsBySourceMarker().forEach((markerId, perspectiveIds) -> perspectiveIds.forEach(perspectiveId -> {
                    int i = pairs.size();
                    pairs.add("(:linkReadMarker_" + i + ", :linkReadPerspective_" + i + ")");
                    params.put("linkReadMarker_" + i, markerId);
                    params.put("linkReadPerspective_" + i, perspectiveId);
                }));
                clauses.add(pairs.isEmpty() ? "AND FALSE"
                        : "AND EXISTS (SELECT 1 FROM register_item_marker source_marker WHERE source_marker.register_item_id = ri_mine.id"
                                + " AND (source_marker.marker_id, rilp_mine.perspective_id) IN (" + String.join(", ", pairs) + "))");
                if (g.readableTargetItemTypeIds().isEmpty()) {
                    clauses.add("AND FALSE");
                } else {
                    clauses.add("AND ri_other.item_type_id IN (:readableTargetItemTypeIds)");
                    params.put("readableTargetItemTypeIds", g.readableTargetItemTypeIds());
                }
                if (g.targetItemReadMarkerIds().isEmpty()) {
                    clauses.add("AND FALSE");
                } else {
                    clauses.add("AND ri_other.id IN (SELECT register_item_id FROM register_item_marker WHERE marker_id IN (:targetItemReadMarkerIds))");
                    params.put("targetItemReadMarkerIds", g.targetItemReadMarkerIds());
                }
                yield new SqlFragment(String.join(" ", clauses), params);
            }
        };
    }

    // The committed rows of the given item types that pass rowFilter (written over ri and rt), as the
    // text after FROM plus the aliases to read them through. One type reads its table directly; several
    // are a UNION ALL of one branch per type (each its own table), read through "combined".
    private record Rows(SqlFragment from, RowSource alias) {}

    private Rows rows(List<UUID> itemTypeIds, SqlFragment rowFilter) {
        if (itemTypeIds.size() == 1) {
            Map<String, Object> params = new HashMap<>(rowFilter.params());
            params.put("itemTypeId", itemTypeIds.getFirst());
            return new Rows(new SqlFragment("""
                    register_item ri
                    JOIN %s rt ON rt.register_item_id = ri.id
                    WHERE ri.item_type_id = :itemTypeId
                      AND ri.state = 'COMMITTED'
                      %s
                    """.formatted(tableNameFor(itemTypeIds.getFirst()), rowFilter.sql()), params), RowSource.ITEM_ROW);
        }
        List<String> branches = new ArrayList<>();
        Map<String, Object> params = new HashMap<>(rowFilter.params());
        for (int i = 0; i < itemTypeIds.size(); i++) {
            String typeParam = "branchType_" + i;
            params.put(typeParam, itemTypeIds.get(i));
            branches.add("""
                    SELECT ri.id AS register_item_id, ri.item_id, ri.item_type_id,
                           rt.properties AS properties, rt.states AS states,
                           ri.created_date AS created_date, ri.created_by AS created_by,
                           ri.updated_date AS updated_date, ri.updated_by AS updated_by, ri.state AS visibility_state
                    FROM register_item ri
                    JOIN %s rt ON rt.register_item_id = ri.id
                    WHERE ri.item_type_id = :%s
                      AND ri.state = 'COMMITTED'
                      %s
                    """.formatted(tableNameFor(itemTypeIds.get(i)), typeParam, rowFilter.sql()));
        }
        return new Rows(new SqlFragment("(" + String.join(" UNION ALL ", branches) + ") combined", params), RowSource.COMBINED);
    }

    private List<ItemRow> selectItems(Rows rows, ExplicitProjection.Sort sort, @Nullable Page page) {
        RowSource a = rows.alias();
        SqlFragment orderBy = orderByClause(sort, a);
        return jdbcClient.sql("""
                SELECT %s AS register_item_id, %s AS item_id, %s AS item_type_id,
                       %s::text AS properties, %s::text AS states,
                       %screated_date AS created_date, %screated_by AS created_by,
                       %supdated_date AS updated_date, %supdated_by AS updated_by
                FROM %s
                %s
                %s
                """.formatted(a.registerItemId, a.itemId, a.itemTypeId, a.properties, a.states,
                        a.auditPrefix, a.auditPrefix, a.auditPrefix, a.auditPrefix,
                        rows.from().sql(), orderBy.sql(), page == null ? "" : "LIMIT :limit OFFSET :offset"))
                .params(rows.from().params())
                .params(orderBy.params())
                .params(page == null ? Map.of() : Map.of("limit", page.limit(), "offset", page.offset()))
                .query((rs, _) -> new ItemRow(
                        rs.getObject("register_item_id", UUID.class),
                        rs.getObject("item_id", UUID.class),
                        rs.getObject("item_type_id", UUID.class),
                        propertiesById(rs.getString("properties")),
                        currentStateIdsOf(rs.getString("states")),
                        new Audit(rs.getObject("created_date", OffsetDateTime.class), rs.getObject("created_by", UUID.class),
                                rs.getObject("updated_date", OffsetDateTime.class), rs.getObject("updated_by", UUID.class)),
                        Set.of(), Map.of(), Map.of()))
                .list();
    }

    private long count(Rows rows) {
        return jdbcClient.sql("SELECT COUNT(*) FROM " + rows.from().sql())
                .params(rows.from().params())
                .query(Long.class)
                .single();
    }

    private List<ValueCount> termsFacet(Rows rows, UUID propertyId) {
        String value = rows.alias().properties + "->>'" + propertyId + "'";
        return jdbcClient.sql("""
                SELECT %1$s AS value, COUNT(*) AS count
                FROM %2$s
                GROUP BY %1$s
                ORDER BY count DESC, value ASC NULLS LAST
                """.formatted(value, rows.from().sql()))
                .params(rows.from().params())
                .query((rs, _) -> new ValueCount(rs.getString("value"), rs.getLong("count")))
                .list();
    }

    private List<ValueCount> stateMachineFacet(Rows rows, UUID stateMachineId) {
        String value = "(" + rows.alias().states + "::jsonb)->'" + stateMachineId + "'->>'" + STATE_CURRENT_STATE_ID + "'";
        return jdbcClient.sql("""
                SELECT %1$s AS value, COUNT(*) AS count
                FROM %2$s
                GROUP BY %1$s
                """.formatted(value, rows.from().sql()))
                .params(rows.from().params())
                .query((rs, _) -> new ValueCount(rs.getString("value"), rs.getLong("count")))
                .list();
    }

    // Every terms filter as "AND (...)" clauses over rt.
    private SqlFragment termsFiltersClause(Collection<TermsFilter> filters) {
        List<SqlFragment> clauses = new ArrayList<>();
        int i = 0;
        for (TermsFilter filter : filters) {
            if (filter.values().isEmpty() && !filter.includeNull()) continue;
            String value = "rt.properties->>'" + filter.propertyId() + "'";
            List<String> alternatives = new ArrayList<>();
            Map<String, Object> params = new HashMap<>();
            if (!filter.values().isEmpty()) {
                String param = "terms_" + i++;
                alternatives.add(value + " IN (:" + param + ")");
                params.put(param, filter.values());
            }
            if (filter.includeNull()) alternatives.add(value + " IS NULL");
            clauses.add(new SqlFragment("AND (" + String.join(" OR ", alternatives) + ")", params));
        }
        return combine(clauses.toArray(SqlFragment[]::new));
    }

    private static SqlFragment combine(SqlFragment... fragments) {
        List<String> sql = new ArrayList<>();
        Map<String, Object> params = new HashMap<>();
        for (SqlFragment f : fragments) {
            if (!f.sql().isBlank()) sql.add(f.sql());
            params.putAll(f.params());
        }
        return new SqlFragment(String.join(" ", sql), params);
    }

    // The filter and access as "AND ..." clauses (or nothing), over the aliases ri (register_item) and
    // rt (the item type's properties table).
    private SqlFragment whereClause(Condition filter, Access access) {
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
    private enum RowSource {
        ITEM_ROW("ri.id", "ri.item_id", "ri.item_type_id", "rt.properties", "rt.states", "ri.state", "ri."),
        COMBINED("combined.register_item_id", "combined.item_id", "combined.item_type_id", "combined.properties",
                "combined.states", "combined.visibility_state", "combined.");

        final String registerItemId, itemId, itemTypeId, properties, states, visibilityState, auditPrefix;

        RowSource(String registerItemId, String itemId, String itemTypeId, String properties, String states, String visibilityState,
                  String auditPrefix) {
            this.registerItemId = registerItemId;
            this.itemId = itemId;
            this.itemTypeId = itemTypeId;
            this.properties = properties;
            this.states = states;
            this.visibilityState = visibilityState;
            this.auditPrefix = auditPrefix;
        }
    }

    private SqlFragment orderByClause(Sort sort, RowSource source) {
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
    private Map<UUID, UUID> currentStateIdsOf(String json) {
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

    // --- Writes (the coordinator only) and the lookups the write side and marker pipelines use ---

    // --- Write side: staged at prepare (UNCOMMITTED), flipped/cleaned up at commit/abort ---
    // Updates are never in-place: prepare stages a whole new row: commit deletes the old
    // committed row for the same business id and flips the new one to COMMITTED.

    // binaryPropertyIds: the item type's BINARY properties, which go to register_binary_property
    // rather than the properties JSONB.
    @Override
    public UUID stageItemCreate(UUID itemId, UUID itemTypeId, Map<UUID, Object> properties, Map<UUID, UUID> initialStates,
                                 Set<UUID> binaryPropertyIds, StagingContext staging) {
        Map<String, Object> states = new HashMap<>();
        initialStates.forEach((stateMachineId, stateId) -> states.put(stateMachineId.toString(), Map.of(STATE_CURRENT_STATE_ID, stateId.toString())));

        UUID registerItemId = insertItemRow(itemId, itemTypeId, keysToStrings(excludeKeys(properties, binaryPropertyIds)), states,
                staging.transactionId(), RowAudit.created(staging));
        if (!binaryPropertyIds.isEmpty()) {
            insertBinaryProperties(registerItemId, toBinaryValues(filterKeys(properties, binaryPropertyIds)));
        }
        return registerItemId;
    }

    // The only staging entry point for an already-existing item, regardless of how many ledger
    // entries (property update, state change, both) target it within one transaction. Deliberately
    // NOT split into separate "stage update" / "stage state change" methods: register_item rows are
    // swapped wholesale (never patched in place -- see commitItem), so each staging call reads
    // COMMITTED and inserts one new UNCOMMITTED row. Two independent staging calls for the same
    // item in the same transaction would each read the same stale COMMITTED row and produce two
    // competing UNCOMMITTED rows, which commitItem's single-row lookup can't resolve. The caller
    // (LedgerRegisterCoordinatorImpl.prepare) is responsible for merging every entry that targets a
    // given item within a transaction before calling this once -- this method has no way to detect
    // that on its own, and shouldn't need to.
    //
    // propertiesDiff/stateChanges/stateMachinesEnded are all allowed to be empty (a change to only
    // one is the common case); at least one is expected to be non-empty in practice.
    // stateMachinesEnded removes those machines from the item's _state entirely (END pseudostate
    // reached) -- no tombstone, since re-entry via START is valid.
    //
    // binaryPropertyIds is as for stageItemCreate.
    //
    // Deliberately deferred: stateChanges values are accepted unconditionally -- this does not check
    // that a given stateId is actually reachable from the item's current state via a defined
    // transition. Transition validity/guard enforcement lives in EntityWriteService.executeTransition.
    @Override
    public UUID stageItemChange(UUID itemId, Map<UUID, Object> propertiesDiff, Map<UUID, UUID> stateChanges,
                                 Set<UUID> stateMachinesEnded, Set<UUID> binaryPropertyIds, StagingContext staging) {
        UUID itemTypeId = findItemTypeId(itemId).orElseThrow();

        var current = jdbcClient.sql("""
                SELECT ri.id AS current_register_item_id, rt.properties::text AS properties, rt.states::text AS states,
                       ri.created_date AS created_date, ri.created_by AS created_by
                FROM register_item ri
                JOIN %s rt ON rt.register_item_id = ri.id
                WHERE ri.item_id = :itemId AND ri.state = 'COMMITTED'
                """.formatted(tableNameFor(itemTypeId)))
                .param(PARAM_ITEM_ID, itemId)
                .query((rs, _) -> new CurrentItemRowContent(
                        rs.getObject("current_register_item_id", UUID.class),
                        parseJson(rs.getString(COL_PROPERTIES), "properties"), parseJson(rs.getString(COL_STATES), "states"),
                        rs.getObject(COL_CREATED_DATE, OffsetDateTime.class), rs.getObject(COL_CREATED_BY, UUID.class)))
                .single();

        Map<UUID, Object> nonBinaryDiff = excludeKeys(propertiesDiff, binaryPropertyIds);

        Map<String, Object> mergedProperties = nonBinaryDiff.isEmpty()
                ? current.properties()
                : mergeProperties(current.properties(), keysToStrings(nonBinaryDiff));

        Map<String, Object> mergedStates = new HashMap<>(current.states());
        stateChanges.forEach((stateMachineId, stateId) -> mergedStates.put(stateMachineId.toString(), Map.of(STATE_CURRENT_STATE_ID, stateId.toString())));
        stateMachinesEnded.forEach(stateMachineId -> mergedStates.remove(stateMachineId.toString()));

        UUID registerItemId = insertItemRow(itemId, itemTypeId, mergedProperties, mergedStates, staging.transactionId(),
                RowAudit.updated(current.createdDate(), current.createdBy(), staging));

        // Binary properties live in their own table (register_binary_property), never in the jsonb
        // properties blob above -- same "fully re-materialize per version" shape as mergedProperties,
        // just carried forward from the prior register_item row instead of the jsonb blob, since
        // there's no jsonb to read them back out of.
        if (!binaryPropertyIds.isEmpty()) {
            Map<UUID, UUID> mergedBinaryValues = currentBinaryPropertyValues(current.registerItemId());
            filterKeys(propertiesDiff, binaryPropertyIds).forEach((propertyId, value) -> {
                if (value == null) mergedBinaryValues.remove(propertyId);
                else mergedBinaryValues.put(propertyId, UUID.fromString((String) value));
            });
            insertBinaryProperties(registerItemId, mergedBinaryValues);
        }
        return registerItemId;
    }

    private record CurrentItemRowContent(UUID registerItemId, Map<String, Object> properties, Map<String, Object> states,
                                          OffsetDateTime createdDate, UUID createdBy) {}

    private Map<UUID, Object> filterKeys(Map<UUID, Object> map, Set<UUID> keys) {
        Map<UUID, Object> result = new HashMap<>();
        map.forEach((k, v) -> {
            if (keys.contains(k)) result.put(k, v);
        });
        return result;
    }

    private Map<UUID, Object> excludeKeys(Map<UUID, Object> map, Set<UUID> keys) {
        if (keys.isEmpty()) return map;
        Map<UUID, Object> result = new HashMap<>();
        map.forEach((k, v) -> {
            if (!keys.contains(k)) result.put(k, v);
        });
        return result;
    }

    private Map<UUID, UUID> toBinaryValues(Map<UUID, Object> rawValuesByPropertyId) {
        Map<UUID, UUID> result = new HashMap<>();
        rawValuesByPropertyId.forEach((propertyId, value) -> {
            if (value != null) result.put(propertyId, UUID.fromString((String) value));
        });
        return result;
    }

    private Map<UUID, UUID> currentBinaryPropertyValues(UUID registerItemId) {
        Map<UUID, UUID> result = new HashMap<>();
        jdbcClient.sql("SELECT property_id, binary_id FROM register_binary_property WHERE register_item_id = :registerItemId")
                .param(PARAM_REGISTER_ITEM_ID, registerItemId)
                .query((rs, _) -> Map.entry(rs.getObject("property_id", UUID.class), rs.getObject("binary_id", UUID.class)))
                .list()
                .forEach(e -> result.put(e.getKey(), e.getValue()));
        return result;
    }

    private void insertBinaryProperties(UUID registerItemId, Map<UUID, UUID> binaryIdsByPropertyId) {
        binaryIdsByPropertyId.forEach((propertyId, binaryId) ->
                jdbcClient.sql("INSERT INTO register_binary_property (register_item_id, property_id, binary_id) VALUES (:registerItemId, :propertyId, :binaryId)")
                        .param(PARAM_REGISTER_ITEM_ID, registerItemId)
                        .param("propertyId", propertyId)
                        .param("binaryId", binaryId)
                        .update());
    }

    // The audit columns describe the item, not this row version (see RowAudit). The nullable pair
    // needs explicit ::timestamptz/::uuid casts, or the driver can't type a bound null.
    private UUID insertItemRow(UUID itemId, UUID itemTypeId, Map<String, Object> properties, Map<String, Object> states, UUID transactionId,
                                RowAudit audit) {
        UUID registerItemId = jdbcClient.sql("""
                INSERT INTO register_item (item_id, item_type_id, state, transaction_id, created_date, created_by, updated_date, updated_by)
                VALUES (:itemId, :itemTypeId, 'UNCOMMITTED', :transactionId, :createdDate, :createdBy, :updatedDate::timestamptz, :updatedBy::uuid)
                RETURNING id
                """)
                .param(PARAM_ITEM_ID, itemId)
                .param(PARAM_ITEM_TYPE_ID, itemTypeId)
                .param(PARAM_TRANSACTION_ID, transactionId)
                .param(PARAM_CREATED_DATE, audit.createdDate())
                .param(PARAM_CREATED_BY, audit.createdBy())
                .param(PARAM_UPDATED_DATE, audit.updatedDate())
                .param(PARAM_UPDATED_BY, audit.updatedBy())
                .query(UUID.class)
                .single();

        jdbcClient.sql("INSERT INTO %s (register_item_id, properties, states) VALUES (:registerItemId, :properties::jsonb, :states::jsonb)"
                .formatted(tableNameFor(itemTypeId)))
                .param(PARAM_REGISTER_ITEM_ID, registerItemId)
                .param(COL_PROPERTIES, writeProperties(properties))
                .param(COL_STATES, writeProperties(states))
                .update();

        return registerItemId;
    }

    @Override
    public Optional<UUID> findItemTypeId(UUID itemId) {
        return jdbcClient.sql("SELECT item_type_id FROM register_item WHERE item_id = :itemId AND state = 'COMMITTED'")
                .param(PARAM_ITEM_ID, itemId)
                .query(UUID.class)
                .optional();
    }

    // Merges an update's property diff onto the item's current committed properties, keyed by
    // property id (not name) -- what ContextResolver needs to evaluate a marker decision pipeline's
    // own-property signals against this item's about-to-be-committed state. No name-resolution step
    // (unlike the now-retired DMN-input-map helpers this replaces), so an empty diff still needs the
    // current row read rather than skipping straight to an empty result.
    @Override
    public Map<UUID, Object> resolveMergedPropertiesById(UUID itemId, UUID itemTypeId, Map<UUID, Object> propertiesDiff) {
        Map<String, Object> currentById = currentPropertiesByIdString(itemId, itemTypeId);
        Map<String, Object> mergedById = propertiesDiff.isEmpty()
                ? currentById
                : mergeProperties(currentById, keysToStrings(propertiesDiff));
        return toUuidKeyedMap(mergedById);
    }

    /** This item's current committed properties, keyed by property id -- what NoOpMutationFilter compares an incoming diff against. */
    @Override
    public Map<UUID, Object> currentPropertiesById(UUID itemId, UUID itemTypeId) {
        return toUuidKeyedMap(currentPropertiesByIdString(itemId, itemTypeId));
    }

    private Map<String, Object> currentPropertiesByIdString(UUID itemId, UUID itemTypeId) {
        String currentJson = jdbcClient.sql("""
                SELECT rt.properties::text AS properties
                FROM register_item ri
                JOIN %s rt ON rt.register_item_id = ri.id
                WHERE ri.item_id = :itemId AND ri.state = 'COMMITTED'
                """.formatted(tableNameFor(itemTypeId)))
                .param(PARAM_ITEM_ID, itemId)
                .query(String.class)
                .single();
        return parseJson(currentJson, "properties");
    }

    /** This link's current committed properties, keyed by property id -- the link counterpart to currentPropertiesById. */
    @Override
    public Map<UUID, Object> currentLinkPropertiesById(UUID linkId, UUID linkTypeId) {
        return toUuidKeyedMap(currentLinkPropertiesByIdString(linkId, linkTypeId));
    }

    private Map<String, Object> currentLinkPropertiesByIdString(UUID linkId, UUID linkTypeId) {
        String currentJson = jdbcClient.sql("""
                SELECT rt.properties::text AS properties
                FROM register_link rl
                JOIN %s rt ON rt.register_link_id = rl.id
                WHERE rl.link_id = :linkId AND rl.state = 'COMMITTED'
                """.formatted(linkTableNameFor(linkTypeId)))
                .param(PARAM_LINK_ID, linkId)
                .query(String.class)
                .single();
        return parseJson(currentJson, "properties");
    }

    // Link counterpart to resolveMergedPropertiesById -- merges an update's property diff onto the
    // link's current committed properties, keyed by property id.
    @Override
    public Map<UUID, Object> resolveMergedLinkPropertiesById(UUID linkId, UUID linkTypeId, Map<UUID, Object> propertiesDiff) {
        Map<String, Object> currentById = currentLinkPropertiesByIdString(linkId, linkTypeId);
        Map<String, Object> mergedById = propertiesDiff.isEmpty()
                ? currentById
                : mergeProperties(currentById, keysToStrings(propertiesDiff));
        return toUuidKeyedMap(mergedById);
    }

    // Does a COMMITTED link already exist between these exact two endpoints (same perspectives,
    // same items)? Matching on both perspective ids already pins down the link type -- a
    // perspective id belongs to exactly one schema_link definition -- so no separate link-type
    // filter is needed.
    @Override
    public Optional<UUID> findExistingLinkId(RegisterLinkEndpoint endpointA, RegisterLinkEndpoint endpointB) {
        return jdbcClient.sql("""
                SELECT rl.link_id
                FROM register_link rl
                JOIN register_item_link_perspective rilp_a ON rilp_a.register_link_id = rl.id
                JOIN register_item ri_a ON ri_a.id = rilp_a.register_item_id
                JOIN register_item_link_perspective rilp_b ON rilp_b.register_link_id = rl.id
                JOIN register_item ri_b ON ri_b.id = rilp_b.register_item_id
                WHERE rl.state = 'COMMITTED'
                  AND rilp_a.perspective_id = :perspectiveA AND ri_a.item_id = :itemA
                  AND rilp_b.perspective_id = :perspectiveB AND ri_b.item_id = :itemB
                """)
                .param("perspectiveA", endpointA.perspectiveId())
                .param("itemA", endpointA.itemId())
                .param("perspectiveB", endpointB.perspectiveId())
                .param("itemB", endpointB.itemId())
                .query(UUID.class)
                .optional();
    }

    /** Does a COMMITTED link currently exist under this id? */
    @Override
    public boolean linkExists(UUID linkId) {
        return jdbcClient.sql("SELECT 1 FROM register_link WHERE link_id = :linkId AND state = 'COMMITTED'")
                .param(PARAM_LINK_ID, linkId)
                .query(Integer.class)
                .optional()
                .isPresent();
    }

    private Map<UUID, Object> toUuidKeyedMap(Map<String, Object> byIdString) {
        Map<UUID, Object> result = new HashMap<>();
        byIdString.forEach((id, value) -> result.put(UUID.fromString(id), value));
        return result;
    }

    // Returns the item's binary values in the replaced committed row (none for a create) and in the
    // newly committed one. Both are read before the old row is deleted, since its
    // register_binary_property rows cascade-delete with it.
    @Override
    public BinaryValueChange commitItem(UUID itemId, UUID transactionId, UUID commitId) {
        UUID stagedRegisterItemId = jdbcClient.sql("""
                SELECT id FROM register_item WHERE item_id = :itemId AND transaction_id = :transactionId AND state = 'UNCOMMITTED'
                """)
                .param(PARAM_ITEM_ID, itemId)
                .param(PARAM_TRANSACTION_ID, transactionId)
                .query(UUID.class)
                .single();

        Optional<UUID> oldRegisterItemId = jdbcClient.sql("""
                SELECT id FROM register_item WHERE item_id = :itemId AND state = 'COMMITTED' AND id != :stagedRegisterItemId
                """)
                .param(PARAM_ITEM_ID, itemId)
                .param("stagedRegisterItemId", stagedRegisterItemId)
                .query(UUID.class)
                .optional();

        BinaryValueChange binaryChange = new BinaryValueChange(
                oldRegisterItemId.map(this::currentBinaryPropertyValues).orElse(Map.of()),
                currentBinaryPropertyValues(stagedRegisterItemId));

        // An update replaces the row wholesale (new surrogate id), but existing perspective rows
        // still point at the old one -- move them forward before deleting it, or the FK from
        // register_item_link_perspective (no cascade on that side) blocks the delete.
        if (oldRegisterItemId.isPresent()) {
            jdbcClient.sql("UPDATE register_item_link_perspective SET register_item_id = :newId WHERE register_item_id = :oldId")
                    .param("newId", stagedRegisterItemId)
                    .param(PARAM_OLD_ID, oldRegisterItemId.get())
                    .update();

            // register_item_marker also FKs to register_item.id, ON DELETE CASCADE -- without this,
            // the DELETE below would silently wipe every marker this item had. Same "row-swap
            // forgot to carry something forward" bug class as the states carry-forward fix, just via
            // an FK cascade instead of a missing field.
            jdbcClient.sql("UPDATE register_item_marker SET register_item_id = :newId WHERE register_item_id = :oldId")
                    .param("newId", stagedRegisterItemId)
                    .param(PARAM_OLD_ID, oldRegisterItemId.get())
                    .update();

            jdbcClient.sql("DELETE FROM register_item WHERE id = :oldId")
                    .param(PARAM_OLD_ID, oldRegisterItemId.get())
                    .update();
        }

        // No updated_date/updated_by stamp here -- staging (insertItemRow) already wrote the
        // correct final values, taken from the exact same prepare() call's actor/timestamp. A
        // fresh NOW() here would silently drift from the ledger entry that actually caused this.
        jdbcClient.sql("UPDATE register_item SET state = 'COMMITTED', commit_id = :commitId WHERE id = :stagedRegisterItemId")
                .param("commitId", commitId)
                .param("stagedRegisterItemId", stagedRegisterItemId)
                .update();
        return binaryChange;
    }

    // Returns the binary values the item held, all now detached. They're read before the delete:
    // register_binary_property rows cascade-delete with the register_item row.
    @Override
    public BinaryValueChange deleteItem(UUID itemId) {
        BinaryValueChange detached = jdbcClient.sql("SELECT id FROM register_item WHERE item_id = :itemId AND state = 'COMMITTED'")
                .param(PARAM_ITEM_ID, itemId)
                .query(UUID.class)
                .optional()
                .map(registerItemId -> new BinaryValueChange(currentBinaryPropertyValues(registerItemId), Map.of()))
                .orElse(BinaryValueChange.NONE);

        jdbcClient.sql("DELETE FROM register_item WHERE item_id = :itemId AND state = 'COMMITTED'")
                .param(PARAM_ITEM_ID, itemId)
                .update();
        return detached;
    }

    @Override
    public UUID stageLinkCreate(UUID linkId, UUID linkTypeId, RegisterLinkEndpoint endpointA, RegisterLinkEndpoint endpointB,
                                 Map<UUID, Object> properties, StagingContext staging) {
        UUID registerLinkId = insertLinkRow(linkId, linkTypeId, keysToStrings(properties), staging.transactionId(), RowAudit.created(staging));
        insertPerspectiveRows(registerLinkId, List.of(endpointA, endpointB), staging.transactionId());
        return registerLinkId;
    }

    @Override
    public Optional<UUID> findLinkTypeId(UUID linkId) {
        return jdbcClient.sql("SELECT link_definition_id FROM register_link WHERE link_id = :linkId AND state = 'COMMITTED'")
                .param(PARAM_LINK_ID, linkId)
                .query(UUID.class)
                .optional();
    }

    // How many committed links currently use this perspective for this item -- what
    // MutationRequestProcessor.checkLinkPerspectiveCardinality baselines a new delta against for an
    // item that already existed before this request (a brand new item's own baseline is zero,
    // never queried at all).
    @Override
    public int countLinksForPerspective(UUID itemId, UUID perspectiveId) {
        return jdbcClient.sql("""
                SELECT count(*) FROM register_item_link_perspective rilp
                JOIN register_link rl ON rl.id = rilp.register_link_id
                JOIN register_item ri ON ri.id = rilp.register_item_id
                WHERE ri.item_id = :itemId AND ri.state = 'COMMITTED'
                  AND rl.state = 'COMMITTED' AND rilp.perspective_id = :perspectiveId
                """)
                .param(PARAM_ITEM_ID, itemId)
                .param(PARAM_PERSPECTIVE_ID, perspectiveId)
                .query(Integer.class)
                .single();
    }

    // A link's two current endpoints, by (perspectiveId, itemId) -- what a LinkDeleteMutation needs
    // to know which perspective-cardinality counts it's about to lower, since the mutation itself
    // only ever carries the linkId. Mirrors the endpoint-lookup query stageLinkUpdate's own re-point
    // step already runs, just against the link's own id instead of its about-to-be-replaced register row.
    @Override
    public List<RegisterLinkEndpoint> findLinkEndpoints(UUID linkId) {
        return jdbcClient.sql("""
                SELECT rilp.perspective_id, ri.item_id
                FROM register_item_link_perspective rilp
                JOIN register_link rl ON rl.id = rilp.register_link_id
                JOIN register_item ri ON ri.id = rilp.register_item_id
                WHERE rl.link_id = :linkId AND rl.state = 'COMMITTED' AND ri.state = 'COMMITTED'
                """)
                .param(PARAM_LINK_ID, linkId)
                .query((rs, _) -> new RegisterLinkEndpoint(
                        rs.getObject(COL_PERSPECTIVE_ID, UUID.class),
                        rs.getObject(COL_ITEM_ID, UUID.class)))
                .list();
    }

    @Override
    public UUID stageLinkUpdate(UUID linkId, Map<UUID, Object> propertiesDiff, StagingContext staging) {
        var current = jdbcClient.sql("""
                SELECT id AS current_register_link_id, created_date, created_by
                FROM register_link WHERE link_id = :linkId AND state = 'COMMITTED'
                """)
                .param(PARAM_LINK_ID, linkId)
                .query((rs, _) -> new CurrentLinkRowContent(
                        rs.getObject("current_register_link_id", UUID.class),
                        rs.getObject(COL_CREATED_DATE, OffsetDateTime.class), rs.getObject(COL_CREATED_BY, UUID.class)))
                .single();
        UUID currentRegisterLinkId = current.registerLinkId();

        UUID linkTypeId = findLinkTypeId(linkId).orElseThrow();

        Map<String, Object> currentProperties = jdbcClient.sql("SELECT properties::text AS properties FROM %s WHERE register_link_id = :id"
                .formatted(linkTableNameFor(linkTypeId)))
                .param("id", currentRegisterLinkId)
                .query((rs, _) -> parseJson(rs.getString(COL_PROPERTIES), "properties"))
                .single();

        Map<String, Object> merged = mergeProperties(currentProperties, keysToStrings(propertiesDiff));
        UUID registerLinkId = insertLinkRow(linkId, linkTypeId, merged, staging.transactionId(),
                RowAudit.updated(current.createdDate(), current.createdBy(), staging));

        // Endpoints don't change on an update (Section 8): re-resolve each one to whichever
        // register_item row currently represents it in this transaction, since an endpoint's
        // own item may itself be concurrently staged for update in the same transaction.
        List<RegisterLinkEndpoint> endpoints = jdbcClient.sql("""
                SELECT rilp.perspective_id, ri.item_id
                FROM register_item_link_perspective rilp
                JOIN register_item ri ON ri.id = rilp.register_item_id
                WHERE rilp.register_link_id = :oldLinkId
                """)
                .param("oldLinkId", currentRegisterLinkId)
                .query((rs, _) -> new RegisterLinkEndpoint(
                        rs.getObject(COL_PERSPECTIVE_ID, UUID.class),
                        rs.getObject(COL_ITEM_ID, UUID.class)))
                .list();
        insertPerspectiveRows(registerLinkId, endpoints, staging.transactionId());

        return registerLinkId;
    }

    private record CurrentLinkRowContent(UUID registerLinkId, OffsetDateTime createdDate, UUID createdBy) {}

    // A row's audit columns. created* describe the item or link itself, so an update carries them
    // forward from the row it supersedes. updated* stay null until the first real update: the
    // creator hasn't also "updated" what they just created.
    private record RowAudit(OffsetDateTime createdDate, UUID createdBy, @Nullable OffsetDateTime updatedDate, @Nullable UUID updatedBy) {

        static RowAudit created(StagingContext staging) {
            return new RowAudit(staging.createdDate(), staging.actorUserId(), null, null);
        }

        static RowAudit updated(OffsetDateTime createdDate, UUID createdBy, StagingContext staging) {
            return new RowAudit(createdDate, createdBy, staging.createdDate(), staging.actorUserId());
        }
    }

    // Same audit semantics and casts as insertItemRow.
    private UUID insertLinkRow(UUID linkId, UUID linkTypeId, Map<String, Object> properties, UUID transactionId, RowAudit audit) {
        UUID registerLinkId = jdbcClient.sql("""
                INSERT INTO register_link (link_id, link_definition_id, state, transaction_id, created_date, created_by, updated_date, updated_by)
                VALUES (:linkId, :linkTypeId, 'UNCOMMITTED', :transactionId, :createdDate, :createdBy, :updatedDate::timestamptz, :updatedBy::uuid)
                RETURNING id
                """)
                .param(PARAM_LINK_ID, linkId)
                .param("linkTypeId", linkTypeId)
                .param(PARAM_TRANSACTION_ID, transactionId)
                .param(PARAM_CREATED_DATE, audit.createdDate())
                .param(PARAM_CREATED_BY, audit.createdBy())
                .param(PARAM_UPDATED_DATE, audit.updatedDate())
                .param(PARAM_UPDATED_BY, audit.updatedBy())
                .query(UUID.class)
                .single();

        jdbcClient.sql("INSERT INTO %s (register_link_id, properties) VALUES (:registerLinkId, :properties::jsonb)"
                .formatted(linkTableNameFor(linkTypeId)))
                .param("registerLinkId", registerLinkId)
                .param(COL_PROPERTIES, writeProperties(properties))
                .update();

        return registerLinkId;
    }

    private void insertPerspectiveRows(UUID registerLinkId, List<RegisterLinkEndpoint> endpoints, UUID transactionId) {
        for (RegisterLinkEndpoint endpoint : endpoints) {
            UUID registerItemId = resolveRegisterItemId(endpoint.itemId(), transactionId);
            jdbcClient.sql("""
                    INSERT INTO register_item_link_perspective (register_link_id, perspective_id, register_item_id)
                    VALUES (:registerLinkId, :perspectiveId, :registerItemId)
                    """)
                    .param("registerLinkId", registerLinkId)
                    .param(PARAM_PERSPECTIVE_ID, endpoint.perspectiveId())
                    .param(PARAM_REGISTER_ITEM_ID, registerItemId)
                    .update();
        }
    }

    @Override
    public void commitLink(UUID linkId, UUID transactionId, UUID commitId) {
        UUID stagedRegisterLinkId = jdbcClient.sql("""
                SELECT id FROM register_link WHERE link_id = :linkId AND transaction_id = :transactionId AND state = 'UNCOMMITTED'
                """)
                .param(PARAM_LINK_ID, linkId)
                .param(PARAM_TRANSACTION_ID, transactionId)
                .query(UUID.class)
                .single();

        jdbcClient.sql("DELETE FROM register_link WHERE link_id = :linkId AND state = 'COMMITTED' AND id != :stagedRegisterLinkId")
                .param(PARAM_LINK_ID, linkId)
                .param("stagedRegisterLinkId", stagedRegisterLinkId)
                .update();

        // No updated_date/updated_by stamp here -- same reasoning as commitItem's own comment.
        jdbcClient.sql("UPDATE register_link SET state = 'COMMITTED', commit_id = :commitId WHERE id = :stagedRegisterLinkId")
                .param("commitId", commitId)
                .param("stagedRegisterLinkId", stagedRegisterLinkId)
                .update();
    }

    @Override
    public void deleteLink(UUID linkId) {
        jdbcClient.sql("DELETE FROM register_link WHERE link_id = :linkId AND state = 'COMMITTED'")
                .param(PARAM_LINK_ID, linkId)
                .update();
    }

    // Markers are never staged (see LedgerRegisterCoordinatorImpl.prepare) -- unlike a create/
    // update, there's no new register_item/register_link row being built, just a plain
    // insert/delete against an already-COMMITTED one, applied directly at commit like a delete.
    @Override
    public void postItemMarkerAdd(UUID itemId, UUID markerId) {
        jdbcClient.sql("""
                INSERT INTO register_item_marker (register_item_id, marker_id)
                SELECT id, :markerId FROM register_item WHERE item_id = :itemId AND state = 'COMMITTED'
                ON CONFLICT DO NOTHING
                """)
                .param(PARAM_ITEM_ID, itemId)
                .param("markerId", markerId)
                .update();
    }

    @Override
    public void postItemMarkerRemove(UUID itemId, UUID markerId) {
        jdbcClient.sql("""
                DELETE FROM register_item_marker
                WHERE marker_id = :markerId
                  AND register_item_id = (SELECT id FROM register_item WHERE item_id = :itemId AND state = 'COMMITTED')
                """)
                .param(PARAM_ITEM_ID, itemId)
                .param("markerId", markerId)
                .update();
    }

    // FactResolver's own item-traversal method -- see that interface's class comment for why
    // this class implements it directly rather than through a separate adapter: every data access it
    // needs already lives here. Same join shape as findLinksForItem just below, narrowed to one
    // named perspective (as seen from itemId's own side) instead of every link the item has.
    @Override
    public List<UUID> linkedItemIds(UUID itemId, UUID linkPerspectiveId) {
        return jdbcClient.sql("""
                SELECT ri_other.item_id AS connected_item_id
                FROM register_item ri_mine
                JOIN register_item_link_perspective rilp_mine  ON rilp_mine.register_item_id = ri_mine.id
                JOIN register_link                 rl          ON rl.id = rilp_mine.register_link_id AND rl.state = 'COMMITTED'
                JOIN register_item_link_perspective rilp_other ON rilp_other.register_link_id = rl.id AND rilp_other.id != rilp_mine.id
                JOIN register_item                 ri_other    ON ri_other.id = rilp_other.register_item_id
                WHERE ri_mine.item_id = :itemId AND ri_mine.state = 'COMMITTED'
                  AND rilp_mine.perspective_id = :perspectiveId
                """)
                .param(PARAM_ITEM_ID, itemId)
                .param(PARAM_PERSPECTIVE_ID, linkPerspectiveId)
                .query(UUID.class)
                .list();
    }

    @Override
    public Map<UUID, Object> propertiesOfItem(UUID itemId) {
        UUID itemTypeId = findItemTypeId(itemId).orElseThrow(() -> new IllegalArgumentException("Unknown item: " + itemId));
        return currentPropertiesById(itemId, itemTypeId);
    }

    @Override
    public UUID currentStateId(UUID itemId, UUID stateMachineId) {
        return currentStateIds(itemId).get(stateMachineId);
    }


    @Override
    public List<RegisterLinkedItem> findLinksForItem(UUID itemId) {
        return jdbcClient.sql("""
                SELECT rl.link_id AS link_id, ri_other.item_id AS connected_item_id
                FROM register_item ri_mine
                JOIN register_item_link_perspective rilp_mine  ON rilp_mine.register_item_id = ri_mine.id
                JOIN register_link                 rl          ON rl.id = rilp_mine.register_link_id AND rl.state = 'COMMITTED'
                JOIN register_item_link_perspective rilp_other ON rilp_other.register_link_id = rl.id AND rilp_other.id != rilp_mine.id
                JOIN register_item                 ri_other    ON ri_other.id = rilp_other.register_item_id
                WHERE ri_mine.item_id = :itemId AND ri_mine.state = 'COMMITTED'
                """)
                .param(PARAM_ITEM_ID, itemId)
                .query((rs, _) -> new RegisterLinkedItem(
                        rs.getObject("link_id", UUID.class),
                        rs.getObject("connected_item_id", UUID.class)))
                .list();
    }

    // Prefers this transaction's own staged (UNCOMMITTED) row for itemId over the existing
    // committed one, so a link create/update referencing an item concurrently staged in the
    // same transaction resolves to the row that will actually still exist after commit.
    private UUID resolveRegisterItemId(UUID itemId, UUID transactionId) {
        Optional<UUID> staged = jdbcClient.sql("""
                SELECT id FROM register_item WHERE item_id = :itemId AND transaction_id = :transactionId AND state = 'UNCOMMITTED'
                """)
                .param(PARAM_ITEM_ID, itemId)
                .param(PARAM_TRANSACTION_ID, transactionId)
                .query(UUID.class)
                .optional();
        if (staged.isPresent()) return staged.get();

        return jdbcClient.sql("SELECT id FROM register_item WHERE item_id = :itemId AND state = 'COMMITTED'")
                .param(PARAM_ITEM_ID, itemId)
                .query(UUID.class)
                .single();
    }

    // Named to match LedgerStoreWriter.abort() -- this and that together are what
    // LedgerRegisterCoordinator.abort() calls to discard a prepared-but-not-yet-committed
    // transaction's staged rows on both sides.
    @Override
    public void abort(UUID transactionId) {
        jdbcClient.sql("DELETE FROM register_item WHERE transaction_id = :transactionId AND state = 'UNCOMMITTED'")
                .param(PARAM_TRANSACTION_ID, transactionId)
                .update();
        jdbcClient.sql("DELETE FROM register_link WHERE transaction_id = :transactionId AND state = 'UNCOMMITTED'")
                .param(PARAM_TRANSACTION_ID, transactionId)
                .update();
    }

    private Map<String, Object> mergeProperties(Map<String, Object> current, Map<String, Object> diff) {
        Map<String, Object> merged = new HashMap<>(current);
        diff.forEach((key, value) -> {
            if (value == null) merged.remove(key);
            else merged.put(key, value);
        });
        return merged;
    }

    private String writeProperties(Map<String, Object> properties) {
        return objectMapper.writeValueAsString(properties);
    }

    // The register's JSONB property columns are keyed by property id (stringified), never by name
    // -- ids are stable, names are mutable (renameable via UpdatePropertyDefinitionMutation), and a
    // register row has to keep meaning the same thing across a rename with zero data migration.
    // This is the write-side half of that: LedgerEntry already carries properties keyed by id, so
    // there's nothing to resolve here, just a key-type change for JSON (object keys must be
    // strings). The read side (propertyPathsByIdForItemType/ForLinkType, used via namesForIds) is
    // the mirror image, resolving back to names only at the point a client-facing response is
    // assembled -- see assembleProjectedItems and fetchPropertiesByRegisterItemId.
    private Map<String, Object> keysToStrings(Map<UUID, Object> propertiesById) {
        Map<String, Object> byIdString = new HashMap<>();
        propertiesById.forEach((propertyId, value) -> byIdString.put(propertyId.toString(), value));
        return byIdString;
    }

    // The item's current state id per state machine it is participating in (empty for machines it
    // isn't in). Reads the committed register row's states JSONB, shape
    // { "<stateMachineId>": { "currentStateId": "<stateId>" } }.
    @Override
    public Map<UUID, UUID> currentStateIds(UUID itemId) {
        UUID itemTypeId = findItemTypeId(itemId).orElseThrow(() -> new IllegalArgumentException("Unknown item: " + itemId));
        String statesJson = jdbcClient.sql("""
                SELECT rt.states::text AS states
                FROM register_item ri
                JOIN %s rt ON rt.register_item_id = ri.id
                WHERE ri.item_id = :itemId AND ri.state = 'COMMITTED'
                """.formatted(tableNameFor(itemTypeId)))
                .param(PARAM_ITEM_ID, itemId)
                .query(String.class).optional().orElse("{}");
        Map<String, Object> byId = parseJson(statesJson, "states");
        Map<UUID, UUID> result = new HashMap<>();
        byId.forEach((smId, entry) -> {
            if (entry instanceof Map<?, ?> m && m.get(STATE_CURRENT_STATE_ID) != null) {
                result.put(UUID.fromString(smId), UUID.fromString(m.get(STATE_CURRENT_STATE_ID).toString()));
            }
        });
        return result;
    }

    // --- Schema change reactions ---
    //
    // Each item/link type gets its own physical properties table (see tableNameFor/
    // linkTableNameFor), so that table has to be created the moment the type is defined and
    // dropped the moment it's deleted -- otherwise projection queries against a type created
    // after boot fail with "relation does not exist", since the static V1_0_0_1__baseline.sql Flyway
    // migration only creates the shared register_* tables once, for whatever types already
    // existed when it ran. Traits have no register-side table of their own (their properties live
    // in the owning item type's JSONB blob), so trait events are intentionally ignored here.

    @Override
    @EventListener
    public void onSchemaChange(SchemaChangeEvent event) {
        switch (event) {
            case SchemaChangeEvent.ItemTypeCreated e -> createItemTypeTable(e.itemTypeId());
            case SchemaChangeEvent.ItemTypeDeleted e -> dropItemTypeTable(e.itemTypeId());
            case SchemaChangeEvent.LinkTypeCreated e -> createLinkTypeTable(e.linkTypeId());
            case SchemaChangeEvent.LinkTypeDeleted e -> dropLinkTypeTable(e.linkTypeId());
            // Traits have no register-side table of their own (see above).
            case SchemaChangeEvent.TraitCreated _, SchemaChangeEvent.TraitDeleted _ -> { /* nothing */ }
        }
    }

    public void createItemTypeTable(UUID itemTypeId) {
        String tableName = tableNameFor(itemTypeId);
        // states is keyed by state-machine id (stringified), mirroring properties' own id-keying --
        // see keysToStrings/propertyPathsByIdForItemType's comment. Each value is a small object
        // (currently just currentStateId, currentTransitionId later) rather than the state id
        // directly, leaving room to add fields without a column/shape migration. Only item types
        // have this column -- state machines are schema_state_machine.item_definition_id-scoped,
        // links can't have one.
        jdbcClient.sql("""
                CREATE TABLE %s (
                    register_item_id UUID PRIMARY KEY REFERENCES register_item(id) ON DELETE CASCADE,
                    properties       JSONB,
                    states           JSONB
                )
                """.formatted(tableName)).update();
        jdbcClient.sql("CREATE INDEX ON %s USING GIN (properties)".formatted(tableName)).update();
        jdbcClient.sql("CREATE INDEX ON %s USING GIN (states)".formatted(tableName)).update();
    }

    public void dropItemTypeTable(UUID itemTypeId) {
        jdbcClient.sql("DROP TABLE IF EXISTS " + tableNameFor(itemTypeId)).update();
    }

    public void createLinkTypeTable(UUID linkTypeId) {
        String tableName = linkTableNameFor(linkTypeId);
        jdbcClient.sql("""
                CREATE TABLE %s (
                    register_link_id UUID PRIMARY KEY REFERENCES register_link(id) ON DELETE CASCADE,
                    properties       JSONB
                )
                """.formatted(tableName)).update();
        jdbcClient.sql("CREATE INDEX ON %s USING GIN (properties)".formatted(tableName)).update();
    }

    public void dropLinkTypeTable(UUID linkTypeId) {
        jdbcClient.sql("DROP TABLE IF EXISTS " + linkTableNameFor(linkTypeId)).update();
    }
}
