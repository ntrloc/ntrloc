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
import org.springframework.lang.Nullable;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.concurrent.atomic.AtomicInteger;

// The register's storage: SQL against the register's own tables, and nothing else. It knows the
// physical layout (one properties table per item type, JSONB keyed by property id, the states
// document's shape) and never the schema: every ExplicitProjection it receives is already resolved
// to ids. Projection SQL lives here; the write side is still in RegisterPartitionManager
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
                                propertiesById(rs.getString("properties")), currentStateIds(rs.getString("states"))))));
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
                        currentStateIds(rs.getString("states")),
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
