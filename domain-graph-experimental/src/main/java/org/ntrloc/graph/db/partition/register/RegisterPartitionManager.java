package org.ntrloc.graph.db.partition.register;

import tools.jackson.databind.ObjectMapper;
import org.ntrloc.graph.db.partition.binary.BinaryPartitionManager;
import org.ntrloc.graph.db.partition.binary.BinaryPropertyObject;
import org.ntrloc.graph.db.projection.AndPredicate;
import org.ntrloc.graph.db.projection.CollectionProjectionSpec;
import org.ntrloc.graph.db.projection.FacetBucket;
import org.ntrloc.graph.db.projection.FacetFilter;
import org.ntrloc.graph.db.projection.NotPredicate;
import org.ntrloc.graph.db.projection.Predicate;
import org.ntrloc.graph.db.partition.schema.SchemaManager;
import org.ntrloc.graph.db.partition.schema.definition.PropertyCardinality;
import org.ntrloc.graph.db.partition.schema.definition.PropertyType;
import org.ntrloc.graph.db.partition.schema.definition.view.admin.AdminItemDefinitionView;
import org.ntrloc.graph.db.partition.schema.definition.view.admin.AdminPropertyDefinitionView;
import org.ntrloc.graph.db.partition.schema.definition.view.admin.AdminStateMachineView;
import org.ntrloc.graph.db.partition.schema.definition.view.admin.AdminStateView;
import org.ntrloc.graph.db.partition.schema.event.SchemaChangeEvent;
import org.ntrloc.graph.db.partition.schema.event.SchemaChangeListener;
import org.springframework.context.event.EventListener;
import org.ntrloc.graph.db.projection.ProjectedItem;
import org.ntrloc.graph.db.projection.ProjectedItemState;
import org.ntrloc.graph.db.projection.ProjectedLink;
import org.ntrloc.graph.db.projection.OrPredicate;
import org.ntrloc.graph.db.projection.ProjectionResult;
import org.ntrloc.graph.db.projection.PropertyExistencePredicate;
import org.ntrloc.graph.db.projection.PropertyValuePredicate;
import org.ntrloc.graph.db.projection.RangeFacetFilter;
import org.ntrloc.graph.db.projection.DateRangeFacetFilter;
import org.ntrloc.graph.db.projection.StateValuePredicate;
import org.ntrloc.graph.db.projection.TermsFacetFilter;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

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
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Component
public class RegisterPartitionManager implements SchemaChangeListener {

    private final JdbcClient jdbcClient;
    private final ObjectMapper objectMapper;
    private final BinaryPartitionManager binaryPartitionManager;
    private final SchemaManager schemaManager;

    public RegisterPartitionManager(JdbcClient jdbcClient, ObjectMapper objectMapper,
                                    BinaryPartitionManager binaryPartitionManager, SchemaManager schemaManager) {
        this.jdbcClient = jdbcClient;
        this.objectMapper = objectMapper;
        this.binaryPartitionManager = binaryPartitionManager;
        this.schemaManager = schemaManager;
    }

    private static final Map<String, String> SYSTEM_SORT_COLUMNS = Map.of(
            "itemId",          "ri.item_id",
            "itemType",        "si.name",
            "createdAt",       "ri.created_at",
            "updatedAt",       "ri.updated_at",
            "visibilityState", "ri.state"
    );

    // Applied when CollectionProjectionSpec.limit()/offset() are null or out of range, rather than
    // ever running the main SELECT unbounded -- a projection with no filter narrow enough (or an
    // MCP tool call from an LLM that never thinks to set one) would otherwise return every
    // COMMITTED row of the item type in one response.
    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_LIMIT = 500;

    private int effectiveOffset(CollectionProjectionSpec spec) {
        return spec.offset() != null && spec.offset() > 0 ? spec.offset() : 0;
    }

    private int effectiveLimit(CollectionProjectionSpec spec) {
        if (spec.limit() == null || spec.limit() <= 0) return DEFAULT_LIMIT;
        return Math.min(spec.limit(), MAX_LIMIT);
    }

    private List<String> facetableFieldsFor(UUID itemTypeId) {
        return schemaManager.getAdminSchema().items().stream()
                .filter(item -> item.id().equals(itemTypeId))
                .findFirst()
                .map(item -> item.properties().stream()
                        .filter(p -> isTermsFacetable(p.type(), p.cardinality(), p.controlledListId()))
                        .map(AdminPropertyDefinitionView::name)
                        .toList())
                .orElse(List.of());
    }

    private boolean isTermsFacetable(PropertyType type, PropertyCardinality cardinality, UUID controlledListId) {
        if (cardinality != PropertyCardinality.SINGLE) return false;
        return switch (type) {
            case STRING  -> controlledListId != null;
            case BOOLEAN -> true;
            default      -> false;
        };
    }

    private static final Pattern SAFE_FIELD_NAME = Pattern.compile("^[a-zA-Z][a-zA-Z0-9_]*$");

    private String sanitizeFieldName(String field) {
        if (field == null || !SAFE_FIELD_NAME.matcher(field).matches()) {
            throw new IllegalArgumentException("Invalid facet field name: " + field);
        }
        return field;
    }

    private String orderByClause(CollectionProjectionSpec spec, UUID itemTypeId) {
        if (spec.sortField() == null || spec.sortField().isBlank()) return "ORDER BY ri.item_id ASC";
        String direction = "DESC".equalsIgnoreCase(spec.sortDirection()) ? "DESC" : "ASC";
        String column = SYSTEM_SORT_COLUMNS.containsKey(spec.sortField())
                ? SYSTEM_SORT_COLUMNS.get(spec.sortField())
                : "rt.properties->>'" + resolvePropertyId(itemTypeId, spec.sortField()) + "'";
        return "ORDER BY " + column + " " + direction + " NULLS LAST, ri.item_id ASC";
    }

    private record RawItem(UUID registerItemId, UUID itemId, String itemType, Map<String, Object> properties, Map<String, Object> states) {}

    public ProjectionResult project(UUID itemTypeId, CollectionProjectionSpec spec, String binaryBaseUrl) {
        String tableName = tableNameFor(itemTypeId);
        SqlFragment filterFragment = buildPredicateFragment(spec.filter(), itemTypeId);

        List<FacetFilter> activeFacetFilters = spec.facetFilters() != null ? spec.facetFilters() : List.of();
        List<String> requestedFacets = spec.facets() == null ? List.of()
                : spec.facets().isEmpty() ? facetableFieldsFor(itemTypeId)
                : spec.facets();

        // Build one SQL fragment per active facet filter, keyed by field
        AtomicInteger facetParamCounter = new AtomicInteger();
        Map<String, SqlFragment> facetFragmentsByField = new LinkedHashMap<>();
        for (FacetFilter ff : activeFacetFilters) {
            facetFragmentsByField.put(ff.field(), buildFacetFilterFragment(ff, itemTypeId, facetParamCounter));
        }
        SqlFragment allFacetFilters = combineFragments(facetFragmentsByField.values());

        // totalCount: base filter only
        long totalCount = runCount(tableName, itemTypeId, filterFragment, SqlFragment.empty());

        // facetedCount: base filter + all facet filters
        long facetedCount = activeFacetFilters.isEmpty() ? totalCount
                : runCount(tableName, itemTypeId, filterFragment, allFacetFilters);

        // Facet GROUP BY queries — disjunctive: each facet excludes its own active filter
        Map<String, List<FacetBucket>> facets = null;
        if (!requestedFacets.isEmpty()) {
            facets = new LinkedHashMap<>();
            for (String field : requestedFacets) {
                SqlFragment otherFilters = combineFragments(
                        facetFragmentsByField.entrySet().stream()
                                .filter(e -> !e.getKey().equals(field))
                                .map(Map.Entry::getValue)
                                .toList());
                facets.put(field, runTermsFacetQuery(tableName, itemTypeId, filterFragment, otherFilters, field));
            }
        }

        // Entirely separate from the property-facets block above -- no facetFilters interaction
        // (there's no equivalent "state machine facet filter" type, unlike TermsFacetFilter), just
        // the base predicate filter. Never auto-populated: only runs for machines the client
        // explicitly named (see CollectionProjectionSpec.stateMachineFacets' own comment).
        List<String> requestedStateMachineFacets = spec.stateMachineFacets() != null ? spec.stateMachineFacets() : List.of();
        Map<String, List<FacetBucket>> stateMachineFacets = null;
        if (!requestedStateMachineFacets.isEmpty()) {
            stateMachineFacets = new LinkedHashMap<>();
            for (String machineName : requestedStateMachineFacets) {
                stateMachineFacets.put(machineName, runStateMachineFacetQuery(tableName, itemTypeId, filterFragment, machineName));
            }
        }

        if (facetedCount == 0) {
            return new ProjectionResult(List.of(), totalCount, 0, facets, stateMachineFacets);
        }

        List<RawItem> rawItems = jdbcClient.sql("""
                SELECT ri.id AS register_item_id, ri.item_id, si.name AS item_type, rt.properties::text AS properties, rt.states::text AS states
                FROM register_item ri
                JOIN %s rt ON rt.register_item_id = ri.id
                JOIN schema_item si ON si.id = ri.item_type_id
                WHERE ri.item_type_id = :itemTypeId
                  AND ri.state = 'COMMITTED'
                  %s
                  %s
                %s
                LIMIT :limit OFFSET :offset
                """.formatted(tableName, filterFragment.sql(), allFacetFilters.sql(), orderByClause(spec, itemTypeId)))
                .param("itemTypeId", itemTypeId)
                .params(mergeParams(filterFragment, allFacetFilters))
                .param("limit", effectiveLimit(spec))
                .param("offset", effectiveOffset(spec))
                .query((rs, n) -> new RawItem(
                        rs.getObject("register_item_id", UUID.class),
                        rs.getObject("item_id", UUID.class),
                        rs.getString("item_type"),
                        parseJsonb(rs.getString("properties")),
                        parseJsonb(rs.getString("states"))))
                .list();

        return new ProjectionResult(assembleProjectedItems(rawItems, itemTypeId, binaryBaseUrl), totalCount, facetedCount, facets, stateMachineFacets);
    }

    private long runCount(String tableName, UUID itemTypeId, SqlFragment filter, SqlFragment additionalFilter) {
        return jdbcClient.sql("""
                SELECT COUNT(*)
                FROM register_item ri
                JOIN %s rt ON rt.register_item_id = ri.id
                WHERE ri.item_type_id = :itemTypeId
                  AND ri.state = 'COMMITTED'
                  %s
                  %s
                """.formatted(tableName, filter.sql(), additionalFilter.sql()))
                .param("itemTypeId", itemTypeId)
                .params(mergeParams(filter, additionalFilter))
                .query(Long.class)
                .single();
    }

    private List<FacetBucket> runTermsFacetQuery(String tableName, UUID itemTypeId,
                                                  SqlFragment filter, SqlFragment otherFacetFilters,
                                                  String field) {
        // sanitizeFieldName no longer carries the injection-prevention weight it used to here --
        // resolvePropertyId already rejects anything that isn't a real, known property name for
        // this item type, which subsumes it. Left in as a cheap, redundant first check.
        String propertyId = resolvePropertyId(itemTypeId, sanitizeFieldName(field)).toString();
        return jdbcClient.sql("""
                SELECT rt.properties->>'%s' AS value, COUNT(*) AS count
                FROM register_item ri
                JOIN %s rt ON rt.register_item_id = ri.id
                WHERE ri.item_type_id = :itemTypeId
                  AND ri.state = 'COMMITTED'
                  %s
                  %s
                GROUP BY rt.properties->>'%s'
                ORDER BY count DESC, value ASC NULLS LAST
                """.formatted(propertyId, tableName, filter.sql(), otherFacetFilters.sql(), propertyId))
                .param("itemTypeId", itemTypeId)
                .params(mergeParams(filter, otherFacetFilters))
                .query((rs, n) -> {
                    String value = rs.getString("value");
                    return new FacetBucket(value, value, rs.getLong("count"));
                })
                .list();
    }

    // Unlike runTermsFacetQuery, a bucket's value (a state id) isn't human-readable on its own --
    // FacetBucket.value stays the id (stable, matches what a StateValuePredicate would need to
    // filter back down to this bucket) while .label carries the resolved name for display, finally
    // giving that value/label split real use (property facets today have identical value/label
    // since a property's own value already *is* its display form).
    private List<FacetBucket> runStateMachineFacetQuery(String tableName, UUID itemTypeId, SqlFragment filter, String stateMachineName) {
        UUID stateMachineId = resolveStateMachineId(itemTypeId, stateMachineName);
        Map<UUID, String> stateNames = stateNamesByIdForMachine(stateMachineId);
        return jdbcClient.sql("""
                SELECT (rt.states::jsonb)->'%s'->>'currentStateId' AS value, COUNT(*) AS count
                FROM register_item ri
                JOIN %s rt ON rt.register_item_id = ri.id
                WHERE ri.item_type_id = :itemTypeId
                  AND ri.state = 'COMMITTED'
                  %s
                GROUP BY (rt.states::jsonb)->'%s'->>'currentStateId'
                ORDER BY count DESC, value ASC NULLS LAST
                """.formatted(stateMachineId, tableName, filter.sql(), stateMachineId))
                .param("itemTypeId", itemTypeId)
                .params(filter.params())
                .query((rs, n) -> {
                    String stateId = rs.getString("value");
                    String label = stateId == null ? null : stateNames.get(UUID.fromString(stateId));
                    return new FacetBucket(stateId, label, rs.getLong("count"));
                })
                .list();
    }

    private Map<UUID, String> stateNamesByIdForMachine(UUID stateMachineId) {
        return schemaManager.getAdminSchema().items().stream()
                .flatMap(item -> Optional.ofNullable(item.stateMachines()).orElse(List.of()).stream())
                .filter(m -> m.id().equals(stateMachineId))
                .findFirst()
                .map(m -> m.states().stream().collect(Collectors.toMap(AdminStateView::id, AdminStateView::name)))
                .orElse(Map.of());
    }

    private SqlFragment buildFacetFilterFragment(FacetFilter facetFilter, UUID itemTypeId, AtomicInteger counter) {
        if (facetFilter instanceof TermsFacetFilter f) return buildTermsFacetFilterFragment(f, itemTypeId, counter);
        if (facetFilter instanceof RangeFacetFilter) throw new UnsupportedOperationException("Range facet filters not yet supported");
        if (facetFilter instanceof DateRangeFacetFilter) throw new UnsupportedOperationException("Date range facet filters not yet supported");
        throw new IllegalArgumentException("Unsupported facet filter: " + facetFilter.getClass().getSimpleName());
    }

    private SqlFragment buildTermsFacetFilterFragment(TermsFacetFilter f, UUID itemTypeId, AtomicInteger counter) {
        List<String> values = f.values() != null ? f.values() : List.of();
        boolean includeNull = f.includeNull();

        if (values.isEmpty() && !includeNull) {
            return SqlFragment.empty();
        }

        String propertyId = resolvePropertyId(itemTypeId, sanitizeFieldName(f.field())).toString();
        List<String> clauses = new ArrayList<>();
        Map<String, Object> params = new HashMap<>();

        if (!values.isEmpty()) {
            String paramName = "facet_" + counter.getAndIncrement();
            clauses.add("rt.properties->>'" + propertyId + "' IN (:" + paramName + ")");
            params.put(paramName, values);
        }
        if (includeNull) {
            clauses.add("rt.properties->>'" + propertyId + "' IS NULL");
        }

        String sql = "AND (" + String.join(" OR ", clauses) + ")";
        return new SqlFragment(sql, params);
    }

    private SqlFragment combineFragments(Collection<SqlFragment> fragments) {
        String sql = fragments.stream()
                .map(SqlFragment::sql)
                .filter(s -> !s.isBlank())
                .collect(Collectors.joining(" "));
        Map<String, Object> params = new HashMap<>();
        fragments.forEach(f -> params.putAll(f.params()));
        return new SqlFragment(sql, params);
    }

    @SafeVarargs
    private Map<String, Object> mergeParams(SqlFragment... fragments) {
        Map<String, Object> params = new HashMap<>();
        for (SqlFragment f : fragments) params.putAll(f.params());
        return params;
    }

    public Optional<ProjectedItem> projectOne(UUID itemTypeId, UUID itemId, String binaryBaseUrl) {
        List<RawItem> rawItems = jdbcClient.sql("""
                SELECT ri.id AS register_item_id, ri.item_id, si.name AS item_type, rt.properties::text AS properties, rt.states::text AS states
                FROM register_item ri
                JOIN %s rt ON rt.register_item_id = ri.id
                JOIN schema_item si ON si.id = ri.item_type_id
                WHERE ri.item_type_id = :itemTypeId
                  AND ri.item_id = :itemId
                  AND ri.state = 'COMMITTED'
                """.formatted(tableNameFor(itemTypeId)))
                .param("itemTypeId", itemTypeId)
                .param("itemId", itemId)
                .query((rs, n) -> new RawItem(
                        rs.getObject("register_item_id", UUID.class),
                        rs.getObject("item_id", UUID.class),
                        rs.getString("item_type"),
                        parseJsonb(rs.getString("properties")),
                        parseJsonb(rs.getString("states"))))
                .list();

        if (rawItems.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(assembleProjectedItems(rawItems, itemTypeId, binaryBaseUrl).get(0));
    }

    // --- Write side: staged at prepare (UNCOMMITTED), flipped/cleaned up at commit/abort ---
    // Updates are never in-place: prepare stages a whole new row: commit deletes the old
    // committed row for the same business id and flips the new one to COMMITTED.

    public UUID stageItemCreate(UUID itemId, UUID itemTypeId, Map<UUID, Object> properties, UUID transactionId) {
        return insertItemRow(itemId, itemTypeId, keysToStrings(properties), transactionId);
    }

    public UUID stageItemUpdate(UUID itemId, Map<UUID, Object> propertiesDiff, UUID transactionId) {
        UUID itemTypeId = findItemTypeId(itemId).orElseThrow();

        Map<String, Object> currentProperties = jdbcClient.sql("""
                SELECT rt.properties::text AS properties
                FROM register_item ri
                JOIN %s rt ON rt.register_item_id = ri.id
                WHERE ri.item_id = :itemId AND ri.state = 'COMMITTED'
                """.formatted(tableNameFor(itemTypeId)))
                .param("itemId", itemId)
                .query((rs, n) -> parseJsonb(rs.getString("properties")))
                .single();

        Map<String, Object> merged = mergeProperties(currentProperties, keysToStrings(propertiesDiff));
        return insertItemRow(itemId, itemTypeId, merged, transactionId);
    }

    private UUID insertItemRow(UUID itemId, UUID itemTypeId, Map<String, Object> properties, UUID transactionId) {
        UUID registerItemId = jdbcClient.sql("""
                INSERT INTO register_item (item_id, item_type_id, state, transaction_id)
                VALUES (:itemId, :itemTypeId, 'UNCOMMITTED', :transactionId)
                RETURNING id
                """)
                .param("itemId", itemId)
                .param("itemTypeId", itemTypeId)
                .param("transactionId", transactionId)
                .query(UUID.class)
                .single();

        jdbcClient.sql("INSERT INTO %s (register_item_id, properties) VALUES (:registerItemId, :properties::jsonb)"
                .formatted(tableNameFor(itemTypeId)))
                .param("registerItemId", registerItemId)
                .param("properties", writeProperties(properties))
                .update();

        return registerItemId;
    }

    public Optional<UUID> findItemTypeId(UUID itemId) {
        return jdbcClient.sql("SELECT item_type_id FROM register_item WHERE item_id = :itemId AND state = 'COMMITTED'")
                .param("itemId", itemId)
                .query(UUID.class)
                .optional();
    }

    public void commitItem(UUID itemId, UUID transactionId, UUID commitId) {
        UUID stagedRegisterItemId = jdbcClient.sql("""
                SELECT id FROM register_item WHERE item_id = :itemId AND transaction_id = :transactionId AND state = 'UNCOMMITTED'
                """)
                .param("itemId", itemId)
                .param("transactionId", transactionId)
                .query(UUID.class)
                .single();

        Optional<UUID> oldRegisterItemId = jdbcClient.sql("""
                SELECT id FROM register_item WHERE item_id = :itemId AND state = 'COMMITTED' AND id != :stagedRegisterItemId
                """)
                .param("itemId", itemId)
                .param("stagedRegisterItemId", stagedRegisterItemId)
                .query(UUID.class)
                .optional();

        // An update replaces the row wholesale (new surrogate id), but existing perspective rows
        // still point at the old one -- move them forward before deleting it, or the FK from
        // register_item_link_perspective (no cascade on that side) blocks the delete.
        if (oldRegisterItemId.isPresent()) {
            jdbcClient.sql("UPDATE register_item_link_perspective SET register_item_id = :newId WHERE register_item_id = :oldId")
                    .param("newId", stagedRegisterItemId)
                    .param("oldId", oldRegisterItemId.get())
                    .update();

            jdbcClient.sql("DELETE FROM register_item WHERE id = :oldId")
                    .param("oldId", oldRegisterItemId.get())
                    .update();
        }

        jdbcClient.sql("UPDATE register_item SET state = 'COMMITTED', commit_id = :commitId, updated_at = NOW() WHERE id = :stagedRegisterItemId")
                .param("commitId", commitId)
                .param("stagedRegisterItemId", stagedRegisterItemId)
                .update();
    }

    public void deleteItem(UUID itemId) {
        jdbcClient.sql("DELETE FROM register_item WHERE item_id = :itemId AND state = 'COMMITTED'")
                .param("itemId", itemId)
                .update();
    }

    public UUID stageLinkCreate(UUID linkId, UUID linkTypeId, RegisterLinkEndpoint endpointA, RegisterLinkEndpoint endpointB,
                                 Map<UUID, Object> properties, UUID transactionId) {
        UUID registerLinkId = insertLinkRow(linkId, linkTypeId, keysToStrings(properties), transactionId);
        insertPerspectiveRows(registerLinkId, List.of(endpointA, endpointB), transactionId);
        return registerLinkId;
    }

    public Optional<UUID> findLinkTypeId(UUID linkId) {
        return jdbcClient.sql("SELECT link_definition_id FROM register_link WHERE link_id = :linkId AND state = 'COMMITTED'")
                .param("linkId", linkId)
                .query(UUID.class)
                .optional();
    }

    public UUID stageLinkUpdate(UUID linkId, Map<UUID, Object> propertiesDiff, UUID transactionId) {
        UUID currentRegisterLinkId = jdbcClient.sql("SELECT id FROM register_link WHERE link_id = :linkId AND state = 'COMMITTED'")
                .param("linkId", linkId)
                .query(UUID.class)
                .single();

        UUID linkTypeId = findLinkTypeId(linkId).orElseThrow();

        Map<String, Object> currentProperties = jdbcClient.sql("SELECT properties::text AS properties FROM %s WHERE register_link_id = :id"
                .formatted(linkTableNameFor(linkTypeId)))
                .param("id", currentRegisterLinkId)
                .query((rs, n) -> parseJsonb(rs.getString("properties")))
                .single();

        Map<String, Object> merged = mergeProperties(currentProperties, keysToStrings(propertiesDiff));
        UUID registerLinkId = insertLinkRow(linkId, linkTypeId, merged, transactionId);

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
                .query((rs, n) -> new RegisterLinkEndpoint(
                        rs.getObject("perspective_id", UUID.class),
                        rs.getObject("item_id", UUID.class)))
                .list();
        insertPerspectiveRows(registerLinkId, endpoints, transactionId);

        return registerLinkId;
    }

    private UUID insertLinkRow(UUID linkId, UUID linkTypeId, Map<String, Object> properties, UUID transactionId) {
        UUID registerLinkId = jdbcClient.sql("""
                INSERT INTO register_link (link_id, link_definition_id, state, transaction_id)
                VALUES (:linkId, :linkTypeId, 'UNCOMMITTED', :transactionId)
                RETURNING id
                """)
                .param("linkId", linkId)
                .param("linkTypeId", linkTypeId)
                .param("transactionId", transactionId)
                .query(UUID.class)
                .single();

        jdbcClient.sql("INSERT INTO %s (register_link_id, properties) VALUES (:registerLinkId, :properties::jsonb)"
                .formatted(linkTableNameFor(linkTypeId)))
                .param("registerLinkId", registerLinkId)
                .param("properties", writeProperties(properties))
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
                    .param("perspectiveId", endpoint.perspectiveId())
                    .param("registerItemId", registerItemId)
                    .update();
        }
    }

    public void commitLink(UUID linkId, UUID transactionId, UUID commitId) {
        UUID stagedRegisterLinkId = jdbcClient.sql("""
                SELECT id FROM register_link WHERE link_id = :linkId AND transaction_id = :transactionId AND state = 'UNCOMMITTED'
                """)
                .param("linkId", linkId)
                .param("transactionId", transactionId)
                .query(UUID.class)
                .single();

        jdbcClient.sql("DELETE FROM register_link WHERE link_id = :linkId AND state = 'COMMITTED' AND id != :stagedRegisterLinkId")
                .param("linkId", linkId)
                .param("stagedRegisterLinkId", stagedRegisterLinkId)
                .update();

        jdbcClient.sql("UPDATE register_link SET state = 'COMMITTED', commit_id = :commitId, updated_at = NOW() WHERE id = :stagedRegisterLinkId")
                .param("commitId", commitId)
                .param("stagedRegisterLinkId", stagedRegisterLinkId)
                .update();
    }

    public void deleteLink(UUID linkId) {
        jdbcClient.sql("DELETE FROM register_link WHERE link_id = :linkId AND state = 'COMMITTED'")
                .param("linkId", linkId)
                .update();
    }

    public record RegisterLinkedItem(UUID linkId, UUID connectedItemId) {
    }

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
                .param("itemId", itemId)
                .query((rs, n) -> new RegisterLinkedItem(
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
                .param("itemId", itemId)
                .param("transactionId", transactionId)
                .query(UUID.class)
                .optional();
        if (staged.isPresent()) return staged.get();

        return jdbcClient.sql("SELECT id FROM register_item WHERE item_id = :itemId AND state = 'COMMITTED'")
                .param("itemId", itemId)
                .query(UUID.class)
                .single();
    }

    public void discardStaged(UUID transactionId) {
        jdbcClient.sql("DELETE FROM register_item WHERE transaction_id = :transactionId AND state = 'UNCOMMITTED'")
                .param("transactionId", transactionId)
                .update();
        jdbcClient.sql("DELETE FROM register_link WHERE transaction_id = :transactionId AND state = 'UNCOMMITTED'")
                .param("transactionId", transactionId)
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
    // strings). The read side (propertyNamesByIdForItemType/ForLinkType, used via namesForIds) is
    // the mirror image, resolving back to names only at the point a client-facing response is
    // assembled -- see assembleProjectedItems and fetchPropertiesByRegisterItemId.
    private Map<String, Object> keysToStrings(Map<UUID, Object> propertiesById) {
        Map<String, Object> byIdString = new HashMap<>();
        propertiesById.forEach((propertyId, value) -> byIdString.put(propertyId.toString(), value));
        return byIdString;
    }

    private Map<UUID, String> propertyNamesByIdForItemType(UUID itemTypeId) {
        return schemaManager.getAdminSchema().items().stream()
                .filter(item -> item.id().equals(itemTypeId))
                .findFirst()
                .map(item -> item.properties().stream()
                        .collect(Collectors.toMap(AdminPropertyDefinitionView::id, AdminPropertyDefinitionView::name)))
                .orElse(Map.of());
    }

    private Map<UUID, String> propertyNamesByIdForLinkType(UUID linkTypeId) {
        return schemaManager.getAdminSchema().links().stream()
                .filter(link -> link.id().equals(linkTypeId))
                .findFirst()
                .map(link -> link.properties().stream()
                        .collect(Collectors.toMap(AdminPropertyDefinitionView::id, AdminPropertyDefinitionView::name)))
                .orElse(Map.of());
    }

    // The inverse lookup -- a client-supplied property *name* (in a filter, sort field, or facet
    // field) has to resolve to the id the register actually stores before it can be used in SQL.
    // This also gives predicate/sort/facet validation for free: an unknown name now fails clearly
    // here instead of silently generating a condition that matches nothing.
    private UUID resolvePropertyId(UUID itemTypeId, String propertyName) {
        return schemaManager.getAdminSchema().items().stream()
                .filter(item -> item.id().equals(itemTypeId))
                .findFirst()
                .flatMap(item -> item.properties().stream()
                        .filter(p -> p.name().equals(propertyName))
                        .map(AdminPropertyDefinitionView::id)
                        .findFirst())
                .orElseThrow(() -> new IllegalArgumentException("Unknown property: " + propertyName));
    }

    // Same "translate name to id, never store the name" rule as resolvePropertyId, one level up:
    // a state machine name (like a property name) is mutable, so the states column has to be
    // keyed by the machine's stable id.
    private UUID resolveStateMachineId(UUID itemTypeId, String stateMachineName) {
        return schemaManager.getAdminSchema().items().stream()
                .filter(item -> item.id().equals(itemTypeId))
                .findFirst()
                .flatMap(item -> Optional.ofNullable(item.stateMachines()).orElse(List.of()).stream()
                        .filter(m -> m.name().equals(stateMachineName))
                        .map(AdminStateMachineView::id)
                        .findFirst())
                .orElseThrow(() -> new IllegalArgumentException("Unknown state machine: " + stateMachineName));
    }

    // A state's name is only unique within its own machine (schema_state's UNIQUE constraint is
    // (state_machine_id, name)), so resolution is scoped to a specific machine id, not global.
    private UUID resolveStateId(UUID stateMachineId, String stateName) {
        return schemaManager.getAdminSchema().items().stream()
                .flatMap(item -> Optional.ofNullable(item.stateMachines()).orElse(List.of()).stream())
                .filter(m -> m.id().equals(stateMachineId))
                .findFirst()
                .flatMap(m -> m.states().stream()
                        .filter(s -> s.name().equals(stateName))
                        .map(AdminStateView::id)
                        .findFirst())
                .orElseThrow(() -> new IllegalArgumentException("Unknown state: " + stateName));
    }

    // Minimal, direct register write -- deliberately bypasses the ledger (states isn't ledger-backed)
    // and any guard/process execution. Exists solely so current-state querying/faceting (below,
    // and the facet query in runTermsFacetQuery's sibling) has real data to run against before
    // real transition execution is built; a proper "perform transition" mutation replaces this.
    public void setItemState(UUID itemId, String stateMachineName, String stateName) {
        UUID itemTypeId = findItemTypeId(itemId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown item: " + itemId));
        UUID stateMachineId = resolveStateMachineId(itemTypeId, stateMachineName);
        UUID stateId = resolveStateId(stateMachineId, stateName);
        String tableName = tableNameFor(itemTypeId);
        jdbcClient.sql("""
                UPDATE %s SET states = jsonb_set(coalesce(states, '{}'::jsonb), ARRAY[:machineId], (:stateJson)::jsonb, true)
                WHERE register_item_id = (SELECT id FROM register_item WHERE item_id = :itemId AND state = 'COMMITTED')
                """.formatted(tableName))
                .param("machineId", stateMachineId.toString())
                .param("stateJson", "{\"currentStateId\": \"" + stateId + "\"}")
                .param("itemId", itemId)
                .update();
    }

    // Converts an id-keyed properties map (as read straight from the register's JSONB) into the
    // name-keyed map a client actually wants to see. A key that no longer resolves (the property
    // was deleted from the schema after this row was written) is silently dropped rather than
    // surfacing a raw id the client has no way to interpret.
    private Map<String, Object> namesForIds(Map<String, Object> propertiesById, Map<UUID, String> idToName) {
        Map<String, Object> byName = new HashMap<>();
        propertiesById.forEach((idString, value) -> {
            String name = idToName.get(UUID.fromString(idString));
            if (name != null) byName.put(name, value);
        });
        return byName;
    }

    private record LinkRow(UUID myRegisterItemId, String perspectiveName,
                           UUID registerLinkId, UUID linkId, UUID linkDefinitionId,
                           UUID linkedRegisterItemId, UUID linkedItemId, UUID linkedItemTypeId, String linkedItemType) {}

    private record BinaryPropertyRow(UUID registerItemId, String propertyName, UUID binaryId) {}

    private List<ProjectedItem> assembleProjectedItems(List<RawItem> rawItems, UUID itemTypeId, String binaryBaseUrl) {
        Map<UUID, String> ownPropertyNames = propertyNamesByIdForItemType(itemTypeId);
        List<AdminStateMachineView> stateMachines = schemaManager.getAdminSchema().items().stream()
                .filter(item -> item.id().equals(itemTypeId))
                .findFirst()
                .map(AdminItemDefinitionView::stateMachines)
                .orElse(null);
        List<UUID> rawItemIds = rawItems.stream().map(RawItem::registerItemId).toList();

        List<LinkRow> linkRows = jdbcClient.sql("""
                SELECT
                    rilp_mine.register_item_id  AS my_register_item_id,
                    silp_mine.name              AS perspective_name,
                    rl.id                       AS register_link_id,
                    rl.link_id                  AS link_id,
                    rl.link_definition_id       AS link_definition_id,
                    ri_other.id                 AS linked_register_item_id,
                    ri_other.item_id            AS linked_item_id,
                    ri_other.item_type_id       AS linked_item_type_id,
                    si_other.name               AS linked_item_type
                FROM register_item ri_mine
                JOIN register_item_link_perspective rilp_mine  ON rilp_mine.register_item_id = ri_mine.id
                JOIN schema_entity_link_perspective silp_mine  ON silp_mine.id = rilp_mine.perspective_id
                JOIN register_link                 rl          ON rl.id = rilp_mine.register_link_id AND rl.state = 'COMMITTED'
                JOIN register_item_link_perspective rilp_other ON rilp_other.register_link_id = rl.id AND rilp_other.id != rilp_mine.id
                JOIN register_item                 ri_other    ON ri_other.id = rilp_other.register_item_id
                JOIN schema_item                   si_other    ON si_other.id = ri_other.item_type_id
                WHERE ri_mine.id IN (:rawItemIds)
                  AND ri_mine.state = 'COMMITTED'
                """)
                .param("rawItemIds", rawItemIds)
                .query((rs, n) -> new LinkRow(
                        rs.getObject("my_register_item_id", UUID.class),
                        rs.getString("perspective_name"),
                        rs.getObject("register_link_id", UUID.class),
                        rs.getObject("link_id", UUID.class),
                        rs.getObject("link_definition_id", UUID.class),
                        rs.getObject("linked_register_item_id", UUID.class),
                        rs.getObject("linked_item_id", UUID.class),
                        rs.getObject("linked_item_type_id", UUID.class),
                        rs.getString("linked_item_type")))
                .list();

        Map<UUID, Map<String, Object>> linkedItemProperties = fetchPropertiesByRegisterItemId(
                linkRows.stream().collect(Collectors.groupingBy(
                        LinkRow::linkedItemTypeId,
                        Collectors.mapping(LinkRow::linkedRegisterItemId, Collectors.toList()))),
                "register_item_id");

        Map<UUID, Map<String, Object>> linkProperties = fetchPropertiesByRegisterItemId(
                linkRows.stream().collect(Collectors.groupingBy(
                        LinkRow::linkDefinitionId,
                        Collectors.mapping(LinkRow::registerLinkId, Collectors.toList()))),
                "register_link_id");

        Map<UUID, Map<String, List<ProjectedLink>>> linksByItem = linkRows.stream()
                .collect(Collectors.groupingBy(
                        LinkRow::myRegisterItemId,
                        Collectors.groupingBy(
                                LinkRow::perspectiveName,
                                Collectors.mapping(row -> new ProjectedLink(
                                        row.linkId(),
                                        linkProperties.getOrDefault(row.registerLinkId(), Map.of()),
                                        // states is left null for a linked item -- a projection's link expansion has
                                        // never fetched a linked item's full state (matching binary properties'
                                        // own scope, which the linked item also doesn't get); this is about the
                                        // top-level projected item's state, not every item reachable through it.
                                        new ProjectedItem(
                                                row.linkedItemId(),
                                                row.linkedItemType(),
                                                linkedItemProperties.getOrDefault(row.linkedRegisterItemId(), Map.of()),
                                                Map.of(),
                                                null,
                                                null)),
                                        Collectors.toList()))));

        List<BinaryPropertyRow> binaryRows = jdbcClient.sql("""
                SELECT rbp.register_item_id, sp.name AS property_name, rbp.binary_id
                FROM register_binary_property rbp
                JOIN schema_property sp ON sp.id = rbp.property_id
                WHERE rbp.register_item_id IN (:ids)
                """)
                .param("ids", rawItemIds)
                .query((rs, n) -> new BinaryPropertyRow(
                        rs.getObject("register_item_id", UUID.class),
                        rs.getString("property_name"),
                        rs.getObject("binary_id", UUID.class)))
                .list();

        Set<UUID> binaryIds = binaryRows.stream().map(BinaryPropertyRow::binaryId).collect(Collectors.toSet());
        Map<UUID, BinaryPropertyObject> binaryObjects = binaryPartitionManager.getBinaryProperties(binaryIds);

        Map<UUID, Map<String, Object>> binaryPropsByItem = new HashMap<>();
        for (var row : binaryRows) {
            BinaryPropertyObject obj = binaryObjects.get(row.binaryId());
            if (obj == null) continue;
            binaryPropsByItem
                    .computeIfAbsent(row.registerItemId(), k -> new HashMap<>())
                    .put(row.propertyName(), assembleBinaryValue(obj, binaryBaseUrl));
        }

        return rawItems.stream()
                .map(raw -> {
                    Map<String, Object> props = new HashMap<>(namesForIds(raw.properties(), ownPropertyNames));
                    Map<String, Object> binProps = binaryPropsByItem.get(raw.registerItemId());
                    if (binProps != null) props.putAll(binProps);
                    return new ProjectedItem(
                            raw.itemId(),
                            raw.itemType(),
                            props,
                            linksByItem.getOrDefault(raw.registerItemId(), Map.of()),
                            buildProjectedItemStates(raw.states(), stateMachines),
                            null);
                })
                .toList();
    }

    // raw.states() is the register's id-keyed storage (machine id -> {currentStateId: ...});
    // resolves both keys back to names for the client, same "id internally, name at the boundary"
    // rule as namesForIds. Returns null (not an empty map) when the item type has no state
    // machines at all, or the item has no recorded state on any of them -- the common case, and
    // ProjectedItem.states' own doc comment says why null instead of {}.
    private Map<String, ProjectedItemState> buildProjectedItemStates(Map<String, Object> statesById, List<AdminStateMachineView> stateMachines) {
        if (stateMachines == null || stateMachines.isEmpty() || statesById == null || statesById.isEmpty()) {
            return null;
        }
        Map<String, ProjectedItemState> result = new LinkedHashMap<>();
        for (AdminStateMachineView machine : stateMachines) {
            if (!(statesById.get(machine.id().toString()) instanceof Map<?, ?> stateEntry)) continue;
            Object currentStateId = stateEntry.get("currentStateId");
            if (currentStateId == null) continue;
            machine.states().stream()
                    .filter(s -> s.id().toString().equals(currentStateId.toString()))
                    .map(AdminStateView::name)
                    .findFirst()
                    // A dangling id (the state was deleted from the schema after this row was
                    // written) is silently dropped, same as namesForIds does for properties.
                    .ifPresent(currentStateName -> result.put(machine.name(), new ProjectedItemState(currentStateName, null)));
        }
        return result.isEmpty() ? null : result;
    }

    private Map<String, Object> assembleBinaryValue(BinaryPropertyObject obj, String binaryBaseUrl) {
        Map<String, Object> value = new HashMap<>();
        value.put("id", obj.id().toString());
        value.put("sha256", obj.sha256());
        value.put("md5", obj.md5());
        value.put("mimeType", obj.mimeType());
        value.put("length", obj.length());
        value.put("url", binaryBaseUrl + "/api/binary/" + obj.id());
        if (obj.metadata() != null) value.put("metadata", obj.metadata());
        return value;
    }

    private record SqlFragment(String sql, Map<String, Object> params) {
        static SqlFragment empty() { return new SqlFragment("", Map.of()); }
    }

    private SqlFragment buildPredicateFragment(Predicate predicate, UUID itemTypeId) {
        if (predicate == null) return SqlFragment.empty();
        var translated = translatePredicate(predicate, itemTypeId, new AtomicInteger());
        return new SqlFragment("AND " + translated.sql(), translated.params());
    }

    private SqlFragment translatePredicate(Predicate predicate, UUID itemTypeId, AtomicInteger counter) {
        if (predicate instanceof AndPredicate and) {
            List<SqlFragment> children = and.predicates().stream()
                    .map(p -> translatePredicate(p, itemTypeId, counter))
                    .toList();
            String sql = children.stream()
                    .map(SqlFragment::sql)
                    .collect(Collectors.joining(" AND ", "(", ")"));
            Map<String, Object> params = new HashMap<>();
            children.forEach(c -> params.putAll(c.params()));
            return new SqlFragment(sql, params);
        } else if (predicate instanceof OrPredicate or) {
            List<SqlFragment> children = or.predicates().stream()
                    .map(p -> translatePredicate(p, itemTypeId, counter))
                    .toList();
            String sql = children.stream()
                    .map(SqlFragment::sql)
                    .collect(Collectors.joining(" OR ", "(", ")"));
            Map<String, Object> params = new HashMap<>();
            children.forEach(c -> params.putAll(c.params()));
            return new SqlFragment(sql, params);
        } else if (predicate instanceof NotPredicate not) {
            var child = translatePredicate(not.predicate(), itemTypeId, counter);
            return new SqlFragment("NOT (" + child.sql() + ")", child.params());
        } else if (predicate instanceof PropertyExistencePredicate p) {
            return new SqlFragment("jsonb_exists(rt.properties::jsonb, '" + resolvePropertyId(itemTypeId, p.propertyName()) + "')", Map.of());
        } else if (predicate instanceof PropertyValuePredicate p) {
            String paramName = "pred_" + counter.getAndIncrement();
            String sqlOp = switch (p.operator()) {
                case EQUALS              -> "=";
                case NOT_EQUALS          -> "!=";
                case LESS_THAN           -> "<";
                case LESS_THAN_OR_EQUAL  -> "<=";
                case GREATER_THAN        -> ">";
                case GREATER_THAN_OR_EQUAL -> ">=";
                case LIKE                -> "ILIKE";
            };
            return new SqlFragment(
                    "(rt.properties::jsonb)->>'" + resolvePropertyId(itemTypeId, p.propertyName()) + "' " + sqlOp + " :" + paramName,
                    Map.of(paramName, p.value()));
        } else if (predicate instanceof StateValuePredicate p) {
            UUID stateMachineId = resolveStateMachineId(itemTypeId, p.stateMachineName());
            UUID stateId = resolveStateId(stateMachineId, p.stateName());
            return new SqlFragment(
                    "(rt.states::jsonb)->'" + stateMachineId + "'->>'currentStateId' = '" + stateId + "'", Map.of());
        }
        throw new IllegalArgumentException("Unsupported predicate: " + predicate.getClass().getSimpleName());
    }

    private Map<UUID, Map<String, Object>> fetchPropertiesByRegisterItemId(
            Map<UUID, List<UUID>> typeOrDefIdToRegisterIds, String idColumn) {
        boolean isLinkTable = idColumn.equals("register_link_id");
        Map<UUID, Map<String, Object>> result = new HashMap<>();
        for (var entry : typeOrDefIdToRegisterIds.entrySet()) {
            UUID typeOrDefId = entry.getKey();
            String table = isLinkTable ? linkTableNameFor(typeOrDefId) : tableNameFor(typeOrDefId);
            Map<UUID, String> idToName = isLinkTable
                    ? propertyNamesByIdForLinkType(typeOrDefId)
                    : propertyNamesByIdForItemType(typeOrDefId);
            jdbcClient.sql("SELECT " + idColumn + ", properties::text FROM " + table + " WHERE " + idColumn + " IN (:ids)")
                    .param("ids", entry.getValue())
                    .query((rs, n) -> Map.entry(
                            rs.getObject(idColumn, UUID.class),
                            namesForIds(parseJsonb(rs.getString("properties")), idToName)))
                    .list()
                    .forEach(e -> result.put(e.getKey(), e.getValue()));
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseJsonb(String json) {
        if (json == null) return Map.of();
        try {
            return objectMapper.readValue(json, Map.class);
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse properties JSONB", e);
        }
    }

    public static String tableNameFor(UUID typeId) {
        return "register_item_" + typeId.toString().replace("-", "_");
    }

    public static String linkTableNameFor(UUID linkTypeId) {
        return "register_link_" + linkTypeId.toString().replace("-", "_");
    }

    // --- Schema change reactions ---
    //
    // Each item/link type gets its own physical properties table (see tableNameFor/
    // linkTableNameFor), so that table has to be created the moment the type is defined and
    // dropped the moment it's deleted -- otherwise projection queries against a type created
    // after boot fail with "relation does not exist", since RegisterInitializer only creates
    // these tables once, at startup, for whatever types already existed then. Traits have no
    // register-side table of their own (their properties live in the owning item type's JSONB
    // blob), so trait events are intentionally ignored here.

    @Override
    @EventListener
    public void onSchemaChange(SchemaChangeEvent event) {
        if (event instanceof SchemaChangeEvent.ItemTypeCreated e) {
            createItemTypeTable(e.itemTypeId());
        } else if (event instanceof SchemaChangeEvent.ItemTypeDeleted e) {
            dropItemTypeTable(e.itemTypeId());
        } else if (event instanceof SchemaChangeEvent.LinkTypeCreated e) {
            createLinkTypeTable(e.linkTypeId());
        } else if (event instanceof SchemaChangeEvent.LinkTypeDeleted e) {
            dropLinkTypeTable(e.linkTypeId());
        }
    }

    public void createItemTypeTable(UUID itemTypeId) {
        String tableName = tableNameFor(itemTypeId);
        // states is keyed by state-machine id (stringified), mirroring properties' own id-keying --
        // see keysToStrings/propertyNamesByIdForItemType's comment. Each value is a small object
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
