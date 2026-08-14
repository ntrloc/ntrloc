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
import org.ntrloc.graph.db.partition.schema.definition.view.admin.AdminSchemaView;
import org.ntrloc.graph.db.partition.schema.definition.view.admin.AdminStateMachineView;
import org.ntrloc.graph.db.partition.schema.definition.view.admin.AdminStateView;
import org.ntrloc.graph.db.partition.schema.definition.view.admin.ObjectAdminPropertyDefinitionView;
import org.ntrloc.graph.db.partition.schema.event.SchemaChangeEvent;
import org.ntrloc.graph.db.partition.schema.event.SchemaChangeListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.context.expression.MapAccessor;
import org.springframework.expression.Expression;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.SimpleEvaluationContext;
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
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Component
public class RegisterPartitionManager implements SchemaChangeListener {

    private static final Logger LOG = LoggerFactory.getLogger(RegisterPartitionManager.class);

    private final JdbcClient jdbcClient;
    private final ObjectMapper objectMapper;
    private final BinaryPartitionManager binaryPartitionManager;
    private final SchemaManager schemaManager;

    // Keyed by pattern *string*, not item type -- a pure function cache (the same SpEL text always
    // compiles to the same Expression), so it needs no invalidation hook when a pattern is edited;
    // an edited pattern is simply a different string, a new cache entry. Unbounded is fine: the
    // realistic number of distinct patterns ever authored across a schema's lifetime is small.
    private final Map<String, Expression> compiledPatternCache = new ConcurrentHashMap<>();
    private final SpelExpressionParser spelParser = new SpelExpressionParser();

    // Memoizes resolveEffectiveDisplayLabelPatterns() by reference-identity of the last-seen
    // AdminSchemaView -- schemaManager.getAdminSchema() only ever returns a new instance when
    // rebuildCache() runs (on any schema mutation), so a normal run of project()/projectOne()/
    // projectAcrossTypes() (which each call assembleProjectedItems, and thus this resolution,
    // once) is a cache hit rather than an O(item types) supertype-chain walk every time.
    // schema and resolved are updated together as a pair, so they're held in one AtomicReference
    // rather than two volatile fields -- otherwise a racing reader could see the new schema but
    // the stale resolved map (or vice versa).
    private record DisplayLabelPatternsCache(AdminSchemaView schema, Map<String, String> resolved) {
    }

    private final AtomicReference<DisplayLabelPatternsCache> displayLabelPatternsCache =
            new AtomicReference<>(new DisplayLabelPatternsCache(null, Map.of()));

    public RegisterPartitionManager(JdbcClient jdbcClient, ObjectMapper objectMapper,
                                    BinaryPartitionManager binaryPartitionManager, SchemaManager schemaManager) {
        this.jdbcClient = jdbcClient;
        this.objectMapper = objectMapper;
        this.binaryPartitionManager = binaryPartitionManager;
        this.schemaManager = schemaManager;
    }

    private static final String PARAM_ITEM_ID = "itemId";
    private static final String PROPERTIES_JSON_ARROW_PREFIX = "rt.properties->>'";
    private static final String PARAM_ITEM_TYPE_ID = "itemTypeId";
    private static final String COL_REGISTER_ITEM_ID = "register_item_id";
    private static final String COL_ITEM_ID = "item_id";
    private static final String COL_PROPERTIES = "properties";
    private static final String COL_ITEM_TYPE = "item_type";
    private static final String COL_STATES = "states";
    private static final String COL_FACET_VALUE = "value";
    private static final String COL_FACET_COUNT = "count";
    private static final String PARAM_TRANSACTION_ID = "transactionId";
    private static final String PARAM_LINK_ID = "linkId";
    private static final String COL_REGISTER_LINK_ID = "register_link_id";

    private static final Map<String, String> SYSTEM_SORT_COLUMNS = Map.of(
            PARAM_ITEM_ID,          "ri.item_id",
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
                .map(item -> collectFacetableFieldNames(item.properties(), ""))
                .orElse(List.of());
    }

    // Recurses into OBJECT properties so a nested facetable property (e.g. "additionalDetails.
    // reviewStatus") is auto-discovered by facets: [] the same as a top-level one, using the same
    // dot-path naming resolvePropertyId already understands for explicit facet requests -- this is
    // the only piece that was missing; querying a dot-path facet has worked since dot-notation was
    // added. An OBJECT property is itself never facetable (isTermsFacetable always rejects it, no
    // controlled list/BOOLEAN type applies), so it's only ever a container to recurse through here,
    // never added to the result itself. No cardinality guard needed on the recursion: OBJECT's own
    // valid cardinalities are SINGLE-only (PropertyType.validCardinalities), so a repeating group of
    // nested objects -- which would make a child's value multi-valued per item, the same ambiguity
    // LIST cardinality already rules out for a scalar -- can't occur here.
    private List<String> collectFacetableFieldNames(List<AdminPropertyDefinitionView> properties, String pathPrefix) {
        List<String> names = new ArrayList<>();
        for (AdminPropertyDefinitionView p : properties) {
            String qualifiedName = pathPrefix.isEmpty() ? p.name() : pathPrefix + "." + p.name();
            if (isTermsFacetable(p)) {
                names.add(qualifiedName);
            } else if (p instanceof ObjectAdminPropertyDefinitionView object) {
                names.addAll(collectFacetableFieldNames(object.properties(), qualifiedName));
            }
        }
        return names;
    }

    private List<String> resolveRequestedFacets(CollectionProjectionSpec spec, UUID itemTypeId) {
        if (spec.facets() == null) return List.of();
        if (spec.facets().isEmpty()) return facetableFieldsFor(itemTypeId);
        return spec.facets();
    }

    // Facetable is an explicit admin declaration (schema_property.facetable), not inferred purely
    // from shape -- see SchemaMutationValidation.requireFacetableEligible's own comment on why.
    // The structural check stays here too, not just at mutation time: it's what keeps an admin's
    // "facetable" flag from ever being treated as usable before a controlled-list-backed
    // property's list is actually attached (a real, normal sequencing gap in the admin workflow --
    // attaching a list requires the property to already have an id), rather than rejecting that
    // sequencing outright the way mutation-time validation does for combinations that could never
    // be valid. Not restricted to STRING -- any type a controlled list can back (see the admin
    // UI's own CONTROLLED_LIST_TYPES) is eligible once one is actually attached; BOOLEAN is the
    // one type that's facetable without a controlled list at all, since true/false is already a
    // closed set.
    private boolean isTermsFacetable(AdminPropertyDefinitionView p) {
        if (!p.facetable() || p.cardinality() != PropertyCardinality.SINGLE) return false;
        return p.type() == PropertyType.BOOLEAN || p.controlledListId() != null;
    }

    // Each dot-separated segment (one per level of OBJECT-property nesting) follows the same
    // identifier shape as a bare field name did before dot-paths existed.
    private static final Pattern SAFE_FIELD_NAME = Pattern.compile("^[a-zA-Z]\\w*(\\.[a-zA-Z]\\w*)*$");

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
                : PROPERTIES_JSON_ARROW_PREFIX + resolvePropertyId(itemTypeId, spec.sortField()) + "'";
        return "ORDER BY " + column + " " + direction + " NULLS LAST, ri.item_id ASC";
    }

    private record RawItem(UUID registerItemId, UUID itemId, String itemType, Map<String, Object> properties, Map<String, Object> states) {}

    public ProjectionResult project(UUID itemTypeId, CollectionProjectionSpec spec, String binaryBaseUrl) {
        String tableName = tableNameFor(itemTypeId);
        SqlFragment filterFragment = buildPredicateFragment(spec.filter(), itemTypeId);

        List<FacetFilter> activeFacetFilters = spec.facetFilters() != null ? spec.facetFilters() : List.of();
        List<String> requestedFacets = resolveRequestedFacets(spec, itemTypeId);

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
                .param(PARAM_ITEM_TYPE_ID, itemTypeId)
                .params(mergeParams(filterFragment, allFacetFilters))
                .param("limit", effectiveLimit(spec))
                .param("offset", effectiveOffset(spec))
                .query((rs, n) -> new RawItem(
                        rs.getObject(COL_REGISTER_ITEM_ID, UUID.class),
                        rs.getObject(COL_ITEM_ID, UUID.class),
                        rs.getString(COL_ITEM_TYPE),
                        parseJsonb(rs.getString(COL_PROPERTIES)),
                        parseJsonb(rs.getString(COL_STATES))))
                .list();

        Map<UUID, List<String>> ownPropertyNames = propertyPathsByIdForItemType(itemTypeId);
        List<AdminStateMachineView> stateMachines = schemaManager.getAdminSchema().items().stream()
                .filter(item -> item.id().equals(itemTypeId))
                .findFirst()
                .map(AdminItemDefinitionView::stateMachines)
                .orElse(null);
        return new ProjectionResult(assembleProjectedItems(rawItems, ownPropertyNames, stateMachines, binaryBaseUrl), totalCount, facetedCount, facets, stateMachineFacets);
    }

    // --- Cross-type (trait/supertype-scoped) queries ---

    private static final Map<String, String> SYSTEM_SORT_COLUMNS_ACROSS_TYPES = Map.of(
            PARAM_ITEM_ID,     "combined.item_id",
            "itemType",        "combined.item_type",
            "createdAt",       "combined.created_at",
            "updatedAt",       "combined.updated_at",
            "visibilityState", "combined.visibility_state"
    );

    // Parallel to project(), not a refactor of it: the single-table path is proven and
    // read-performance-critical, and reshaping it to always route through an N-branch code path
    // risked destabilizing it for the (still overwhelmingly common) case of a type with no
    // descendants. EntityManagerImpl only calls this once a resolved type set actually has more
    // than one member.
    public ProjectionResult projectAcrossTypes(Set<UUID> itemTypeIds, CollectionProjectionSpec spec, String binaryBaseUrl) {
        List<UUID> branches = List.copyOf(itemTypeIds);
        // Property/sort/facet field names resolve against a single representative branch, not each
        // one individually -- deliberate: every branch in a resolved set is guaranteed (by
        // construction of SchemaManager's resolvers) to have inherited whichever properties are
        // common to the whole set, so any single member resolves them identically. A field valid on
        // only one specific sibling branch correctly fails to resolve here, same as it would
        // against project() for that branch alone -- querying it means querying that concrete type
        // directly, not polymorphically. Known minor wrinkle: Set has no defined order, so an empty
        // spec.facets() ("auto-populate every facetable property") auto-populates from whichever
        // branch happens to land here, not necessarily the query's literal root -- for a supertype
        // query that can pull in a sibling's own facetable property alongside the shared ones. Not
        // incorrect (branches lacking that property just contribute no values to it), just
        // possibly slightly more inclusive than strictly necessary in that one case.
        UUID representativeItemTypeId = branches.get(0);

        SqlFragment filterFragment = buildPredicateFragment(spec.filter(), representativeItemTypeId);

        List<FacetFilter> activeFacetFilters = spec.facetFilters() != null ? spec.facetFilters() : List.of();
        List<String> requestedFacets = resolveRequestedFacets(spec, representativeItemTypeId);

        AtomicInteger facetParamCounter = new AtomicInteger();
        Map<String, SqlFragment> facetFragmentsByField = new LinkedHashMap<>();
        for (FacetFilter ff : activeFacetFilters) {
            facetFragmentsByField.put(ff.field(), buildFacetFilterFragment(ff, representativeItemTypeId, facetParamCounter));
        }
        SqlFragment allFacetFilters = combineFragments(facetFragmentsByField.values());

        SqlFragment baseSource = buildUnionedBranchSource(branches, filterFragment, SqlFragment.empty());
        long totalCount = jdbcClient.sql("SELECT COUNT(*) FROM (" + baseSource.sql() + ") combined")
                .params(baseSource.params())
                .query(Long.class).single();

        long facetedCount = totalCount;
        if (!activeFacetFilters.isEmpty()) {
            SqlFragment facetedSource = buildUnionedBranchSource(branches, filterFragment, allFacetFilters);
            facetedCount = jdbcClient.sql("SELECT COUNT(*) FROM (" + facetedSource.sql() + ") combined")
                    .params(facetedSource.params())
                    .query(Long.class).single();
        }

        Map<String, List<FacetBucket>> facets = null;
        if (!requestedFacets.isEmpty()) {
            facets = new LinkedHashMap<>();
            for (String field : requestedFacets) {
                SqlFragment otherFilters = combineFragments(
                        facetFragmentsByField.entrySet().stream()
                                .filter(e -> !e.getKey().equals(field))
                                .map(Map.Entry::getValue)
                                .toList());
                facets.put(field, runTermsFacetQueryAcrossTypes(branches, filterFragment, otherFilters, field, representativeItemTypeId));
            }
        }

        // No cross-type equivalent for state-machine facets in this pass -- naming one specific
        // machine id isn't guaranteed meaningful the same way across an arbitrary branch set the
        // way a merged property lookup is; left unsupported here rather than guessed at.
        Map<String, List<FacetBucket>> stateMachineFacets = null;

        if (facetedCount == 0) {
            return new ProjectionResult(List.of(), totalCount, 0, facets, stateMachineFacets);
        }

        SqlFragment mainSource = buildUnionedBranchSource(branches, filterFragment, allFacetFilters);
        List<RawItem> rawItems = jdbcClient.sql("""
                SELECT combined.register_item_id, combined.item_id, combined.item_type,
                       combined.properties::text AS properties, combined.states::text AS states
                FROM (%s) combined
                %s
                LIMIT :limit OFFSET :offset
                """.formatted(mainSource.sql(), orderByClauseAcrossTypes(spec, representativeItemTypeId)))
                .params(mainSource.params())
                .param("limit", effectiveLimit(spec))
                .param("offset", effectiveOffset(spec))
                .query((rs, n) -> new RawItem(
                        rs.getObject(COL_REGISTER_ITEM_ID, UUID.class),
                        rs.getObject(COL_ITEM_ID, UUID.class),
                        rs.getString(COL_ITEM_TYPE),
                        parseJsonb(rs.getString(COL_PROPERTIES)),
                        parseJsonb(rs.getString(COL_STATES))))
                .list();

        // Merged, not per-row-resolved: property ids are globally unique across the whole schema
        // (never scoped per type), so unioning each branch's own name lookup is a safe, unambiguous
        // merge -- see assembleProjectedItems' own comment on why an over-inclusive stateMachines
        // list is equally safe.
        Map<UUID, List<String>> mergedPropertyNames = new HashMap<>();
        List<AdminStateMachineView> mergedStateMachines = new ArrayList<>();
        for (UUID branchId : branches) {
            mergedPropertyNames.putAll(propertyPathsByIdForItemType(branchId));
            schemaManager.getAdminSchema().items().stream()
                    .filter(item -> item.id().equals(branchId))
                    .findFirst()
                    .map(AdminItemDefinitionView::stateMachines)
                    .ifPresent(mergedStateMachines::addAll);
        }

        var items = assembleProjectedItems(rawItems, mergedPropertyNames,
                mergedStateMachines.isEmpty() ? null : mergedStateMachines, binaryBaseUrl);
        return new ProjectionResult(items, totalCount, facetedCount, facets, stateMachineFacets);
    }

    // One SELECT per branch (its own physical table, its own item-type-id parameter), UNION ALL'd
    // into a single source reusable for a count wrapper, a facet GROUP BY wrapper, or the main
    // paginated select -- filter/additionalFilter's own SQL text (and thus its :param placeholders)
    // is repeated verbatim in every branch, which is safe: JdbcClient binds a named parameter to
    // the same value everywhere it appears in one statement, so the shared filter params only need
    // to be bound once despite appearing N times. properties/states are left as JSONB here (not
    // cast to text) so a facet wrapper can access them directly -- only the final main-select
    // wrapper casts to text, matching what parseJsonb expects.
    private SqlFragment buildUnionedBranchSource(List<UUID> branches, SqlFragment filter, SqlFragment additionalFilter) {
        List<String> selects = new ArrayList<>();
        Map<String, Object> params = new HashMap<>();
        params.putAll(filter.params());
        params.putAll(additionalFilter.params());
        for (int i = 0; i < branches.size(); i++) {
            UUID branchId = branches.get(i);
            String paramName = "branchType_" + i;
            params.put(paramName, branchId);
            selects.add("""
                    SELECT ri.id AS register_item_id, ri.item_id, si.name AS item_type,
                           rt.properties AS properties, rt.states AS states,
                           ri.created_at AS created_at, ri.updated_at AS updated_at, ri.state AS visibility_state
                    FROM register_item ri
                    JOIN %s rt ON rt.register_item_id = ri.id
                    JOIN schema_item si ON si.id = ri.item_type_id
                    WHERE ri.item_type_id = :%s
                      AND ri.state = 'COMMITTED'
                      %s
                      %s
                    """.formatted(tableNameFor(branchId), paramName, filter.sql(), additionalFilter.sql()));
        }
        return new SqlFragment(String.join(" UNION ALL ", selects), params);
    }

    private List<FacetBucket> runTermsFacetQueryAcrossTypes(List<UUID> branches, SqlFragment filter, SqlFragment otherFacetFilters,
                                                              String field, UUID representativeItemTypeId) {
        String propertyId = resolvePropertyId(representativeItemTypeId, sanitizeFieldName(field)).toString();
        SqlFragment source = buildUnionedBranchSource(branches, filter, otherFacetFilters);
        return jdbcClient.sql("""
                SELECT combined.properties->>'%s' AS value, COUNT(*) AS count
                FROM (%s) combined
                GROUP BY combined.properties->>'%s'
                ORDER BY count DESC, value ASC NULLS LAST
                """.formatted(propertyId, source.sql(), propertyId))
                .params(source.params())
                .query((rs, n) -> {
                    String value = rs.getString(COL_FACET_VALUE);
                    return new FacetBucket(value, value, rs.getLong(COL_FACET_COUNT));
                })
                .list();
    }

    private String orderByClauseAcrossTypes(CollectionProjectionSpec spec, UUID representativeItemTypeId) {
        if (spec.sortField() == null || spec.sortField().isBlank()) return "ORDER BY combined.item_id ASC";
        String direction = "DESC".equalsIgnoreCase(spec.sortDirection()) ? "DESC" : "ASC";
        String column = SYSTEM_SORT_COLUMNS_ACROSS_TYPES.containsKey(spec.sortField())
                ? SYSTEM_SORT_COLUMNS_ACROSS_TYPES.get(spec.sortField())
                : "combined.properties->>'" + resolvePropertyId(representativeItemTypeId, spec.sortField()) + "'";
        return "ORDER BY " + column + " " + direction + " NULLS LAST, combined.item_id ASC";
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
                .param(PARAM_ITEM_TYPE_ID, itemTypeId)
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
                .param(PARAM_ITEM_TYPE_ID, itemTypeId)
                .params(mergeParams(filter, otherFacetFilters))
                .query((rs, n) -> {
                    String value = rs.getString(COL_FACET_VALUE);
                    return new FacetBucket(value, value, rs.getLong(COL_FACET_COUNT));
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
                .param(PARAM_ITEM_TYPE_ID, itemTypeId)
                .params(filter.params())
                .query((rs, n) -> {
                    String stateId = rs.getString(COL_FACET_VALUE);
                    String label = stateId == null ? null : stateNames.get(UUID.fromString(stateId));
                    return new FacetBucket(stateId, label, rs.getLong(COL_FACET_COUNT));
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
            clauses.add(PROPERTIES_JSON_ARROW_PREFIX + propertyId + "' IN (:" + paramName + ")");
            params.put(paramName, values);
        }
        if (includeNull) {
            clauses.add(PROPERTIES_JSON_ARROW_PREFIX + propertyId + "' IS NULL");
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
                .param(PARAM_ITEM_TYPE_ID, itemTypeId)
                .param(PARAM_ITEM_ID, itemId)
                .query((rs, n) -> new RawItem(
                        rs.getObject(COL_REGISTER_ITEM_ID, UUID.class),
                        rs.getObject(COL_ITEM_ID, UUID.class),
                        rs.getString(COL_ITEM_TYPE),
                        parseJsonb(rs.getString(COL_PROPERTIES)),
                        parseJsonb(rs.getString(COL_STATES))))
                .list();

        if (rawItems.isEmpty()) {
            return Optional.empty();
        }
        Map<UUID, List<String>> ownPropertyNames = propertyPathsByIdForItemType(itemTypeId);
        List<AdminStateMachineView> stateMachines = schemaManager.getAdminSchema().items().stream()
                .filter(item -> item.id().equals(itemTypeId))
                .findFirst()
                .map(AdminItemDefinitionView::stateMachines)
                .orElse(null);
        return Optional.of(assembleProjectedItems(rawItems, ownPropertyNames, stateMachines, binaryBaseUrl).get(0));
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
                .param(PARAM_ITEM_ID, itemId)
                .query((rs, n) -> parseJsonb(rs.getString(COL_PROPERTIES)))
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
                .param(PARAM_ITEM_ID, itemId)
                .param(PARAM_ITEM_TYPE_ID, itemTypeId)
                .param(PARAM_TRANSACTION_ID, transactionId)
                .query(UUID.class)
                .single();

        jdbcClient.sql("INSERT INTO %s (register_item_id, properties) VALUES (:registerItemId, :properties::jsonb)"
                .formatted(tableNameFor(itemTypeId)))
                .param("registerItemId", registerItemId)
                .param(COL_PROPERTIES, writeProperties(properties))
                .update();

        return registerItemId;
    }

    public Optional<UUID> findItemTypeId(UUID itemId) {
        return jdbcClient.sql("SELECT item_type_id FROM register_item WHERE item_id = :itemId AND state = 'COMMITTED'")
                .param(PARAM_ITEM_ID, itemId)
                .query(UUID.class)
                .optional();
    }

    public void commitItem(UUID itemId, UUID transactionId, UUID commitId) {
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
                .param(PARAM_ITEM_ID, itemId)
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
                .param(PARAM_LINK_ID, linkId)
                .query(UUID.class)
                .optional();
    }

    public UUID stageLinkUpdate(UUID linkId, Map<UUID, Object> propertiesDiff, UUID transactionId) {
        UUID currentRegisterLinkId = jdbcClient.sql("SELECT id FROM register_link WHERE link_id = :linkId AND state = 'COMMITTED'")
                .param(PARAM_LINK_ID, linkId)
                .query(UUID.class)
                .single();

        UUID linkTypeId = findLinkTypeId(linkId).orElseThrow();

        Map<String, Object> currentProperties = jdbcClient.sql("SELECT properties::text AS properties FROM %s WHERE register_link_id = :id"
                .formatted(linkTableNameFor(linkTypeId)))
                .param("id", currentRegisterLinkId)
                .query((rs, n) -> parseJsonb(rs.getString(COL_PROPERTIES)))
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
                        rs.getObject(COL_ITEM_ID, UUID.class)))
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
                .param(PARAM_LINK_ID, linkId)
                .param("linkTypeId", linkTypeId)
                .param(PARAM_TRANSACTION_ID, transactionId)
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
                    .param("perspectiveId", endpoint.perspectiveId())
                    .param("registerItemId", registerItemId)
                    .update();
        }
    }

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

        jdbcClient.sql("UPDATE register_link SET state = 'COMMITTED', commit_id = :commitId, updated_at = NOW() WHERE id = :stagedRegisterLinkId")
                .param("commitId", commitId)
                .param("stagedRegisterLinkId", stagedRegisterLinkId)
                .update();
    }

    public void deleteLink(UUID linkId) {
        jdbcClient.sql("DELETE FROM register_link WHERE link_id = :linkId AND state = 'COMMITTED'")
                .param(PARAM_LINK_ID, linkId)
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
                .param(PARAM_ITEM_ID, itemId)
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

    public void discardStaged(UUID transactionId) {
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

    // Returns each leaf property's full name path from root to leaf, not just its own name --
    // storage is flat (a leaf's own id), but two leaves nested under different object properties
    // can share a name (e.g. dimensions.length vs packagingDimensions.length), so only the full
    // path is unambiguous. An OBJECT property's own id never appears here: nothing is ever stored
    // under a container's own id, only under each of its leaves'.
    private Map<UUID, List<String>> propertyPathsByIdForItemType(UUID itemTypeId) {
        return schemaManager.getAdminSchema().items().stream()
                .filter(item -> item.id().equals(itemTypeId))
                .findFirst()
                .map(item -> propertyPaths(item.properties()))
                .orElse(Map.of());
    }

    private Map<UUID, List<String>> propertyPathsByIdForLinkType(UUID linkTypeId) {
        return schemaManager.getAdminSchema().links().stream()
                .filter(link -> link.id().equals(linkTypeId))
                .findFirst()
                .map(link -> propertyPaths(link.properties()))
                .orElse(Map.of());
    }

    private Map<UUID, List<String>> propertyPaths(List<AdminPropertyDefinitionView> properties) {
        Map<UUID, List<String>> result = new HashMap<>();
        for (AdminPropertyDefinitionView p : properties) {
            if (p instanceof ObjectAdminPropertyDefinitionView o) {
                propertyPaths(o.properties()).forEach((id, childPath) -> {
                    List<String> fullPath = new ArrayList<>();
                    fullPath.add(o.name());
                    fullPath.addAll(childPath);
                    result.put(id, fullPath);
                });
            } else {
                result.put(p.id(), List.of(p.name()));
            }
        }
        return result;
    }

    // The inverse lookup -- a client-supplied property *name* (in a filter, sort field, or facet
    // field) has to resolve to the id the register actually stores before it can be used in SQL.
    // This also gives predicate/sort/facet validation for free: an unknown name now fails clearly
    // here instead of silently generating a condition that matches nothing.
    //
    // propertyName may be a dot-separated path (e.g. "dimensions.length") reaching into one or more
    // OBJECT properties -- mirrors the write-side walk in
    // MutationRequestProcessor.resolveObjectPropertyValue, which resolves each segment against the
    // *containing* object property's own children rather than a global name index. That scoping is
    // required, not just mirrored for consistency: propertyPaths' own comment notes two leaves
    // nested under different object properties can share a name (dimensions.length vs
    // packagingDimensions.length), so only the full path is unambiguous.
    private UUID resolvePropertyId(UUID itemTypeId, String propertyName) {
        List<AdminPropertyDefinitionView> rootProperties = schemaManager.getAdminSchema().items().stream()
                .filter(item -> item.id().equals(itemTypeId))
                .findFirst()
                .map(AdminItemDefinitionView::properties)
                .orElse(List.of());
        return resolvePropertyId(rootProperties, propertyName.split("\\."), propertyName);
    }

    private UUID resolvePropertyId(List<AdminPropertyDefinitionView> properties, String[] pathSegments, String fullPath) {
        AdminPropertyDefinitionView match = properties.stream()
                .filter(p -> p.name().equals(pathSegments[0]))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown property: " + fullPath));
        boolean isLastSegment = pathSegments.length == 1;
        if (match instanceof ObjectAdminPropertyDefinitionView object) {
            if (isLastSegment) {
                throw new IllegalArgumentException("Property is an object, not a leaf value: " + fullPath);
            }
            return resolvePropertyId(object.properties(), Arrays.copyOfRange(pathSegments, 1, pathSegments.length), fullPath);
        }
        if (!isLastSegment) {
            throw new IllegalArgumentException("Unknown property: " + fullPath);
        }
        return match.id();
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
                .param(PARAM_ITEM_ID, itemId)
                .update();
    }

    // Converts an id-keyed properties map (as read straight from the register's JSONB, always
    // flat -- storage never nests) into the nested name-keyed map a client actually wants to see,
    // by walking each stored leaf's full path and inserting it at the right depth, creating
    // intermediate maps as needed. A key that no longer resolves (the property was deleted from
    // the schema after this row was written) is silently dropped, same as before. The unchecked
    // cast is safe: only an OBJECT property's name ever occupies a non-final path segment, and a
    // container's own id is never a storage key (see propertyPaths), so a segment can't be both an
    // intermediate map and a leaf value at once.
    @SuppressWarnings("unchecked")
    private Map<String, Object> namesForIds(Map<String, Object> propertiesById, Map<UUID, List<String>> idToPath) {
        Map<String, Object> byName = new HashMap<>();
        propertiesById.forEach((idString, value) -> {
            List<String> path = idToPath.get(UUID.fromString(idString));
            if (path == null) return;
            Map<String, Object> cursor = byName;
            for (int i = 0; i < path.size() - 1; i++) {
                cursor = (Map<String, Object>) cursor.computeIfAbsent(path.get(i), k -> new HashMap<String, Object>());
            }
            cursor.put(path.get(path.size() - 1), value);
        });
        return byName;
    }

    private record LinkRow(UUID myRegisterItemId, String perspectiveName,
                           UUID registerLinkId, UUID linkId, UUID linkDefinitionId,
                           UUID linkedRegisterItemId, UUID linkedItemId, UUID linkedItemTypeId, String linkedItemType) {}

    private record BinaryPropertyRow(UUID registerItemId, String propertyName, UUID binaryId) {}

    // Every item type's EFFECTIVE display-label pattern (own, or inherited from the nearest
    // supertype that defines one), keyed by type name -- name, not id, because RawItem/LinkRow
    // only carry the type name per row (schema_item.name is NOT NULL UNIQUE, so this is
    // unambiguous). Resolved for the whole schema, not just the branch(es) a given call is
    // querying: a linked item reachable via linksByItem below can be of a type completely
    // unrelated to the outer query, so a branch-scoped resolution (like the property-name merge
    // projectAcrossTypes already does) wouldn't cover it. Mirrors SchemaManager's own
    // implementsTraitInChain walk-up-supertype-chain idiom.
    private Map<String, String> resolveEffectiveDisplayLabelPatterns() {
        AdminSchemaView schema = schemaManager.getAdminSchema();
        DisplayLabelPatternsCache cached = displayLabelPatternsCache.get();
        if (schema == cached.schema()) {
            return cached.resolved();
        }
        Map<UUID, AdminItemDefinitionView> itemById = schema.items().stream()
                .collect(Collectors.toMap(AdminItemDefinitionView::id, i -> i));
        Map<String, String> resolved = new HashMap<>();
        for (AdminItemDefinitionView item : schema.items()) {
            AdminItemDefinitionView current = item;
            while (current != null && current.displayLabelPattern() == null) {
                current = current.supertypeId() == null ? null : itemById.get(current.supertypeId());
            }
            if (current != null) {
                resolved.put(item.name(), current.displayLabelPattern());
            }
        }
        displayLabelPatternsCache.set(new DisplayLabelPatternsCache(schema, resolved));
        return resolved;
    }

    private static final Pattern DISPLAY_LABEL_SEPARATOR_TRIM = Pattern.compile("(^[\\s,\\-/|]+)|([\\s,\\-/|]+$)");
    private static final Pattern DISPLAY_LABEL_WHITESPACE_RUN = Pattern.compile("\\s+");

    // pattern is the EFFECTIVE (already supertype-resolved) pattern for this item's type, or null
    // if none exists anywhere in its chain. A broken pattern (bad SpEL syntax, a property that
    // isn't actually a Map key so evaluation throws for some other reason) must never break
    // projection -- caught broadly and logged, not propagated. Evaluated with
    // SimpleEvaluationContext (not the default StandardEvaluationContext): no method calls, no
    // `new`, no type/bean references -- MapAccessor is registered so a pattern can reference
    // property names directly (`lastName`) rather than needing `['lastName']` map-index syntax.
    private String computeDisplayLabel(UUID itemId, Map<String, Object> props, String pattern) {
        if (pattern != null && !pattern.isBlank()) {
            try {
                Expression expression = compiledPatternCache.computeIfAbsent(pattern, spelParser::parseExpression);
                var context = SimpleEvaluationContext.forPropertyAccessors(new MapAccessor()).build();
                Object result = expression.getValue(context, props);
                String cleaned = DISPLAY_LABEL_WHITESPACE_RUN.matcher(
                        DISPLAY_LABEL_SEPARATOR_TRIM.matcher(String.valueOf(result == null ? "" : result)).replaceAll(""))
                        .replaceAll(" ").trim();
                if (!cleaned.isEmpty()) {
                    return cleaned;
                }
            } catch (Exception e) {
                LOG.warn("Display label pattern \"{}\" failed to evaluate for item {}: {}", pattern, itemId, e.getMessage());
            }
        }
        // No pattern anywhere in the chain, evaluation failed, or the cleaned result was empty --
        // a deliberate "fail loud" signal (the item's own id) that this type has no usable display
        // label configured, rather than silently guessing from a title/name-ish property.
        return itemId.toString();
    }

    // ownPropertyNames/stateMachines are pre-resolved by the caller rather than looked up here from
    // a single itemTypeId -- the single-table project()/projectOne() resolve them from their one
    // known type; projectAcrossTypes resolves a merged view across every branch instead (property
    // ids are globally unique across the whole schema, so a plain union of each branch's own
    // lookup is a safe, unambiguous merge -- an over-inclusive stateMachines list is likewise safe,
    // since buildProjectedItemStates already skips any machine a given row's states don't mention).
    private List<ProjectedItem> assembleProjectedItems(List<RawItem> rawItems, Map<UUID, List<String>> ownPropertyNames,
                                                         List<AdminStateMachineView> stateMachines, String binaryBaseUrl) {
        List<UUID> rawItemIds = rawItems.stream().map(RawItem::registerItemId).toList();
        Map<String, String> displayLabelPatterns = resolveEffectiveDisplayLabelPatterns();

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
                        rs.getObject(COL_REGISTER_LINK_ID, UUID.class),
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
                COL_REGISTER_ITEM_ID);

        Map<UUID, Map<String, Object>> linkProperties = fetchPropertiesByRegisterItemId(
                linkRows.stream().collect(Collectors.groupingBy(
                        LinkRow::linkDefinitionId,
                        Collectors.mapping(LinkRow::registerLinkId, Collectors.toList()))),
                COL_REGISTER_LINK_ID);

        Map<UUID, Map<String, List<ProjectedLink>>> linksByItem = linkRows.stream()
                .collect(Collectors.groupingBy(
                        LinkRow::myRegisterItemId,
                        Collectors.groupingBy(
                                LinkRow::perspectiveName,
                                Collectors.mapping(row -> {
                                    Map<String, Object> linkedProps = linkedItemProperties.getOrDefault(row.linkedRegisterItemId(), Map.of());
                                    return new ProjectedLink(
                                        row.linkId(),
                                        linkProperties.getOrDefault(row.registerLinkId(), Map.of()),
                                        // states is left null for a linked item -- a projection's link expansion has
                                        // never fetched a linked item's full state (matching binary properties'
                                        // own scope, which the linked item also doesn't get); this is about the
                                        // top-level projected item's state, not every item reachable through it.
                                        new ProjectedItem(
                                                row.linkedItemId(),
                                                row.linkedItemType(),
                                                linkedProps,
                                                Map.of(),
                                                null,
                                                null,
                                                computeDisplayLabel(row.linkedItemId(), linkedProps, displayLabelPatterns.get(row.linkedItemType())))
                                    );
                                },
                                Collectors.toList()))));

        List<BinaryPropertyRow> binaryRows = jdbcClient.sql("""
                SELECT rbp.register_item_id, sp.name AS property_name, rbp.binary_id
                FROM register_binary_property rbp
                JOIN schema_property sp ON sp.id = rbp.property_id
                WHERE rbp.register_item_id IN (:ids)
                """)
                .param("ids", rawItemIds)
                .query((rs, n) -> new BinaryPropertyRow(
                        rs.getObject(COL_REGISTER_ITEM_ID, UUID.class),
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
                            null,
                            computeDisplayLabel(raw.itemId(), props, displayLabelPatterns.get(raw.itemType())));
                })
                .toList();
    }

    // Resolves the register's id-keyed states storage back to names for the client. Returns null
    // (not an empty map) when the item type has no state machines, or none is recorded.
    private Map<String, ProjectedItemState> buildProjectedItemStates(Map<String, Object> statesById, List<AdminStateMachineView> stateMachines) {
        if (stateMachines == null || stateMachines.isEmpty() || statesById == null || statesById.isEmpty()) {
            return null;
        }
        Map<String, ProjectedItemState> result = new LinkedHashMap<>();
        for (AdminStateMachineView machine : stateMachines) {
            resolveCurrentStateName(machine, statesById.get(machine.id().toString()))
                    // A dangling id (the state was deleted from the schema after this row was
                    // written) is silently dropped, same as namesForIds does for properties.
                    .ifPresent(currentStateName -> result.put(machine.name(), new ProjectedItemState(currentStateName, null)));
        }
        return result.isEmpty() ? null : result;
    }

    private Optional<String> resolveCurrentStateName(AdminStateMachineView machine, Object rawStateEntry) {
        if (!(rawStateEntry instanceof Map<?, ?> stateEntry)) return Optional.empty();
        Object currentStateId = stateEntry.get("currentStateId");
        if (currentStateId == null) return Optional.empty();
        return machine.states().stream()
                .filter(s -> s.id().toString().equals(currentStateId.toString()))
                .map(AdminStateView::name)
                .findFirst();
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
        boolean isLinkTable = idColumn.equals(COL_REGISTER_LINK_ID);
        Map<UUID, Map<String, Object>> result = new HashMap<>();
        for (var entry : typeOrDefIdToRegisterIds.entrySet()) {
            UUID typeOrDefId = entry.getKey();
            String table = isLinkTable ? linkTableNameFor(typeOrDefId) : tableNameFor(typeOrDefId);
            Map<UUID, List<String>> idToPath = isLinkTable
                    ? propertyPathsByIdForLinkType(typeOrDefId)
                    : propertyPathsByIdForItemType(typeOrDefId);
            jdbcClient.sql("SELECT " + idColumn + ", properties::text FROM " + table + " WHERE " + idColumn + " IN (:ids)")
                    .param("ids", entry.getValue())
                    .query((rs, n) -> Map.entry(
                            rs.getObject(idColumn, UUID.class),
                            namesForIds(parseJsonb(rs.getString(COL_PROPERTIES)), idToPath)))
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
    // after boot fail with "relation does not exist", since the static V1_0_0_1__baseline.sql Flyway
    // migration only creates the shared register_* tables once, for whatever types already
    // existed when it ran. Traits have no register-side table of their own (their properties live
    // in the owning item type's JSONB blob), so trait events are intentionally ignored here.

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
