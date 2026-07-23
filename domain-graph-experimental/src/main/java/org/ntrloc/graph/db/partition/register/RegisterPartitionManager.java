package org.ntrloc.graph.db.partition.register;

import tools.jackson.databind.ObjectMapper;
import org.ntrloc.graph.db.partition.binary.BinaryPartitionManager;
import org.ntrloc.graph.db.partition.binary.BinaryPropertyObject;
import org.ntrloc.graph.db.projection.AndPredicate;
import org.ntrloc.graph.db.projection.CollectionProjectionSpec;
import org.ntrloc.graph.db.projection.FacetBucket;
import org.ntrloc.graph.db.projection.FacetFilter;
import org.ntrloc.graph.db.projection.Predicate;
import org.ntrloc.graph.db.partition.schema.SchemaManager;
import org.ntrloc.graph.db.partition.schema.definition.PropertyCardinality;
import org.ntrloc.graph.db.partition.schema.definition.PropertyType;
import org.ntrloc.graph.db.partition.schema.definition.view.admin.AdminPropertyDefinitionView;
import org.ntrloc.graph.db.partition.schema.event.SchemaChangeEvent;
import org.ntrloc.graph.db.partition.schema.event.SchemaChangeListener;
import org.springframework.context.event.EventListener;
import org.ntrloc.graph.db.projection.ProjectedItem;
import org.ntrloc.graph.db.projection.ProjectedLink;
import org.ntrloc.graph.db.projection.OrPredicate;
import org.ntrloc.graph.db.projection.ProjectionResult;
import org.ntrloc.graph.db.projection.PropertyExistencePredicate;
import org.ntrloc.graph.db.projection.PropertyValuePredicate;
import org.ntrloc.graph.db.projection.RangeFacetFilter;
import org.ntrloc.graph.db.projection.DateRangeFacetFilter;
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

    private String orderByClause(CollectionProjectionSpec spec) {
        if (spec.sortField() == null || spec.sortField().isBlank()) return "ORDER BY ri.item_id ASC";
        String direction = "DESC".equalsIgnoreCase(spec.sortDirection()) ? "DESC" : "ASC";
        String column = SYSTEM_SORT_COLUMNS.containsKey(spec.sortField())
                ? SYSTEM_SORT_COLUMNS.get(spec.sortField())
                : "rt.properties->>'" + spec.sortField() + "'";
        return "ORDER BY " + column + " " + direction + " NULLS LAST, ri.item_id ASC";
    }

    private record RawItem(UUID registerItemId, UUID itemId, String itemType, Map<String, Object> properties) {}

    public ProjectionResult project(UUID itemTypeId, CollectionProjectionSpec spec, String binaryBaseUrl) {
        String tableName = tableNameFor(itemTypeId);
        SqlFragment filterFragment = buildPredicateFragment(spec.filter());

        List<FacetFilter> activeFacetFilters = spec.facetFilters() != null ? spec.facetFilters() : List.of();
        List<String> requestedFacets = spec.facets() == null ? List.of()
                : spec.facets().isEmpty() ? facetableFieldsFor(itemTypeId)
                : spec.facets();

        // Build one SQL fragment per active facet filter, keyed by field
        AtomicInteger facetParamCounter = new AtomicInteger();
        Map<String, SqlFragment> facetFragmentsByField = new LinkedHashMap<>();
        for (FacetFilter ff : activeFacetFilters) {
            facetFragmentsByField.put(ff.field(), buildFacetFilterFragment(ff, facetParamCounter));
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

        if (facetedCount == 0) {
            return new ProjectionResult(List.of(), totalCount, 0, facets);
        }

        List<RawItem> rawItems = jdbcClient.sql("""
                SELECT ri.id AS register_item_id, ri.item_id, si.name AS item_type, rt.properties::text AS properties
                FROM register_item ri
                JOIN %s rt ON rt.register_item_id = ri.id
                JOIN schema_item si ON si.id = ri.item_type_id
                WHERE ri.item_type_id = :itemTypeId
                  AND ri.state = 'COMMITTED'
                  %s
                  %s
                %s
                """.formatted(tableName, filterFragment.sql(), allFacetFilters.sql(), orderByClause(spec)))
                .param("itemTypeId", itemTypeId)
                .params(mergeParams(filterFragment, allFacetFilters))
                .query((rs, n) -> new RawItem(
                        rs.getObject("register_item_id", UUID.class),
                        rs.getObject("item_id", UUID.class),
                        rs.getString("item_type"),
                        parseJsonb(rs.getString("properties"))))
                .list();

        return new ProjectionResult(assembleProjectedItems(rawItems, binaryBaseUrl), totalCount, facetedCount, facets);
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
        String safeField = sanitizeFieldName(field);
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
                """.formatted(safeField, tableName, filter.sql(), otherFacetFilters.sql(), safeField))
                .param("itemTypeId", itemTypeId)
                .params(mergeParams(filter, otherFacetFilters))
                .query((rs, n) -> {
                    String value = rs.getString("value");
                    return new FacetBucket(value, value, rs.getLong("count"));
                })
                .list();
    }

    private SqlFragment buildFacetFilterFragment(FacetFilter facetFilter, AtomicInteger counter) {
        return switch (facetFilter) {
            case TermsFacetFilter f    -> buildTermsFacetFilterFragment(f, counter);
            case RangeFacetFilter f    -> throw new UnsupportedOperationException("Range facet filters not yet supported");
            case DateRangeFacetFilter f -> throw new UnsupportedOperationException("Date range facet filters not yet supported");
        };
    }

    private SqlFragment buildTermsFacetFilterFragment(TermsFacetFilter f, AtomicInteger counter) {
        List<String> values = f.values() != null ? f.values() : List.of();
        boolean includeNull = f.includeNull();

        if (values.isEmpty() && !includeNull) {
            return SqlFragment.empty();
        }

        String field = sanitizeFieldName(f.field());
        List<String> clauses = new ArrayList<>();
        Map<String, Object> params = new HashMap<>();

        if (!values.isEmpty()) {
            String paramName = "facet_" + counter.getAndIncrement();
            clauses.add("rt.properties->>'" + field + "' IN (:" + paramName + ")");
            params.put(paramName, values);
        }
        if (includeNull) {
            clauses.add("rt.properties->>'" + field + "' IS NULL");
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
                SELECT ri.id AS register_item_id, ri.item_id, si.name AS item_type, rt.properties::text AS properties
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
                        parseJsonb(rs.getString("properties"))))
                .list();

        if (rawItems.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(assembleProjectedItems(rawItems, binaryBaseUrl).getFirst());
    }

    // --- Write side: staged at prepare (UNCOMMITTED), flipped/cleaned up at commit/abort ---
    // Updates are never in-place: prepare stages a whole new row: commit deletes the old
    // committed row for the same business id and flips the new one to COMMITTED.

    public UUID stageItemCreate(UUID itemId, UUID itemTypeId, Map<UUID, Object> properties, UUID transactionId) {
        return insertItemRow(itemId, itemTypeId, resolveItemPropertyNames(itemTypeId, properties), transactionId);
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

        Map<String, Object> merged = mergeProperties(currentProperties, resolveItemPropertyNames(itemTypeId, propertiesDiff));
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
        UUID registerLinkId = insertLinkRow(linkId, linkTypeId, resolveLinkPropertyNames(linkTypeId, properties), transactionId);
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

        Map<String, Object> merged = mergeProperties(currentProperties, resolveLinkPropertyNames(linkTypeId, propertiesDiff));
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

    // Translates property-id-keyed input (what LedgerEntry carries) into the name-keyed map the
    // JSONB columns actually store -- property ids are stable, names are mutable (renameable via
    // UpdatePropertyDefinitionMutation), so this is where that gap gets bridged.
    private Map<String, Object> resolveItemPropertyNames(UUID itemTypeId, Map<UUID, Object> propertiesById) {
        Map<UUID, String> idToName = schemaManager.getAdminSchema().items().stream()
                .filter(item -> item.id().equals(itemTypeId))
                .findFirst()
                .map(item -> item.properties().stream()
                        .collect(Collectors.toMap(AdminPropertyDefinitionView::id, AdminPropertyDefinitionView::name)))
                .orElse(Map.of());
        return resolvePropertyNames(propertiesById, idToName);
    }

    private Map<String, Object> resolveLinkPropertyNames(UUID linkTypeId, Map<UUID, Object> propertiesById) {
        Map<UUID, String> idToName = schemaManager.getAdminSchema().links().stream()
                .filter(link -> link.id().equals(linkTypeId))
                .findFirst()
                .map(link -> link.properties().stream()
                        .collect(Collectors.toMap(AdminPropertyDefinitionView::id, AdminPropertyDefinitionView::name)))
                .orElse(Map.of());
        return resolvePropertyNames(propertiesById, idToName);
    }

    private Map<String, Object> resolvePropertyNames(Map<UUID, Object> propertiesById, Map<UUID, String> idToName) {
        Map<String, Object> byName = new HashMap<>();
        propertiesById.forEach((propertyId, value) -> {
            String name = idToName.get(propertyId);
            if (name == null) {
                throw new IllegalArgumentException("Unknown property id: " + propertyId);
            }
            byName.put(name, value);
        });
        return byName;
    }

    private record LinkRow(UUID myRegisterItemId, String perspectiveName,
                           UUID registerLinkId, UUID linkId, UUID linkDefinitionId,
                           UUID linkedRegisterItemId, UUID linkedItemId, UUID linkedItemTypeId, String linkedItemType) {}

    private record BinaryPropertyRow(UUID registerItemId, String propertyName, UUID binaryId) {}

    private List<ProjectedItem> assembleProjectedItems(List<RawItem> rawItems, String binaryBaseUrl) {
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
                                        new ProjectedItem(
                                                row.linkedItemId(),
                                                row.linkedItemType(),
                                                linkedItemProperties.getOrDefault(row.linkedRegisterItemId(), Map.of()),
                                                Map.of())),
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
                    Map<String, Object> props = new HashMap<>(raw.properties());
                    Map<String, Object> binProps = binaryPropsByItem.get(raw.registerItemId());
                    if (binProps != null) props.putAll(binProps);
                    return new ProjectedItem(
                            raw.itemId(),
                            raw.itemType(),
                            props,
                            linksByItem.getOrDefault(raw.registerItemId(), Map.of()));
                })
                .toList();
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

    private SqlFragment buildPredicateFragment(Predicate predicate) {
        if (predicate == null) return SqlFragment.empty();
        var translated = translatePredicate(predicate, new AtomicInteger());
        return new SqlFragment("AND " + translated.sql(), translated.params());
    }

    private SqlFragment translatePredicate(Predicate predicate, AtomicInteger counter) {
        return switch (predicate) {
            case AndPredicate and -> {
                List<SqlFragment> children = and.predicates().stream()
                        .map(p -> translatePredicate(p, counter))
                        .toList();
                String sql = children.stream()
                        .map(SqlFragment::sql)
                        .collect(Collectors.joining(" AND ", "(", ")"));
                Map<String, Object> params = new HashMap<>();
                children.forEach(c -> params.putAll(c.params()));
                yield new SqlFragment(sql, params);
            }
            case OrPredicate or -> {
                List<SqlFragment> children = or.predicates().stream()
                        .map(p -> translatePredicate(p, counter))
                        .toList();
                String sql = children.stream()
                        .map(SqlFragment::sql)
                        .collect(Collectors.joining(" OR ", "(", ")"));
                Map<String, Object> params = new HashMap<>();
                children.forEach(c -> params.putAll(c.params()));
                yield new SqlFragment(sql, params);
            }
            case PropertyExistencePredicate p ->
                new SqlFragment("jsonb_exists(rt.properties::jsonb, '" + p.propertyName() + "')", Map.of());
            case PropertyValuePredicate p -> {
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
                yield new SqlFragment(
                        "(rt.properties::jsonb)->>'" + p.propertyName() + "' " + sqlOp + " :" + paramName,
                        Map.of(paramName, p.value()));
            }
        };
    }

    private Map<UUID, Map<String, Object>> fetchPropertiesByRegisterItemId(
            Map<UUID, List<UUID>> typeOrDefIdToRegisterIds, String idColumn) {
        Map<UUID, Map<String, Object>> result = new HashMap<>();
        for (var entry : typeOrDefIdToRegisterIds.entrySet()) {
            String table = idColumn.equals("register_link_id")
                    ? linkTableNameFor(entry.getKey())
                    : tableNameFor(entry.getKey());
            jdbcClient.sql("SELECT " + idColumn + ", properties::text FROM " + table + " WHERE " + idColumn + " IN (:ids)")
                    .param("ids", entry.getValue())
                    .query((rs, n) -> Map.entry(
                            rs.getObject(idColumn, UUID.class),
                            parseJsonb(rs.getString("properties"))))
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
        switch (event) {
            case SchemaChangeEvent.ItemTypeCreated e -> createItemTypeTable(e.itemTypeId());
            case SchemaChangeEvent.ItemTypeDeleted e -> dropItemTypeTable(e.itemTypeId());
            case SchemaChangeEvent.LinkTypeCreated e -> createLinkTypeTable(e.linkTypeId());
            case SchemaChangeEvent.LinkTypeDeleted e -> dropLinkTypeTable(e.linkTypeId());
            case SchemaChangeEvent.TraitCreated ignored -> {}
            case SchemaChangeEvent.TraitDeleted ignored -> {}
        }
    }

    public void createItemTypeTable(UUID itemTypeId) {
        String tableName = tableNameFor(itemTypeId);
        jdbcClient.sql("""
                CREATE TABLE %s (
                    register_item_id UUID PRIMARY KEY REFERENCES register_item(id) ON DELETE CASCADE,
                    properties       JSONB
                )
                """.formatted(tableName)).update();
        jdbcClient.sql("CREATE INDEX ON %s USING GIN (properties)".formatted(tableName)).update();
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
