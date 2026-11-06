package org.ntrloc.graph.db.partition.register;

import tools.jackson.databind.ObjectMapper;
import org.ntrloc.graph.db.partition.authorization.RequestPermissionContext;
import org.ntrloc.graph.db.partition.binary.BinaryPartitionManager;
import org.ntrloc.graph.db.partition.binary.BinaryPropertyObject;
import org.ntrloc.graph.db.partition.register.ExplicitProjection.Access;
import org.ntrloc.graph.db.partition.register.ExplicitProjection.Comparison;
import org.ntrloc.graph.db.partition.register.ExplicitProjection.Condition;
import org.ntrloc.graph.db.partition.register.ExplicitProjection.Page;
import org.ntrloc.graph.db.partition.register.ExplicitProjection.StateMachineFacet;
import org.ntrloc.graph.db.partition.register.ExplicitProjection.TermsFacet;
import org.ntrloc.graph.db.partition.register.ExplicitProjection.TermsFilter;
import org.ntrloc.graph.db.partition.register.ExplicitProjection.Sort;
import org.ntrloc.graph.db.partition.register.ExplicitProjection.SortKey;
import org.ntrloc.graph.db.partition.register.ExplicitProjection.ValueType;
import org.ntrloc.graph.access.FactResolver;
import org.ntrloc.graph.projection.AndPredicate;
import org.ntrloc.graph.projection.AvailableTransition;
import org.ntrloc.graph.projection.CollectionProjectionSpec;
import org.ntrloc.graph.projection.FacetBucket;
import org.ntrloc.graph.projection.FacetFilter;
import org.ntrloc.graph.projection.LinkProjectionSpec;
import org.ntrloc.graph.projection.NotPredicate;
import org.ntrloc.graph.projection.Operator;
import org.ntrloc.graph.projection.Predicate;
import org.ntrloc.graph.db.partition.schema.ControlledListLabels;
import org.ntrloc.graph.db.partition.schema.ControlledListManager;
import org.ntrloc.graph.db.partition.schema.SchemaManager;
import org.ntrloc.graph.db.partition.schema.definition.PropertyCardinality;
import org.ntrloc.graph.db.partition.schema.definition.PropertyType;
import org.ntrloc.graph.db.partition.schema.definition.view.admin.AdminBinaryPropertyDefinitionView;
import org.ntrloc.graph.db.partition.schema.definition.view.admin.AdminItemDefinitionView;
import org.ntrloc.graph.db.partition.schema.definition.view.admin.AdminPropertyDefinitionView;
import org.ntrloc.graph.db.partition.schema.definition.view.admin.DynamicFieldView;
import org.ntrloc.graph.db.partition.schema.definition.view.admin.AdminSchemaView;
import org.ntrloc.graph.db.partition.schema.definition.view.admin.AdminItemLinkPerspectiveView;
import org.ntrloc.graph.db.partition.schema.definition.view.admin.AdminStateMachineView;
import org.ntrloc.graph.db.partition.schema.definition.view.admin.AdminStateView;
import org.ntrloc.graph.db.partition.schema.definition.view.admin.AdminTransitionView;
import org.ntrloc.graph.db.partition.schema.definition.view.admin.AdminPropertyGroupView;
import org.ntrloc.graph.db.partition.schema.event.SchemaChangeEvent;
import org.ntrloc.graph.db.partition.schema.event.SchemaChangeListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.context.expression.MapAccessor;
import org.springframework.expression.Expression;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.SimpleEvaluationContext;
import org.ntrloc.graph.projection.ProjectedItem;
import org.ntrloc.graph.projection.ProjectedItemPermissions;
import org.ntrloc.graph.projection.ProjectedItemState;
import org.ntrloc.graph.projection.ProjectedLink;
import org.ntrloc.graph.projection.OrPredicate;
import org.ntrloc.graph.projection.ProjectionResult;
import org.ntrloc.graph.projection.PropertyExistencePredicate;
import org.ntrloc.graph.projection.PropertyValuePredicate;
import org.ntrloc.graph.projection.RangeFacetFilter;
import org.ntrloc.graph.projection.DateRangeFacetFilter;
import org.ntrloc.graph.projection.StateValuePredicate;
import org.ntrloc.graph.projection.TermsFacetFilter;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Component
public class RegisterPartitionManager implements SchemaChangeListener, FactResolver {

    private static final Logger LOG = LoggerFactory.getLogger(RegisterPartitionManager.class);

    private final JdbcClient jdbcClient;
    private final ObjectMapper objectMapper;
    private final BinaryPartitionManager binaryPartitionManager;
    private final SchemaManager schemaManager;
    private final ControlledListManager controlledListManager;
    private final RegisterStore registerStore;

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
                                    BinaryPartitionManager binaryPartitionManager,
                                    SchemaManager schemaManager,
                                    ControlledListManager controlledListManager, RegisterStore registerStore) {
        this.jdbcClient = jdbcClient;
        this.objectMapper = objectMapper;
        this.binaryPartitionManager = binaryPartitionManager;
        this.schemaManager = schemaManager;
        this.controlledListManager = controlledListManager;
        this.registerStore = registerStore;
    }

    private static final String PARAM_ITEM_ID = "itemId";
    private static final String PARAM_ITEM_TYPE_ID = "itemTypeId";
    private static final String COL_REGISTER_ITEM_ID = "register_item_id";
    private static final String COL_ITEM_ID = "item_id";
    private static final String COL_PROPERTIES = "properties";
    private static final String COL_STATES = "states";
    private static final String COL_FACET_VALUE = "value";
    private static final String PARAM_TRANSACTION_ID = "transactionId";
    private static final String PARAM_LINK_ID = "linkId";
    private static final String STATE_CURRENT_STATE_ID = "currentStateId";
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

    // Sortable system fields by client name. "itemType" is handled separately (it needs the names).
    private static final String SORT_FIELD_ITEM_TYPE = "itemType";
    private static final Map<String, SortKey.Column> SYSTEM_SORT_COLUMNS = Map.of(
            PARAM_ITEM_ID,      SortKey.Column.ITEM_ID,
            PARAM_CREATED_DATE, SortKey.Column.CREATED_DATE,
            PARAM_CREATED_BY,   SortKey.Column.CREATED_BY,
            PARAM_UPDATED_DATE, SortKey.Column.UPDATED_DATE,
            PARAM_UPDATED_BY,   SortKey.Column.UPDATED_BY,
            "visibilityState",  SortKey.Column.VISIBILITY_STATE
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
                .map(item -> collectFacetableFieldNames(item.properties(), item.groups(), ""))
                .orElse(List.of());
    }

    // Recurses through property groups so a nested facetable property (e.g. "dimensions.unit", or a
    // trait's "File.mimeType") is auto-discovered by facets: [] the same as a top-level one, using
    // the same dot-path naming resolvePropertyId understands for explicit facet requests. Groups are
    // structural and never facetable themselves. No cardinality guard needed on the recursion: a
    // group holds each of its properties at most once per item, so a child's value is never
    // multi-valued through nesting (LIST/SET cardinality is ruled out by isTermsFacetable).
    private List<String> collectFacetableFieldNames(List<AdminPropertyDefinitionView> properties, List<AdminPropertyGroupView> groups, String pathPrefix) {
        List<String> names = new ArrayList<>();
        for (AdminPropertyDefinitionView p : properties) {
            if (isTermsFacetable(p)) {
                names.add(pathPrefix + p.name());
            }
        }
        for (AdminPropertyGroupView g : groups) {
            names.addAll(collectFacetableFieldNames(g.properties(), g.groups(), pathPrefix + g.name() + "."));
        }
        return names;
    }

    // spec.facets() empty (not null) means "every facetable property" -- auto-populated, same
    // "give me everything you can" semantics as an empty/null explicit properties list elsewhere,
    // so an unreadable one is silently excluded here rather than rejected. A non-empty list is an
    // explicit request naming specific fields, which has to be a loud rejection instead if any of
    // them is unreadable -- same rule filter/sort already apply, via the same check.
    private List<String> resolveRequestedFacets(CollectionProjectionSpec spec, UUID itemTypeId, RequestPermissionContext permissions) {
        if (spec.facets() == null) return List.of();
        if (spec.facets().isEmpty()) {
            return facetableFieldsFor(itemTypeId).stream()
                    .filter(name -> isPropertyReadableByAnyMarker(permissions, resolvePropertyId(itemTypeId, name)))
                    .toList();
        }
        spec.facets().forEach(field -> requirePropertyReadableByAnyMarker(permissions, resolvePropertyId(itemTypeId, sanitizeFieldName(field)), field));
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

    // Each dot-separated segment (one per level of property-group nesting) follows the same
    // identifier shape as a bare field name did before dot-paths existed. Quantifiers are
    // possessive (++/*+) so the engine never backtracks into them -- each segment's own \w*+ can't
    // be re-split against the outer repetition, which is what a client-controlled field name would
    // need to trigger catastrophic backtracking on a pathological input.
    private static final Pattern SAFE_FIELD_NAME = Pattern.compile("^[a-zA-Z]\\w*+(?:\\.[a-zA-Z]\\w*+)*+$");

    private String sanitizeFieldName(String field) {
        if (field == null || !SAFE_FIELD_NAME.matcher(field).matches()) {
            throw new IllegalArgumentException("Invalid facet field name: " + field);
        }
        return field;
    }

    // The client's sort resolved against the schema. Property names resolve against resolveAgainst
    // (the one type, or a cross-type query's representative); itemTypeIds are every type queried, for
    // sorting by type name. A field inside a binary's metadata (e.g. "attachment.length") is only
    // sortable within a single type, and sorts by Binary Store's SQL for that field.
    private Sort toSort(CollectionProjectionSpec spec, UUID resolveAgainst, Collection<UUID> itemTypeIds,
                        RequestPermissionContext permissions, boolean allowBinaryFields) {
        String field = spec.sortField();
        if (field == null || field.isBlank()) return Sort.DEFAULT;
        boolean descending = "DESC".equalsIgnoreCase(spec.sortDirection());
        if (SORT_FIELD_ITEM_TYPE.equals(field)) {
            Map<UUID, String> names = new HashMap<>();
            itemTypeIds.forEach(id -> names.put(id, itemTypeName(id)));
            return new Sort(new SortKey.ItemTypeName(names), descending);
        }
        SortKey.Column column = SYSTEM_SORT_COLUMNS.get(field);
        if (column != null) return new Sort(column, descending);

        PropertyPathResolution resolution = allowBinaryFields
                ? resolvePropertyPath(resolveAgainst, field)
                : PropertyPathResolution.ofProperty(resolveProperty(resolveAgainst, field));
        requirePropertyReadableByAnyMarker(permissions, resolution.property().id(), field);
        if (!resolution.binaryFieldPath().isEmpty()) {
            return new Sort(new SortKey.BinaryField(resolution.property().id(),
                    binaryPartitionManager.metadataFieldText(resolution.binaryFieldPath()), valueType(resolution.binaryFieldType())), descending);
        }
        if (resolution.property().type() == PropertyType.BINARY) {
            throw new IllegalArgumentException("'" + field + "' is binary content -- sort by an intrinsic attribute instead, e.g. '"
                    + field + ".length'");
        }
        return new Sort(new SortKey.Property(resolution.property().id(), valueType(resolution.property().type())), descending);
    }

    private static ValueType valueType(PropertyType type) {
        return switch (type) {
            case STRING -> ValueType.TEXT;
            case INTEGER -> ValueType.INTEGER;
            case DECIMAL -> ValueType.DECIMAL;
            case DATE -> ValueType.DATE;
            case DATETIME -> ValueType.DATETIME;
            case BOOLEAN -> ValueType.BOOLEAN;
            case BINARY -> throw new IllegalStateException("BINARY values aren't stored as comparable values");
        };
    }

    private record RawItem(UUID registerItemId, UUID itemId, String itemType, Map<String, Object> properties, Map<String, Object> states,
                            OffsetDateTime createdDate, UUID createdBy, OffsetDateTime updatedDate, UUID updatedBy, RegisterRows.ItemRow row) {}

    // Transitional: assembly still works on RawItem's name-and-raw-JSON shape. Goes away when
    // assembly moves to ProjectionEngine and works on RegisterRows directly.
    private RawItem toRawItem(RegisterRows.ItemRow row) {
        Map<String, Object> states = new HashMap<>();
        row.currentStateIds().forEach((stateMachineId, stateId) ->
                states.put(stateMachineId.toString(), Map.of(STATE_CURRENT_STATE_ID, stateId.toString())));
        return new RawItem(row.registerItemId(), row.itemId(), itemTypeName(row.itemTypeId()), keysToStrings(row.properties()), states,
                row.audit().createdDate(), row.audit().createdBy(), row.audit().updatedDate(), row.audit().updatedBy(), row);
    }

    private String itemTypeName(UUID itemTypeId) {
        String name = itemTypeNameOrNull(itemTypeId);
        if (name == null) throw new IllegalStateException("Register row for unknown item type " + itemTypeId);
        return name;
    }

    private @Nullable String itemTypeNameOrNull(UUID itemTypeId) {
        return schemaManager.getAdminSchema().items().stream()
                .filter(item -> item.id().equals(itemTypeId))
                .map(AdminItemDefinitionView::name)
                .findFirst()
                .orElse(null);
    }

    public ProjectionResult project(UUID itemTypeId, CollectionProjectionSpec spec, String binaryBaseUrl) {
        return project(itemTypeId, spec, binaryBaseUrl, RequestPermissionContext.forAdmin());
    }

    public ProjectionResult project(UUID itemTypeId, CollectionProjectionSpec spec, String binaryBaseUrl, RequestPermissionContext permissions) {
        Condition filter = toCondition(spec.filter(), itemTypeId, permissions);
        List<TermsFilter> termsFilters = toTermsFilters(spec, itemTypeId, permissions);
        Map<String, AdminPropertyDefinitionView> facetProperties = new LinkedHashMap<>();
        resolveRequestedFacets(spec, itemTypeId, permissions)
                .forEach(field -> facetProperties.put(field, resolveProperty(itemTypeId, sanitizeFieldName(field))));
        // Never auto-populated: only machines the client named (see CollectionProjectionSpec.stateMachineFacets).
        Map<String, UUID> stateMachineIds = new LinkedHashMap<>();
        (spec.stateMachineFacets() != null ? spec.stateMachineFacets() : List.<String>of())
                .forEach(name -> stateMachineIds.put(name, schemaManager.resolveStateMachineId(itemTypeId, name)));

        RegisterRows rows = registerStore.query(new ExplicitProjection(Set.of(itemTypeId), filter, toAccess(permissions), termsFilters,
                facetProperties.entrySet().stream().map(e -> new TermsFacet(e.getKey(), e.getValue().id())).toList(),
                stateMachineIds.entrySet().stream().map(e -> new StateMachineFacet(e.getKey(), e.getValue())).toList(),
                toSort(spec, itemTypeId, Set.of(itemTypeId), permissions, true),
                new Page(effectiveOffset(spec), effectiveLimit(spec)), toLinkSelection(spec.links()), toLinkAccess(permissions), true));

        Map<String, List<FacetBucket>> facets = facetProperties.isEmpty() ? null : new LinkedHashMap<>();
        facetProperties.forEach((field, property) -> facets.put(field, labelledTermsBuckets(rows.termsFacetCounts().get(field), property)));
        Map<String, List<FacetBucket>> stateMachineFacets = stateMachineIds.isEmpty() ? null : new LinkedHashMap<>();
        stateMachineIds.forEach((name, stateMachineId) ->
                stateMachineFacets.put(name, stateMachineBuckets(rows.stateMachineFacetCounts().get(name), stateMachineId)));

        long totalCount = rows.counts().total();
        long facetedCount = rows.counts().faceted();
        if (facetedCount == 0) {
            return new ProjectionResult(List.of(), totalCount, 0, facets, stateMachineFacets);
        }
        List<RawItem> rawItems = rows.items().stream().map(this::toRawItem).toList();

        Map<UUID, AdminPropertyDefinitionView> propertyViewsById = propertyViewsByIdForItemType(itemTypeId);
        List<AdminStateMachineView> stateMachines = schemaManager.getAdminSchema().items().stream()
                .filter(item -> item.id().equals(itemTypeId))
                .findFirst()
                .map(AdminItemDefinitionView::stateMachines)
                .orElse(null);
        var items = assembleProjectedItems(rawItems, propertyPathsByIdForItemType(itemTypeId), propertyViewsById, stateMachines, binaryBaseUrl,
                new RenderOptions(spec.links(), permissions, Boolean.TRUE.equals(spec.includePermissions()), Boolean.TRUE.equals(spec.includeStates()), spec.properties()));
        return new ProjectionResult(items, totalCount, facetedCount, facets, stateMachineFacets);
    }

    // --- Cross-type (trait/supertype-scoped) queries ---

    // Parallel to project(), not a refactor of it: the single-table path is proven and
    // read-performance-critical, and reshaping it to always route through an N-branch code path
    // risked destabilizing it for the (still overwhelmingly common) case of a type with no
    // descendants. EntityManagerImpl only calls this once a resolved type set actually has more
    // than one member.
    public ProjectionResult projectAcrossTypes(Set<UUID> itemTypeIds, CollectionProjectionSpec spec, String binaryBaseUrl) {
        return projectAcrossTypes(itemTypeIds, spec, binaryBaseUrl, RequestPermissionContext.forAdmin());
    }

    public ProjectionResult projectAcrossTypes(Set<UUID> itemTypeIds, CollectionProjectionSpec spec, String binaryBaseUrl, RequestPermissionContext permissions) {
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
        UUID representativeItemTypeId = branches.getFirst();

        Condition filter = toCondition(spec.filter(), representativeItemTypeId, permissions);
        List<TermsFilter> termsFilters = toTermsFilters(spec, representativeItemTypeId, permissions);
        Map<String, AdminPropertyDefinitionView> facetProperties = new LinkedHashMap<>();
        resolveRequestedFacets(spec, representativeItemTypeId, permissions)
                .forEach(field -> facetProperties.put(field, resolveProperty(representativeItemTypeId, sanitizeFieldName(field))));

        // No state-machine facets across types: a machine belongs to one item type, so naming one isn't
        // meaningful across an arbitrary branch set. Binary-field sorts are single-type only too.
        RegisterRows rows = registerStore.query(new ExplicitProjection(itemTypeIds, filter, toAccess(permissions), termsFilters,
                facetProperties.entrySet().stream().map(e -> new TermsFacet(e.getKey(), e.getValue().id())).toList(), List.of(),
                toSort(spec, representativeItemTypeId, branches, permissions, false),
                new Page(effectiveOffset(spec), effectiveLimit(spec)), toLinkSelection(spec.links()), toLinkAccess(permissions), true));

        Map<String, List<FacetBucket>> facets = facetProperties.isEmpty() ? null : new LinkedHashMap<>();
        facetProperties.forEach((field, property) -> facets.put(field, labelledTermsBuckets(rows.termsFacetCounts().get(field), property)));
        Map<String, List<FacetBucket>> stateMachineFacets = null;

        long totalCount = rows.counts().total();
        long facetedCount = rows.counts().faceted();
        if (facetedCount == 0) {
            return new ProjectionResult(List.of(), totalCount, 0, facets, stateMachineFacets);
        }
        List<RawItem> rawItems = rows.items().stream().map(this::toRawItem).toList();

        // Merged, not per-row-resolved: property ids are globally unique across the whole schema
        // (never scoped per type), so unioning each branch's own name lookup is a safe, unambiguous
        // merge -- see assembleProjectedItems' own comment on why an over-inclusive stateMachines
        // list is equally safe.
        Map<UUID, List<String>> mergedPropertyNames = new HashMap<>();
        Map<UUID, AdminPropertyDefinitionView> mergedPropertyViews = new HashMap<>();
        List<AdminStateMachineView> mergedStateMachines = new ArrayList<>();
        for (UUID branchId : branches) {
            mergedPropertyNames.putAll(propertyPathsByIdForItemType(branchId));
            mergedPropertyViews.putAll(propertyViewsByIdForItemType(branchId));
            schemaManager.getAdminSchema().items().stream()
                    .filter(item -> item.id().equals(branchId))
                    .findFirst()
                    .map(AdminItemDefinitionView::stateMachines)
                    .ifPresent(mergedStateMachines::addAll);
        }

        var items = assembleProjectedItems(rawItems, mergedPropertyNames, mergedPropertyViews,
                mergedStateMachines.isEmpty() ? null : mergedStateMachines, binaryBaseUrl,
                new RenderOptions(spec.links(), permissions, Boolean.TRUE.equals(spec.includePermissions()), Boolean.TRUE.equals(spec.includeStates()), spec.properties()));
        return new ProjectionResult(items, totalCount, facetedCount, facets, stateMachineFacets);
    }

    // Empty when the property isn't controlled-list-backed, or its list has no labels at all (see
    // ControlledListManager.getLabels).
    private ControlledListLabels labelsForFacetProperty(AdminPropertyDefinitionView property) {
        return property.controlledListId() == null ? new ControlledListLabels(property.type(), Map.of())
                : controlledListManager.getLabels(property.controlledListId(), property.type());
    }

    private String labelFor(String value, ControlledListLabels labels) {
        if (value == null) return null;
        String label = labels.labelFor(value);
        return label == null ? value : label;
    }

    private List<FacetBucket> labelledTermsBuckets(List<RegisterRows.ValueCount> counts, AdminPropertyDefinitionView property) {
        ControlledListLabels labels = labelsForFacetProperty(property);
        return counts.stream().map(c -> new FacetBucket(c.value(), labelFor(c.value(), labels), c.count())).toList();
    }

    // Each bucket's value is a state id, which a state predicate filters on; its label is the state's name.
    // Buckets are ordered by distance from START, then by name. Unreachable states come after
    // reachable ones, and the null bucket (items never in this machine) always comes last.
    private List<FacetBucket> stateMachineBuckets(List<RegisterRows.ValueCount> counts, UUID stateMachineId) {
        AdminStateMachineView machine = stateMachineView(stateMachineId);
        Map<UUID, String> stateNames = machine.states().stream()
                .collect(Collectors.toMap(AdminStateView::id, AdminStateView::name));
        Map<UUID, Integer> rankByStateId = rankStatesByDistanceFromStart(machine);
        Comparator<FacetBucket> byWorkflowPosition = Comparator
                .comparing((FacetBucket b) -> b.value() == null)
                .thenComparing(b -> b.value() == null ? Integer.MAX_VALUE
                        : rankByStateId.getOrDefault(UUID.fromString(b.value()), Integer.MAX_VALUE))
                .thenComparing(b -> b.label() == null ? "" : b.label(), String.CASE_INSENSITIVE_ORDER);
        return counts.stream()
                .map(c -> new FacetBucket(c.value(), c.value() == null ? null : stateNames.get(UUID.fromString(c.value())), c.count()))
                .sorted(byWorkflowPosition)
                .toList();
    }

    private AdminStateMachineView stateMachineView(UUID stateMachineId) {
        return schemaManager.getAdminSchema().items().stream()
                .flatMap(item -> Optional.ofNullable(item.stateMachines()).orElse(List.of()).stream())
                .filter(m -> m.id().equals(stateMachineId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown state machine: " + stateMachineId));
    }

    // BFS distance from the machine's START pseudostate, one hop per transition. A machine's states
    // form a graph, not a line (branches are normal, and re-entry after END is valid so cycles are
    // possible too) -- this is a best-effort rank, not a true total order, but it matches how the
    // diagram itself lays states out and needs no schema change to compute.
    private Map<UUID, Integer> rankStatesByDistanceFromStart(AdminStateMachineView machine) {
        Map<UUID, Integer> distanceByStateId = new HashMap<>();
        Optional<AdminStateView> start = machine.states().stream()
                .filter(s -> STATE_KIND_START.equals(s.kind()))
                .findFirst();
        if (start.isEmpty()) {
            return distanceByStateId;
        }
        Map<UUID, List<UUID>> outgoingByStateId = machine.states().stream()
                .collect(Collectors.toMap(AdminStateView::id,
                        s -> s.transitions().stream().map(AdminTransitionView::toStateId).toList()));
        Deque<UUID> frontier = new ArrayDeque<>();
        distanceByStateId.put(start.get().id(), 0);
        frontier.add(start.get().id());
        while (!frontier.isEmpty()) {
            UUID current = frontier.poll();
            int nextDistance = distanceByStateId.get(current) + 1;
            for (UUID next : outgoingByStateId.getOrDefault(current, List.of())) {
                distanceByStateId.computeIfAbsent(next, k -> {
                    frontier.add(k);
                    return nextDistance;
                });
            }
        }
        return distanceByStateId;
    }

    // The client's facet filters, one per property (a later filter on the same field replaces an
    // earlier one), each property checked readable. A filter selecting nothing (no values, no null)
    // narrows nothing, so it's dropped unresolved.
    private List<TermsFilter> toTermsFilters(CollectionProjectionSpec spec, UUID itemTypeId, RequestPermissionContext permissions) {
        if (spec.facetFilters() == null) return List.of();
        Map<UUID, TermsFilter> byProperty = new LinkedHashMap<>();
        for (FacetFilter facetFilter : spec.facetFilters()) {
            switch (facetFilter) {
                case TermsFacetFilter f -> {
                    List<String> values = f.values() != null ? f.values() : List.of();
                    if (values.isEmpty() && !f.includeNull()) continue;
                    UUID propertyId = resolvePropertyId(itemTypeId, sanitizeFieldName(f.field()));
                    requirePropertyReadableByAnyMarker(permissions, propertyId, f.field());
                    byProperty.put(propertyId, new TermsFilter(propertyId, values, f.includeNull()));
                }
                case RangeFacetFilter _ -> throw new UnsupportedOperationException("Range facet filters not yet supported");
                case DateRangeFacetFilter _ -> throw new UnsupportedOperationException("Date range facet filters not yet supported");
            }
        }
        return List.copyOf(byProperty.values());
    }

    public Optional<ProjectedItem> projectOne(UUID itemTypeId, UUID itemId, String binaryBaseUrl) {
        return projectOne(itemTypeId, itemId, binaryBaseUrl, null, RequestPermissionContext.forAdmin());
    }

    public Optional<ProjectedItem> projectOne(UUID itemTypeId, UUID itemId, String binaryBaseUrl,
                                               @Nullable Map<String, LinkProjectionSpec> requestedLinks) {
        return projectOne(itemTypeId, itemId, binaryBaseUrl, requestedLinks, RequestPermissionContext.forAdmin());
    }

    // Kept at "always include everything" -- unlike ProjectionSpec's HTTP-facing "default false"
    // policy (see its own comment), this overload has no spec to consult and predates
    // includePermissions/includeStates entirely; it's what every direct-Java caller (mostly tests)
    // already relied on. EntityManagerImpl.projectOne, the real HTTP-facing caller, goes through
    // the RenderOptions overload below instead, with real flags read off the caller's own spec.
    public Optional<ProjectedItem> projectOne(UUID itemTypeId, UUID itemId, String binaryBaseUrl,
                                               @Nullable Map<String, LinkProjectionSpec> requestedLinks,
                                               RequestPermissionContext permissions) {
        return projectOne(itemTypeId, itemId, binaryBaseUrl, new RenderOptions(requestedLinks, permissions, true, true, null));
    }

    // permissions here only filters this item's *links* (see toLinkAccess) -- the item's own
    // existence isn't gated by this method at all, deliberately: unlike a paginated collection,
    // there's no totalCount/pagination correctness that requires it to be baked into this query,
    // so the caller (EntityManagerImpl) checks PermissionService.canReadItem before ever calling
    // this, exactly like it already does for the type-level requireReadAccess check -- cheaper,
    // since a denied request never pays for fetching the item's own (possibly large) properties.
    public Optional<ProjectedItem> projectOne(UUID itemTypeId, UUID itemId, String binaryBaseUrl, RenderOptions options) {
        List<RawItem> rawItems = registerStore.query(ExplicitProjection.singleItem(itemTypeId, itemId,
                        toLinkSelection(options.requestedLinks()), toLinkAccess(options.permissions()))).items().stream()
                .map(this::toRawItem)
                .toList();

        if (rawItems.isEmpty()) {
            return Optional.empty();
        }
        Map<UUID, AdminPropertyDefinitionView> propertyViewsById = propertyViewsByIdForItemType(itemTypeId);
        List<AdminStateMachineView> stateMachines = schemaManager.getAdminSchema().items().stream()
                .filter(item -> item.id().equals(itemTypeId))
                .findFirst()
                .map(AdminItemDefinitionView::stateMachines)
                .orElse(null);
        return Optional.of(assembleProjectedItems(rawItems, propertyPathsByIdForItemType(itemTypeId), propertyViewsById, stateMachines, binaryBaseUrl,
                options).getFirst());
    }

    // --- Write side: staged at prepare (UNCOMMITTED), flipped/cleaned up at commit/abort ---
    // Updates are never in-place: prepare stages a whole new row: commit deletes the old
    // committed row for the same business id and flips the new one to COMMITTED.

    // binaryPropertyIds: the item type's BINARY properties, which go to register_binary_property
    // rather than the properties JSONB.
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
    // transition. Transition validity/guard enforcement lives in EntityManagerImpl.executeTransition.
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
                        parseJsonb(rs.getString(COL_PROPERTIES)), parseJsonb(rs.getString(COL_STATES)),
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
    public Map<UUID, Object> resolveMergedPropertiesById(UUID itemId, UUID itemTypeId, Map<UUID, Object> propertiesDiff) {
        Map<String, Object> currentById = currentPropertiesByIdString(itemId, itemTypeId);
        Map<String, Object> mergedById = propertiesDiff.isEmpty()
                ? currentById
                : mergeProperties(currentById, keysToStrings(propertiesDiff));
        return toUuidKeyedMap(mergedById);
    }

    /** This item's current committed properties, keyed by property id -- what NoOpMutationFilter compares an incoming diff against. */
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
        return parseJsonb(currentJson);
    }

    /** This link's current committed properties, keyed by property id -- the link counterpart to currentPropertiesById. */
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
        return parseJsonb(currentJson);
    }

    // Link counterpart to resolveMergedPropertiesById -- merges an update's property diff onto the
    // link's current committed properties, keyed by property id.
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

    public UUID stageLinkCreate(UUID linkId, UUID linkTypeId, RegisterLinkEndpoint endpointA, RegisterLinkEndpoint endpointB,
                                 Map<UUID, Object> properties, StagingContext staging) {
        UUID registerLinkId = insertLinkRow(linkId, linkTypeId, keysToStrings(properties), staging.transactionId(), RowAudit.created(staging));
        insertPerspectiveRows(registerLinkId, List.of(endpointA, endpointB), staging.transactionId());
        return registerLinkId;
    }

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
                .query((rs, _) -> parseJsonb(rs.getString(COL_PROPERTIES)))
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

    public void deleteLink(UUID linkId) {
        jdbcClient.sql("DELETE FROM register_link WHERE link_id = :linkId AND state = 'COMMITTED'")
                .param(PARAM_LINK_ID, linkId)
                .update();
    }

    // Markers are never staged (see LedgerRegisterCoordinatorImpl.prepare) -- unlike a create/
    // update, there's no new register_item/register_link row being built, just a plain
    // insert/delete against an already-COMMITTED one, applied directly at commit like a delete.
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

    // --- Mode-2 (field/capability-affecting) permission resolution: batched per-page marker
    // lookup + in-memory set arithmetic against RequestPermissionContext's grant maps. Never a
    // WHERE-clause concern (see toAccess's own comment on the mode split).

    // Marker id -> name, for ProjectedItem.markers -- display only, never a permission decision.
    // Reads authorization_marker, another partition's table: the engine's to look up (through the
    // authorization store), never RegisterStore's.
    private Map<UUID, String> resolveMarkerNames(Set<UUID> markerIds) {
        if (markerIds.isEmpty()) return Map.of();
        return jdbcClient.sql("SELECT id, name FROM authorization_marker WHERE id IN (:ids)")
                .param("ids", markerIds)
                .query((rs, _) -> Map.entry(rs.getObject("id", UUID.class), rs.getString("name")))
                .list().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    // Union of a grant map's values across the markers a row actually carries -- the core mode-2
    // computation, reused for property:read/write, link_property:read/write, and (for the
    // perspective-keyed maps) link:read/delete alike, just with a different (markerIds,
    // grantsByMarker) pair each time.
    private Set<UUID> unionGrantedIds(Set<UUID> markerIds, Map<UUID, Set<UUID>> grantsByMarker) {
        if (markerIds.isEmpty() || grantsByMarker.isEmpty()) return Set.of();
        Set<UUID> result = new HashSet<>();
        for (UUID markerId : markerIds) {
            Set<UUID> granted = grantsByMarker.get(markerId);
            if (granted != null) result.addAll(granted);
        }
        return result;
    }

    // Unlike filterPropertiesByReadGrant (gated by a specific item's own markers), filter/sort
    // resolution has no single item in play -- a query spans every instance of a type. So this asks
    // the coarser question "could this principal ever read this property, via *any* marker they
    // hold at all" rather than "can they read it on this one item." Rejecting a filter/sort target
    // this way can't leak which instances exist or what's on them (the response is still reduced to
    // whatever the real per-item checks allow); it only ever rejects a query shape the principal has
    // no conceivable use for, same spirit as resolvePropertyPath's own "unknown property" rejection
    // just above -- both are IllegalArgumentException, both map to 400 the same way.
    private boolean isPropertyReadableByAnyMarker(RequestPermissionContext permissions, UUID propertyId) {
        if (permissions.isAdmin()) return true;
        return permissions.propertyReadGrantsByMarker().values().stream().anyMatch(ids -> ids.contains(propertyId))
                || permissions.propertyWriteGrantsByMarker().values().stream().anyMatch(ids -> ids.contains(propertyId));
    }

    private void requirePropertyReadableByAnyMarker(RequestPermissionContext permissions, UUID propertyId, String propertyName) {
        if (!isPropertyReadableByAnyMarker(permissions, propertyId)) {
            throw new IllegalArgumentException("Not permitted to filter, sort, or facet on property: " + propertyName);
        }
    }

    // A write grant on a property implies read of it -- editing a value you can't see back is not a
    // coherent capability, and the admin UI already presents read as "implied by write". Callers
    // pass this merged (read UNION write) map into the read filter rather than the bare read map.
    // For binary properties there is no separate "download" grant -- read alone gates the bytes.
    private Map<UUID, Set<UUID>> readImpliedByWrite(Map<UUID, Set<UUID>> readGrants, Map<UUID, Set<UUID>> writeGrants) {
        Map<UUID, Set<UUID>> merged = new HashMap<>();
        readGrants.forEach((markerId, ids) -> merged.computeIfAbsent(markerId, k -> new HashSet<>()).addAll(ids));
        writeGrants.forEach((markerId, ids) -> merged.computeIfAbsent(markerId, k -> new HashSet<>()).addAll(ids));
        return merged;
    }

    // Filters a still-property-ID-keyed JSONB map down to what's readable, before namesForIds
    // resolves it -- redaction has to happen at the ID level, namesForIds has no concept of
    // permission. propertyReadGrantsByMarker == null means "don't filter at all" (admin).
    // Applies the same whether or not the caller named the property: an item whose markers don't
    // grant it simply comes back without it, so a response never reveals what a caller can't see.
    private Map<String, Object> filterPropertiesByReadGrant(Map<String, Object> propertiesById, Set<UUID> markerIds,
                                                              @Nullable Map<UUID, Set<UUID>> propertyReadGrantsByMarker) {
        if (propertyReadGrantsByMarker == null) return propertiesById;
        Set<UUID> readable = unionGrantedIds(markerIds, propertyReadGrantsByMarker);
        if (readable.isEmpty()) return Map.of();
        Map<String, Object> filtered = new HashMap<>();
        propertiesById.forEach((idString, value) -> {
            if (readable.contains(UUID.fromString(idString))) filtered.put(idString, value);
        });
        return filtered;
    }

    // edit is a nested tree mirroring the item type's own property structure -- see
    // ProjectedItemPermissions' own comment for the exact shape and why. delete is a plain
    // capability flag. Admin goes through the same buildEditTree walk as everyone else
    // (buildFullEditTree just never has to check a granted-id set to know the answer is "yes") --
    // no shortcut, so edit's shape never depends on who's asking.
    private ProjectedItemPermissions buildPermissions(RequestPermissionContext permissions, Set<UUID> markerIds,
                                                        Map<UUID, Set<UUID>> writeGrantsByMarker, Set<UUID> deleteGrantedMarkerIds,
                                                        PropertyRoot rootProperties,
                                                        Map<String, List<AdminItemLinkPerspectiveView>> linkPerspectives) {
        if (permissions.isAdmin()) {
            List<String> allPerspectiveNames = linkPerspectives.isEmpty() ? null : List.copyOf(linkPerspectives.keySet());
            return new ProjectedItemPermissions(buildFullEditTree(rootProperties), true, allPerspectiveNames);
        }
        Set<UUID> grantedIds = unionGrantedIds(markerIds, writeGrantsByMarker);
        Map<String, Object> edit = grantedIds.isEmpty() ? null : buildEditTree(rootProperties, grantedIds);
        boolean canDelete = !Collections.disjoint(markerIds, deleteGrantedMarkerIds);
        List<String> createLinks = creatableLinkPerspectiveNames(markerIds, permissions.linkPerspectiveCreateGrantsByMarker(), linkPerspectives);
        return new ProjectedItemPermissions(edit, canDelete, createLinks);
    }

    // Advisory only, like ProjectedItemState.startable -- names the perspectives this item's marker
    // set grants link:create on, without regard to whether any *specific* target would actually be
    // creatable through them (target readability can only be checked once a real target id is on the
    // table, at actual mutation time -- see ProjectedItemPermissions' own comment). A perspective
    // name appears once even if multiple AdminItemLinkPerspectiveView entries share it.
    private List<String> creatableLinkPerspectiveNames(Set<UUID> markerIds, Map<UUID, Set<UUID>> createGrantsByMarker,
                                                         Map<String, List<AdminItemLinkPerspectiveView>> linkPerspectives) {
        Set<UUID> grantedPerspectiveIds = unionGrantedIds(markerIds, createGrantsByMarker);
        if (grantedPerspectiveIds.isEmpty()) return null;
        List<String> names = new ArrayList<>();
        for (var entry : linkPerspectives.entrySet()) {
            if (entry.getValue().stream().anyMatch(p -> grantedPerspectiveIds.contains(p.id()))) {
                names.add(entry.getKey());
            }
        }
        return names.isEmpty() ? null : names;
    }

    // What sits at one level of the schema tree: leaf properties and the property groups beside
    // them. An item type's trait contributions are groups here (one per trait, named for it).
    private record PropertyRoot(List<AdminPropertyDefinitionView> properties, List<AdminPropertyGroupView> groups) {
        static final PropertyRoot EMPTY = new PropertyRoot(List.of(), List.of());
    }

    private Map<String, Object> buildEditTree(PropertyRoot root, Set<UUID> grantedIds) {
        return buildEditTree(root.properties(), root.groups(), grantedIds);
    }

    private Map<String, Object> buildFullEditTree(PropertyRoot root) {
        return buildFullEditTree(root.properties(), root.groups());
    }

    // Bottom-up: a node whose own scalar children are ALL granted collapses its "scalars" entry to
    // ["*"] rather than naming each one; a group with nothing writable anywhere beneath it is
    // omitted from "groups" entirely, same as a fully-uncovered node returns null and is omitted by
    // its own parent. Every node keeps the same {scalars, groups} shape regardless of how much of it
    // is granted. Advisory only: dynamic properties never appear here, and a group is only ever
    // structure -- it is listed because something inside it is writable, never as a grant of its own.
    private Map<String, Object> buildEditTree(List<AdminPropertyDefinitionView> properties, List<AdminPropertyGroupView> groups, Set<UUID> grantedIds) {
        List<String> grantedScalarNames = new ArrayList<>();
        Map<String, Object> groupChildren = new LinkedHashMap<>();

        for (AdminPropertyDefinitionView p : properties) {
            if (grantedIds.contains(p.id())) grantedScalarNames.add(p.name());
        }
        for (AdminPropertyGroupView g : groups) {
            Map<String, Object> childNode = buildEditTree(g.properties(), g.groups(), grantedIds);
            if (childNode != null) groupChildren.put(g.name(), childNode);
        }

        if (grantedScalarNames.isEmpty() && groupChildren.isEmpty()) return null;

        Map<String, Object> node = new LinkedHashMap<>();
        if (!grantedScalarNames.isEmpty()) {
            node.put("scalars", grantedScalarNames.size() == properties.size() ? List.of("*") : List.copyOf(grantedScalarNames));
        }
        if (!groupChildren.isEmpty()) {
            node.put("groups", groupChildren);
        }
        return node;
    }

    // Admin's counterpart to buildEditTree -- every scalar is granted by definition, so this
    // never needs a granted-id set, but keeps the identical {scalars, groups} shape (always "*",
    // never a per-name list) so a client can't tell which principal it's looking at from edit's
    // shape alone. Only returns null for a node with nothing at all, which real schema content
    // never produces.
    private Map<String, Object> buildFullEditTree(List<AdminPropertyDefinitionView> properties, List<AdminPropertyGroupView> groups) {
        Map<String, Object> groupChildren = new LinkedHashMap<>();
        for (AdminPropertyGroupView g : groups) {
            Map<String, Object> childNode = buildFullEditTree(g.properties(), g.groups());
            if (childNode != null) groupChildren.put(g.name(), childNode);
        }

        if (properties.isEmpty() && groupChildren.isEmpty()) return null;

        Map<String, Object> node = new LinkedHashMap<>();
        if (!properties.isEmpty()) node.put("scalars", List.of("*"));
        if (!groupChildren.isEmpty()) node.put("groups", groupChildren);
        return node;
    }

    private PropertyRoot rootPropertiesForItemTypeName(String itemTypeName) {
        return schemaManager.getAdminSchema().items().stream()
                .filter(item -> item.name().equals(itemTypeName))
                .findFirst()
                .map(item -> new PropertyRoot(item.properties(), item.groups()))
                .orElse(PropertyRoot.EMPTY);
    }

    private PropertyRoot rootPropertiesForItemType(UUID itemTypeId) {
        return schemaManager.getAdminSchema().items().stream()
                .filter(item -> item.id().equals(itemTypeId))
                .findFirst()
                .map(item -> new PropertyRoot(item.properties(), item.groups()))
                .orElse(PropertyRoot.EMPTY);
    }

    private Map<String, List<AdminItemLinkPerspectiveView>> linkPerspectivesForItemTypeName(String itemTypeName) {
        return schemaManager.getAdminSchema().items().stream()
                .filter(item -> item.name().equals(itemTypeName))
                .findFirst()
                .map(AdminItemDefinitionView::links)
                .orElse(Map.of());
    }

    private Map<String, List<AdminItemLinkPerspectiveView>> linkPerspectivesForItemType(UUID itemTypeId) {
        return schemaManager.getAdminSchema().items().stream()
                .filter(item -> item.id().equals(itemTypeId))
                .findFirst()
                .map(AdminItemDefinitionView::links)
                .orElse(Map.of());
    }

    private PropertyRoot rootPropertiesForLinkType(UUID linkTypeId) {
        return schemaManager.getAdminSchema().links().stream()
                .filter(link -> link.id().equals(linkTypeId))
                .findFirst()
                .map(link -> new PropertyRoot(link.properties(), link.groups()))
                .orElse(PropertyRoot.EMPTY);
    }

    // A link's own permissions differ from buildPermissions' shape in exactly one way: delete is
    // perspective-keyed (marker_grant_link_perspective.can_delete), not a flat marker set, since
    // link:delete is anchored to the source item's marker via the specific perspective traversed --
    // same reasoning as link:read's own filtering above. Edit (link_property:write) uses the same
    // tree shape as buildPermissions, since link properties can sit inside groups too.
    private ProjectedItemPermissions buildLinkPermissions(RequestPermissionContext permissions, Set<UUID> sourceMarkerIds, UUID perspectiveId,
                                                            PropertyRoot linkRootProperties) {
        if (permissions.isAdmin()) {
            return new ProjectedItemPermissions(buildFullEditTree(linkRootProperties), true, null);
        }
        Set<UUID> grantedIds = unionGrantedIds(sourceMarkerIds, permissions.linkPropertyWriteGrantsByMarker());
        Map<String, Object> edit = grantedIds.isEmpty() ? null : buildEditTree(linkRootProperties, grantedIds);
        boolean canDelete = unionGrantedIds(sourceMarkerIds, permissions.linkPerspectiveDeleteGrantsByMarker()).contains(perspectiveId);
        return new ProjectedItemPermissions(edit, canDelete, null);
    }

    // FactResolver's own own item-traversal method -- see that interface's class comment for why
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

    // Named to match LedgerPartitionManager.abort() -- this and that together are what
    // LedgerRegisterCoordinator.abort() calls to discard a prepared-but-not-yet-committed
    // transaction's staged rows on both sides.
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

    // Returns each leaf property's full name path from root to leaf, not just its own name --
    // storage is flat (a leaf's own id), but two leaves nested under different object properties
    // can share a name (e.g. dimensions.length vs packagingDimensions.length), so only the full
    // path is unambiguous. A group's own id never appears here: nothing is ever stored
    // under a group's id, only under each of its leaf properties'. A trait's contributions sit
    // under a path segment named for the trait.
    private Map<UUID, List<String>> propertyPathsByIdForItemType(UUID itemTypeId) {
        return schemaManager.getAdminSchema().items().stream()
                .filter(item -> item.id().equals(itemTypeId))
                .findFirst()
                .map(item -> propertyPaths(item.properties(), item.groups()))
                .orElse(Map.of());
    }

    // Sibling to propertyPathsByIdForItemType/propertyPaths, collecting the view objects themselves
    // instead of their paths -- restrictToRequestedProperties needs these to recognize a request for
    // a BINARY leaf's *registered dynamic path* (e.g. "File.content.metadata.extracted.exif.Make"),
    // which requires checking that leaf's own AdminBinaryPropertyDefinitionView.dynamicFields, not
    // just its static path.
    private Map<UUID, AdminPropertyDefinitionView> propertyViewsByIdForItemType(UUID itemTypeId) {
        return schemaManager.getAdminSchema().items().stream()
                .filter(item -> item.id().equals(itemTypeId))
                .findFirst()
                .map(item -> propertyViewsById(item.properties(), item.groups()))
                .orElse(Map.of());
    }

    private Map<UUID, AdminPropertyDefinitionView> propertyViewsById(List<AdminPropertyDefinitionView> properties, List<AdminPropertyGroupView> groups) {
        Map<UUID, AdminPropertyDefinitionView> result = new HashMap<>();
        for (AdminPropertyDefinitionView p : properties) {
            result.put(p.id(), p);
        }
        for (AdminPropertyGroupView g : groups) {
            result.putAll(propertyViewsById(g.properties(), g.groups()));
        }
        return result;
    }

    // Restricts a full property-path map down to just the caller-requested subset (dot-notation
    // paths matching the same names ItemDefinitionView.projectableFields already exposes, e.g.
    // "File.name") -- null means unfiltered (today's behavior); non-null, even empty, keeps only
    // matches. Applied once, up front, rather than inside assembleProjectedItem: namesForIds
    // already silently skips any propertyId absent from the map handed to it, so trimming the map
    // here is sufficient to restrict the final response with no other change to assembly. Only ever
    // applied to a top-level item's own property map -- never to a linked item's (see
    // ProjectionSpec.properties()'s own comment on why that's out of scope for now).
    //
    // A match is either exact, or the requested string names a *prefix* of the leaf's path (e.g.
    // requesting "dimensions" keeps a leaf whose path is "dimensions.widthCm") -- unambiguous the
    // same way a bare group name already is elsewhere: a group is never itself a leaf, so there's no
    // competing "return this as a scalar value" reading to collide with. This is also what makes a
    // bare BINARY leaf's own name (e.g. "File.content") work as a plain exact match -- it needs no
    // special-casing, since a binary's path is just another leaf path like any other.
    //
    // A BINARY leaf is *also* kept when a request names one of its own registered dynamic paths
    // (e.g. "File.content.metadata.extracted.exif.Make", checked against
    // AdminBinaryPropertyDefinitionView.dynamicFields) -- the reverse direction from the prefix rule
    // above, since here the *request* extends past the leaf's own static path, not the other way
    // round. Assembly (mergeReadableBinaryProperties/assembleBinaryValue) still decides separately
    // whether that means the whole binary or a slice of it -- this method only decides inclusion.
    private Map<UUID, List<String>> restrictToRequestedProperties(Map<UUID, List<String>> ownPropertyNames,
                                                                     Map<UUID, AdminPropertyDefinitionView> propertyViewsById,
                                                                     @Nullable List<String> requestedProperties) {
        if (requestedProperties == null) {
            return ownPropertyNames;
        }
        Set<String> requested = Set.copyOf(requestedProperties);
        Map<UUID, List<String>> filtered = new HashMap<>();
        ownPropertyNames.forEach((id, path) -> {
            String joined = String.join(".", path);
            boolean matches = requested.stream().anyMatch(r -> joined.equals(r) || joined.startsWith(r + "."))
                    || requestsRegisteredDynamicPathOf(requested, joined, propertyViewsById.get(id));
            if (matches) {
                filtered.put(id, path);
            }
        });
        return filtered;
    }

    // A requested path into a binary's own fields can name an exact registered leaf (e.g.
    // "...metadata.exif.Make"), or a *prefix* of one or more registered leaves (e.g. "...metadata"
    // alone, matching every leaf currently registered under that bucket) -- the same
    // bare-prefix-means-everything-beneath rule restrictToRequestedProperties already applies to
    // ordinary static paths, just checked against the registry instead of the static schema tree,
    // since a dynamically-discovered branch like "metadata.exif" has no AdminPropertyGroupView of its
    // own. getAtPath/putAtPath in slicedMetadata already handle returning a whole intermediate
    // sub-object correctly once this method includes the property at all -- this is purely the
    // inclusion-gate half of the fix. No special marker segment needed (there's no wrapper key): any
    // path continuing past the leaf's own name is checked directly against the registry's own
    // recorded paths, whether it starts with "metadata", "calculated", "hash", or a bare "length"/
    // "mimeType".
    private boolean requestsRegisteredDynamicPathOf(Set<String> requested, String binaryLeafPath, @Nullable AdminPropertyDefinitionView property) {
        if (!(property instanceof AdminBinaryPropertyDefinitionView binaryProperty) || binaryProperty.dynamicFields().isEmpty()) {
            return false;
        }
        String prefix = binaryLeafPath + ".";
        return requested.stream().anyMatch(r -> r.startsWith(prefix)
                && binaryProperty.dynamicFields().stream().anyMatch(f -> {
                    String suffix = r.substring(prefix.length());
                    return f.path().equals(suffix) || f.path().startsWith(suffix + ".");
                }));
    }

    private Map<UUID, List<String>> propertyPathsByIdForLinkType(UUID linkTypeId) {
        return schemaManager.getAdminSchema().links().stream()
                .filter(link -> link.id().equals(linkTypeId))
                .findFirst()
                .map(link -> propertyPaths(link.properties(), link.groups()))
                .orElse(Map.of());
    }

    // Sibling to propertyPathsByIdForLinkType, same reasoning as propertyViewsByIdForItemType's own
    // comment -- fetchPropertiesByRegisterItemId needs the views themselves (not just paths) to
    // resolve controlled-list labels for a link's own properties.
    private Map<UUID, AdminPropertyDefinitionView> propertyViewsByIdForLinkType(UUID linkTypeId) {
        return schemaManager.getAdminSchema().links().stream()
                .filter(link -> link.id().equals(linkTypeId))
                .findFirst()
                .map(link -> propertyViewsById(link.properties(), link.groups()))
                .orElse(Map.of());
    }

    private Map<UUID, List<String>> propertyPaths(List<AdminPropertyDefinitionView> properties, List<AdminPropertyGroupView> groups) {
        Map<UUID, List<String>> result = new HashMap<>();
        for (AdminPropertyDefinitionView p : properties) {
            result.put(p.id(), List.of(p.name()));
        }
        for (AdminPropertyGroupView g : groups) {
            propertyPaths(g.properties(), g.groups()).forEach((id, childPath) -> {
                List<String> fullPath = new ArrayList<>();
                fullPath.add(g.name());
                fullPath.addAll(childPath);
                result.put(id, fullPath);
            });
        }
        return result;
    }

    // The inverse lookup -- a client-supplied property *name* (in a filter, sort field, or facet
    // field) has to resolve to the id the register actually stores before it can be used in SQL.
    // This also gives predicate/sort/facet validation for free: an unknown name now fails clearly
    // here instead of silently generating a condition that matches nothing.
    //
    // propertyName may be a dot-separated path (e.g. "dimensions.length") reaching into one or more
    // property groups -- mirrors the write-side walk in
    // MutationRequestProcessor.resolveGroupValue, which resolves each segment against the
    // *containing* object property's own children rather than a global name index. That scoping is
    // required, not just mirrored for consistency: propertyPaths' own comment notes two leaves
    // nested under different groups can share a name (dimensions.length vs
    // packagingDimensions.length), so only the full path is unambiguous.
    // A property path resolves either to an ordinary schema property (the common case) or, when the
    // path continues past a BINARY leaf, to that leaf plus the path within its own fields document
    // (e.g. "File.content.length" -> binaryFieldPath ["length"], "File.content.metadata.exif.Make"
    // -> ["metadata", "exif", "Make"]) and the PropertyType the binary dynamic-property registry
    // recorded for that exact path (see AdminBinaryPropertyDefinitionView.dynamicFields, design-notes
    // section 10) -- that path isn't schema structure (a binary's own facts need no grants of their
    // own, see design-notes section 8), so it can't be represented as further properties/groups, and
    // has no schema_property row of its own to read a type off the ordinary way. "id"/"url" behave
    // like any other unregistered path here -- never resolvable this way, same as before ("id"/"url"
    // are resolved only via assembleBinaryValue's own projection response, not sort/filter/facet).
    // binaryFieldPath/binaryFieldType are empty/null for the ordinary case.
    private record PropertyPathResolution(AdminPropertyDefinitionView property, List<String> binaryFieldPath, @Nullable PropertyType binaryFieldType) {
        static PropertyPathResolution ofProperty(AdminPropertyDefinitionView property) {
            return new PropertyPathResolution(property, List.of(), null);
        }
    }

    private UUID resolvePropertyId(UUID itemTypeId, String propertyName) {
        return resolveProperty(itemTypeId, propertyName).id();
    }

    // Ordinary-property resolution, preserved for every existing caller (filter, facet, and the
    // cross-type sort path) -- none of them resolve a binary-attribute path yet, so one is reported
    // as a clear, explicit error here rather than silently mishandled the way it would have been
    // before path resolution knew about BINARY leaves at all.
    private AdminPropertyDefinitionView resolveProperty(UUID itemTypeId, String propertyName) {
        PropertyPathResolution resolution = resolvePropertyPath(itemTypeId, propertyName);
        if (!resolution.binaryFieldPath().isEmpty()) {
            throw new IllegalArgumentException(
                    "'" + propertyName + "' names a binary content attribute, not supported in this context");
        }
        return resolution.property();
    }

    private PropertyPathResolution resolvePropertyPath(UUID itemTypeId, String propertyName) {
        PropertyRoot root = schemaManager.getAdminSchema().items().stream()
                .filter(item -> item.id().equals(itemTypeId))
                .findFirst()
                .map(item -> new PropertyRoot(item.properties(), item.groups()))
                .orElse(PropertyRoot.EMPTY);
        return resolvePropertyPath(root.properties(), root.groups(), propertyName.split("\\."), propertyName);
    }

    private PropertyPathResolution resolvePropertyPath(List<AdminPropertyDefinitionView> properties, List<AdminPropertyGroupView> groups,
                                                        String[] pathSegments, String fullPath) {
        boolean isLastSegment = pathSegments.length == 1;
        if (isLastSegment) {
            return properties.stream()
                    .filter(p -> p.name().equals(pathSegments[0]))
                    .findFirst()
                    .map(PropertyPathResolution::ofProperty)
                    .orElseThrow(() -> new IllegalArgumentException(
                            groups.stream().anyMatch(g -> g.name().equals(pathSegments[0]))
                                    ? "Property is a group, not a leaf value: " + fullPath
                                    : "Unknown property: " + fullPath));
        }
        // A path continuing past a BINARY leaf addresses one of its own fields, not further schema
        // structure -- checked before falling through to the group lookup below. No special marker
        // segment is required (there's no wrapper key): the whole remainder is looked up directly
        // against the registry's own recorded paths -- see AdminBinaryPropertyDefinitionView's own
        // comment. "id"/"url" simply never match anything there (they're resolved only via
        // assembleBinaryValue's projection response, never through the registry), so they still fall
        // through to the same "unknown attribute" error as any other unregistered path.
        Optional<AdminPropertyDefinitionView> binaryLeaf = properties.stream()
                .filter(p -> p.name().equals(pathSegments[0]) && p.type() == PropertyType.BINARY)
                .findFirst();
        if (binaryLeaf.isPresent()) {
            List<String> rest = List.of(Arrays.copyOfRange(pathSegments, 1, pathSegments.length));
            PropertyType binaryFieldType = dynamicFieldType((AdminBinaryPropertyDefinitionView) binaryLeaf.get(), rest);
            if (binaryFieldType == null) {
                throw new IllegalArgumentException("Unknown binary content attribute: " + fullPath);
            }
            return new PropertyPathResolution(binaryLeaf.get(), rest, binaryFieldType);
        }
        AdminPropertyGroupView group = groups.stream()
                .filter(g -> g.name().equals(pathSegments[0]))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown property: " + fullPath));
        return resolvePropertyPath(group.properties(), group.groups(), Arrays.copyOfRange(pathSegments, 1, pathSegments.length), fullPath);
    }

    // null (not found) rather than throwing -- the caller already has a clearer, fullPath-specific
    // error message to raise for that case.
    private PropertyType dynamicFieldType(AdminBinaryPropertyDefinitionView binaryLeaf, List<String> binaryFieldPath) {
        String joined = String.join(".", binaryFieldPath);
        return binaryLeaf.dynamicFields().stream()
                .filter(f -> f.path().equals(joined))
                .map(DynamicFieldView::type)
                .findFirst()
                .orElse(null);
    }

    // The item's current state id per state machine it is participating in (empty for machines it
    // isn't in). Reads the committed register row's states JSONB, shape
    // { "<stateMachineId>": { "currentStateId": "<stateId>" } }.
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
        Map<String, Object> byId = parseJsonb(statesJson);
        Map<UUID, UUID> result = new HashMap<>();
        byId.forEach((smId, entry) -> {
            if (entry instanceof Map<?, ?> m && m.get(STATE_CURRENT_STATE_ID) != null) {
                result.put(UUID.fromString(smId), UUID.fromString(m.get(STATE_CURRENT_STATE_ID).toString()));
            }
        });
        return result;
    }

    // Converts an id-keyed properties map (as read straight from the register's JSONB, always
    // flat -- storage never nests) into the nested name-keyed map a client actually wants to see,
    // by walking each stored leaf's full path and inserting it at the right depth, creating
    // intermediate maps as needed. A key that no longer resolves (the property was deleted from
    // the schema after this row was written) is silently dropped, same as before. The unchecked
    // cast is safe: only a group's name ever occupies a non-final path segment, and a
    // group's own id is never a storage key (see propertyPaths), so a segment can't be both an
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
            cursor.put(path.getLast(), value);
        });
        return byName;
    }

    // Same walk-and-create-intermediate-maps merge as namesForIds' own inner loop, extracted for
    // a single (already-resolved) path/value pair -- used by the binary-property merge, which
    // resolves its path from a different source (binaryPropsByItem, not the raw jsonb blob) but
    // still needs to land at the identical nested location.
    @SuppressWarnings("unchecked")
    private void putAtPath(Map<String, Object> target, List<String> path, Object value) {
        Map<String, Object> cursor = target;
        for (int i = 0; i < path.size() - 1; i++) {
            cursor = (Map<String, Object>) cursor.computeIfAbsent(path.get(i), k -> new HashMap<String, Object>());
        }
        cursor.put(path.getLast(), value);
    }

    // Projection rule (4): resolved once per request against whatever properties the caller is
    // actually about to receive (ownPropertyNames, already restricted by restrictToRequestedProperties
    // where applicable) rather than per row -- a controlled-list label lookup is a real query, and a
    // page of results shares the same schema, so paying for it once per property id here (not once
    // per property id per row) is what keeps this from becoming an N+1. Only properties that are both
    // controlled-list-backed AND whose list actually has labels (ControlledListManager.getLabels
    // returns non-empty) end up with an entry -- an absent entry means applyControlledListLabels
    // leaves that property's value exactly as namesForIds already produced it.
    private Map<UUID, ControlledListLabels> resolveControlledListLabels(
            Map<UUID, List<String>> ownPropertyNames, Map<UUID, AdminPropertyDefinitionView> propertyViewsById) {
        Map<UUID, ControlledListLabels> result = new HashMap<>();
        for (UUID propertyId : ownPropertyNames.keySet()) {
            AdminPropertyDefinitionView view = propertyViewsById.get(propertyId);
            if (view == null || view.controlledListId() == null) continue;
            ControlledListLabels labels = controlledListManager.getLabels(view.controlledListId(), view.type());
            if (!labels.isEmpty()) {
                result.put(propertyId, labels);
            }
        }
        return result;
    }

    // Walks only the (typically tiny, or empty) set of properties resolveControlledListLabels found
    // to actually need wrapping, overwriting each leaf namesForIds already placed -- never creates a
    // new intermediate map (getAtPath returning non-null already proves the path exists), and skips a
    // property whose value is absent from this particular item (getAtPath returns null) rather than
    // writing one in.
    private void applyControlledListLabels(Map<String, Object> props, Map<UUID, List<String>> idToPath,
                                             Map<UUID, ControlledListLabels> labelsByPropertyId) {
        for (var entry : labelsByPropertyId.entrySet()) {
            List<String> path = idToPath.get(entry.getKey());
            if (path == null) continue;
            Object current = getAtPath(props, path);
            if (current != null) {
                putAtPath(props, path, wrapControlledListValue(current, entry.getValue()));
            }
        }
    }

    // A SET/LIST-cardinality leaf stores a List<Object> rather than a single scalar -- each element
    // is wrapped independently, though validateEntries' all-or-nothing labeling rule (rule 2 of the
    // controlled-list design) means every element of a given property's list either has a label or
    // none do in practice.
    private Object wrapControlledListValue(Object rawValue, ControlledListLabels labels) {
        if (rawValue instanceof List<?> list) {
            return list.stream().map(v -> wrapControlledListScalar(v, labels)).toList();
        }
        return wrapControlledListScalar(rawValue, labels);
    }

    private Object wrapControlledListScalar(Object value, ControlledListLabels labels) {
        if (value == null) return null;
        String label = labels.labelFor(value);
        return label == null ? value : Map.of(COL_FACET_VALUE, value, "label", label);
    }

    private record AssembledBinary(UUID propertyId, Object value) {}

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
                LOG.info("Display label pattern \"{}\" failed to evaluate for item {}: {}", pattern, itemId, e.getMessage());
            }
        }
        // No pattern anywhere in the chain, evaluation failed, or the cleaned result was empty --
        // a deliberate "fail loud" signal (the item's own id) that this type has no usable display
        // label configured, rather than silently guessing from a title/name-ish property.
        return itemId.toString();
    }

    // The client's requested links as perspective ids. null: every direct link, not nested. Empty: none.
    // Otherwise the named perspectives -- a name means every perspective with that name, since a name is
    // only unique within an item type -- nested where a perspective's own spec names further links.
    private ExplicitProjection.LinkSelection toLinkSelection(@Nullable Map<String, LinkProjectionSpec> requestedLinks) {
        if (requestedLinks == null) return ExplicitProjection.LinkSelection.ALL_DIRECT;
        if (requestedLinks.isEmpty()) return ExplicitProjection.LinkSelection.NONE;
        Map<String, Set<UUID>> perspectiveIdsByName = new HashMap<>();
        perspectiveNamesById().forEach((id, name) -> perspectiveIdsByName.computeIfAbsent(name, k -> new HashSet<>()).add(id));
        Set<UUID> perspectiveIds = new HashSet<>();
        Map<UUID, ExplicitProjection.LinkSelection> nested = new HashMap<>();
        requestedLinks.forEach((name, spec) -> {
            Set<UUID> named = perspectiveIdsByName.getOrDefault(name, Set.of());
            perspectiveIds.addAll(named);
            Map<String, LinkProjectionSpec> childLinks = spec != null ? spec.links() : null;
            if (childLinks != null && !childLinks.isEmpty()) {
                ExplicitProjection.LinkSelection child = toLinkSelection(childLinks);
                named.forEach(id -> nested.put(id, child));
            }
        });
        return new ExplicitProjection.LinkSelection(perspectiveIds, nested);
    }

    // A link is visible only if the caller may read it through its source's markers, and may read the
    // target's item type and the target itself; otherwise it's left out as if absent, whether or not the
    // client named its perspective.
    private static ExplicitProjection.LinkAccess toLinkAccess(RequestPermissionContext permissions) {
        return permissions.isAdmin() ? ExplicitProjection.LinkAccess.Unrestricted.INSTANCE
                : new ExplicitProjection.LinkAccess.Granted(permissions.linkPerspectiveReadGrantsByMarker(),
                        permissions.readableItemTypeIds(), permissions.grantedItemReadMarkerIds());
    }

    private Map<UUID, String> perspectiveNamesById() {
        Map<UUID, String> names = new HashMap<>();
        schemaManager.getAdminSchema().items().forEach(item -> Optional.ofNullable(item.links()).orElse(Map.of())
                .forEach((name, perspectives) -> perspectives.forEach(perspective -> names.put(perspective.id(), name))));
        return names;
    }

    // Every binary id attached to these rows or, through their links, to any target at any depth.
    private Map<UUID, BinaryPropertyObject> binaryObjects(List<RegisterRows.ItemRow> rows) {
        Set<UUID> binaryIds = new HashSet<>();
        collectBinaryIds(rows, binaryIds);
        return binaryIds.isEmpty() ? Map.of() : binaryPartitionManager.getBinaryProperties(binaryIds);
    }

    private void collectBinaryIds(List<RegisterRows.ItemRow> rows, Set<UUID> into) {
        for (RegisterRows.ItemRow row : rows) {
            into.addAll(row.binaryIdsByProperty().values());
            row.linksByPerspective().values().forEach(links -> collectBinaryIds(links.stream().map(RegisterRows.LinkRow::target).toList(), into));
        }
    }

    // A row's binary values, keyed by property id and placed via ownPropertyNames. requestedProperties
    // is null for a linked item, which always returns every field unsliced (see ProjectionSpec).
    private List<AssembledBinary> assembledBinaries(Map<UUID, UUID> binaryIdsByProperty, Map<UUID, BinaryPropertyObject> binaryObjects,
                                                    String binaryBaseUrl, Map<UUID, List<String>> ownPropertyNames,
                                                    @Nullable List<String> requestedProperties) {
        List<AssembledBinary> result = new ArrayList<>();
        binaryIdsByProperty.forEach((propertyId, binaryId) -> {
            BinaryPropertyObject obj = binaryObjects.get(binaryId);
            if (obj != null) {
                result.add(new AssembledBinary(propertyId, assembleBinaryValue(obj, binaryBaseUrl, ownPropertyNames.get(propertyId), requestedProperties)));
            }
        });
        return result;
    }

    // What assembling a tree of links needs, resolved once per request. itemReadGrants/linkReadGrants
    // are null for an admin (nothing filtered). The two maps cache each item or link type's property
    // paths and controlled-list labels across the tree.
    private record LinkAssembly(RequestPermissionContext permissions, boolean includePermissions, String binaryBaseUrl,
                                Map<UUID, BinaryPropertyObject> binaryObjects, Map<String, String> displayLabelPatterns,
                                Map<UUID, String> perspectiveNamesById,
                                @Nullable Map<UUID, Set<UUID>> itemReadGrants, @Nullable Map<UUID, Set<UUID>> linkReadGrants,
                                Map<UUID, TypeProperties> itemTypes, Map<UUID, TypeProperties> linkTypes) {}

    private record TypeProperties(Map<UUID, List<String>> paths, Map<UUID, ControlledListLabels> labels) {}

    private TypeProperties itemTypeProperties(UUID itemTypeId, LinkAssembly ctx) {
        return ctx.itemTypes().computeIfAbsent(itemTypeId, id -> {
            Map<UUID, List<String>> paths = propertyPathsByIdForItemType(id);
            return new TypeProperties(paths, resolveControlledListLabels(paths, propertyViewsByIdForItemType(id)));
        });
    }

    private TypeProperties linkTypeProperties(UUID linkTypeId, LinkAssembly ctx) {
        return ctx.linkTypes().computeIfAbsent(linkTypeId, id -> {
            Map<UUID, List<String>> paths = propertyPathsByIdForLinkType(id);
            return new TypeProperties(paths, resolveControlledListLabels(paths, propertyViewsByIdForLinkType(id)));
        });
    }

    // One source item's links, by perspective name. A link whose perspective or target type is no longer
    // in the schema is left out.
    private Map<String, List<ProjectedLink>> assembleLinks(Set<UUID> sourceMarkerIds, Map<UUID, List<RegisterRows.LinkRow>> linksByPerspective,
                                                           LinkAssembly ctx) {
        Map<String, List<ProjectedLink>> result = new HashMap<>();
        linksByPerspective.forEach((perspectiveId, links) -> {
            String perspectiveName = ctx.perspectiveNamesById().get(perspectiveId);
            if (perspectiveName == null) return;
            for (RegisterRows.LinkRow link : links) {
                ProjectedLink projected = toProjectedLink(link, sourceMarkerIds, ctx);
                if (projected != null) result.computeIfAbsent(perspectiveName, k -> new ArrayList<>()).add(projected);
            }
        });
        return result;
    }

    // The linked item's own properties are gated by property:read through its own markers (it's still
    // an item); the link's properties by link_property:read through the source item's markers, since a
    // link has no markers of its own. A linked item's states and markers aren't returned.
    private @Nullable ProjectedLink toProjectedLink(RegisterRows.LinkRow link, Set<UUID> sourceMarkerIds, LinkAssembly ctx) {
        RegisterRows.ItemRow target = link.target();
        String targetTypeName = itemTypeNameOrNull(target.itemTypeId());
        if (targetTypeName == null) return null;

        TypeProperties itemType = itemTypeProperties(target.itemTypeId(), ctx);
        Map<String, Object> targetProperties = namesForIds(
                filterPropertiesByReadGrant(keysToStrings(target.properties()), target.markerIds(), ctx.itemReadGrants()), itemType.paths());
        applyControlledListLabels(targetProperties, itemType.paths(), itemType.labels());
        mergeReadableBinaryProperties(targetProperties, target.registerItemId(), target.markerIds(),
                Map.of(target.registerItemId(), assembledBinaries(target.binaryIdsByProperty(), ctx.binaryObjects(), ctx.binaryBaseUrl(),
                        itemType.paths(), null)),
                ctx.itemReadGrants(), itemType.paths());

        TypeProperties linkType = linkTypeProperties(link.linkTypeId(), ctx);
        Map<String, Object> linkProperties = namesForIds(
                filterPropertiesByReadGrant(keysToStrings(link.properties()), sourceMarkerIds, ctx.linkReadGrants()), linkType.paths());
        applyControlledListLabels(linkProperties, linkType.paths(), linkType.labels());

        RequestPermissionContext permissions = ctx.permissions();
        ProjectedItem targetItem = new ProjectedItem(
                target.itemId(),
                targetTypeName,
                targetProperties,
                assembleLinks(target.markerIds(), target.linksByPerspective(), ctx),
                null,
                ctx.includePermissions()
                        ? buildPermissions(permissions, target.markerIds(),
                                permissions.propertyWriteGrantsByMarker(), permissions.itemDeleteGrantedMarkerIds(),
                                rootPropertiesForItemType(target.itemTypeId()), linkPerspectivesForItemType(target.itemTypeId()))
                        : null,
                computeDisplayLabel(target.itemId(), targetProperties, ctx.displayLabelPatterns().get(targetTypeName)),
                null, // markers: not populated for a linked item yet -- see ProjectedItem's own comment
                // createdBy/updatedBy left null -- see assembleProjectedItem's own comment.
                target.audit().createdDate(), target.audit().createdBy(), null, target.audit().updatedDate(), target.audit().updatedBy(), null);
        return new ProjectedLink(
                link.linkId(),
                linkProperties,
                targetItem,
                ctx.includePermissions()
                        ? buildLinkPermissions(permissions, sourceMarkerIds, link.perspectiveId(), rootPropertiesForLinkType(link.linkTypeId()))
                        : null,
                link.audit().createdDate(), link.audit().createdBy(), null, link.audit().updatedDate(), link.audit().updatedBy(), null);
    }

    // ownPropertyNames/stateMachines are pre-resolved by the caller rather than looked up here from
    // a single itemTypeId -- the single-table project()/projectOne() resolve them from their one
    // known type; projectAcrossTypes resolves a merged view across every branch instead (property
    // ids are globally unique across the whole schema, so a plain union of each branch's own
    // lookup is a safe, unambiguous merge -- an over-inclusive stateMachines list is likewise safe,
    // since buildProjectedItemStates already skips any machine a given row's states don't mention).
    // RenderOptions: what the caller asked to see, shared by project(), projectAcrossTypes() and
    // projectOne(). requestedProperties is carried through unprocessed (not just as the already-filtered
    // ownPropertyNames map) because slicing a BINARY leaf's metadata needs the caller's *exact*
    // requested strings -- restrictToRequestedProperties already decided *whether* to include the
    // leaf at all, but not which of its dynamic paths (if any) to narrow the response to.
    public record RenderOptions(@Nullable Map<String, LinkProjectionSpec> requestedLinks, RequestPermissionContext permissions,
                                  boolean includePermissions, boolean includeStates, @Nullable List<String> requestedProperties) {}

    // allPropertyNames is every property path of the item type(s), unrestricted: the response's
    // properties are narrowed to options.requestedProperties() here, but each display label is
    // computed from all of the item's readable properties (see assembleProjectedItem).
    private List<ProjectedItem> assembleProjectedItems(List<RawItem> rawItems, Map<UUID, List<String>> allPropertyNames,
                                                         Map<UUID, AdminPropertyDefinitionView> propertyViewsById,
                                                         List<AdminStateMachineView> stateMachines, String binaryBaseUrl,
                                                         RenderOptions options) {
        // Nothing to assemble -- and every batch fetch below binds `... IN (:ids)`, which Postgres
        // rejects as a syntax error when the list is empty. A page whose offset lands past the last
        // row (offset >= totalCount) reaches here with an empty list; it must come back as [], not 500.
        if (rawItems.isEmpty()) return List.of();
        Map<UUID, List<String>> ownPropertyNames = restrictToRequestedProperties(allPropertyNames, propertyViewsById, options.requestedProperties());
        RequestPermissionContext permissions = options.permissions();
        boolean includePermissions = options.includePermissions();
        Map<String, String> displayLabelPatterns = resolveEffectiveDisplayLabelPatterns();
        Map<UUID, Set<UUID>> markerIdsByRegisterItemId = rawItems.stream()
                .collect(Collectors.toMap(RawItem::registerItemId, raw -> raw.row().markerIds(), (a, b) -> a));
        // Names for every marker we might surface on a projected item: all of them for an admin,
        // or just the caller's item:read-granted subset for everyone else. A non-admin only ever
        // sees an item because one of its markers is granted (toAccess), so a
        // visible item's disclosable list is non-empty in practice.
        Set<UUID> markerIdsToName = permissions.isAdmin()
                ? markerIdsByRegisterItemId.values().stream().flatMap(Set::stream).collect(Collectors.toSet())
                : permissions.grantedItemReadMarkerIds();
        Map<UUID, String> markerNamesById = resolveMarkerNames(markerIdsToName);

        // Every binary attached anywhere in the result, the links' targets included, fetched at once.
        Map<UUID, BinaryPropertyObject> binaryObjects = binaryObjects(rawItems.stream().map(RawItem::row).toList());
        Map<UUID, List<AssembledBinary>> binaryPropsByItem = new HashMap<>();
        rawItems.forEach(raw -> binaryPropsByItem.put(raw.registerItemId(), assembledBinaries(raw.row().binaryIdsByProperty(), binaryObjects,
                binaryBaseUrl, ownPropertyNames, options.requestedProperties())));

        // Null for an admin (no filtering); otherwise the read-granted (write implies read)
        // property-id grant map, resolved per item against that item's markers below.
        Map<UUID, Set<UUID>> effectiveReadGrants = permissions.isAdmin() ? null
                : readImpliedByWrite(permissions.propertyReadGrantsByMarker(), permissions.propertyWriteGrantsByMarker());

        LinkAssembly linkAssembly = new LinkAssembly(permissions, includePermissions, binaryBaseUrl, binaryObjects, displayLabelPatterns,
                perspectiveNamesById(), effectiveReadGrants,
                permissions.isAdmin() ? null : readImpliedByWrite(permissions.linkPropertyReadGrantsByMarker(), permissions.linkPropertyWriteGrantsByMarker()),
                new HashMap<>(), new HashMap<>());
        Map<UUID, Map<String, List<ProjectedLink>>> linksByItem = new HashMap<>();
        rawItems.forEach(raw -> linksByItem.put(raw.registerItemId(),
                assembleLinks(raw.row().markerIds(), raw.row().linksByPerspective(), linkAssembly)));

        Map<UUID, ControlledListLabels> controlledListLabelsByPropertyId = resolveControlledListLabels(allPropertyNames, propertyViewsById);

        AssemblyContext ctx = new AssemblyContext(allPropertyNames, ownPropertyNames, controlledListLabelsByPropertyId, stateMachines, displayLabelPatterns,
                linksByItem, markerIdsByRegisterItemId, binaryPropsByItem, effectiveReadGrants,
                markerNamesById, permissions, includePermissions, options.includeStates(), options.requestedProperties());
        return rawItems.stream().map(raw -> assembleProjectedItem(raw, ctx)).toList();
    }

    private record AssemblyContext(
            Map<UUID, List<String>> allPropertyNames,
            Map<UUID, List<String>> ownPropertyNames,
            Map<UUID, ControlledListLabels> controlledListLabelsByPropertyId,
            List<AdminStateMachineView> stateMachines,
            Map<String, String> displayLabelPatterns,
            Map<UUID, Map<String, List<ProjectedLink>>> linksByItem,
            Map<UUID, Set<UUID>> markerIdsByRegisterItemId,
            Map<UUID, List<AssembledBinary>> binaryPropsByItem,
            @Nullable Map<UUID, Set<UUID>> effectiveReadGrants,
            Map<UUID, String> markerNamesById,
            RequestPermissionContext permissions,
            boolean includePermissions,
            boolean includeStates,
            @Nullable List<String> requestedProperties) {}

    // The label identifies the item, so it reads every property the caller may see, not just the
    // requested subset. With no restriction, props already is exactly that. (Binary properties
    // aren't merged in for a restricted request -- a label pattern has no use for one.)
    private Map<String, Object> displayLabelInput(Map<String, Object> props, Map<String, Object> readableProps, AssemblyContext ctx) {
        if (ctx.requestedProperties() == null) {
            return props;
        }
        Map<String, Object> all = namesForIds(readableProps, ctx.allPropertyNames());
        applyControlledListLabels(all, ctx.allPropertyNames(), ctx.controlledListLabelsByPropertyId());
        return all;
    }

    private ProjectedItem assembleProjectedItem(RawItem raw, AssemblyContext ctx) {
        Set<UUID> markerIds = ctx.markerIdsByRegisterItemId().getOrDefault(raw.registerItemId(), Set.of());
        Map<String, Object> readableProps = filterPropertiesByReadGrant(raw.properties(), markerIds, ctx.effectiveReadGrants());
        Map<String, Object> props = new HashMap<>(namesForIds(readableProps, ctx.ownPropertyNames()));
        applyControlledListLabels(props, ctx.ownPropertyNames(), ctx.controlledListLabelsByPropertyId());
        mergeReadableBinaryProperties(props, raw.registerItemId(), markerIds,
                ctx.binaryPropsByItem(), ctx.effectiveReadGrants(), ctx.ownPropertyNames());
        var itemPermissions = ctx.includePermissions()
                ? buildPermissions(ctx.permissions(), markerIds,
                        ctx.permissions().propertyWriteGrantsByMarker(), ctx.permissions().itemDeleteGrantedMarkerIds(),
                        rootPropertiesForItemTypeName(raw.itemType()),
                        linkPerspectivesForItemTypeName(raw.itemType()))
                : null;
        // Admin sees every marker on the item; anyone else sees only the ones they hold an
        // item:read grant on -- never the mere existence of markers they can't read.
        Set<UUID> disclosableMarkerIds = ctx.permissions().isAdmin()
                ? markerIds
                : markerIds.stream().filter(ctx.permissions().grantedItemReadMarkerIds()::contains).collect(Collectors.toSet());
        List<String> markerNames = disclosableMarkerIds.stream()
                .map(ctx.markerNamesById()::get).filter(Objects::nonNull).sorted().toList();
        return new ProjectedItem(
                raw.itemId(),
                raw.itemType(),
                props,
                ctx.linksByItem().getOrDefault(raw.registerItemId(), Map.of()),
                ctx.includeStates() ? buildProjectedItemStates(raw.states(), ctx.stateMachines(), markerIds, ctx.permissions()) : null,
                itemPermissions,
                computeDisplayLabel(raw.itemId(), displayLabelInput(props, readableProps, ctx), ctx.displayLabelPatterns().get(raw.itemType())),
                markerNames,
                // createdBy/updatedBy are left null here -- RegisterPartitionManager must never
                // resolve or import anything security-related; EntityManagerImpl fills these in
                // afterward from createdByUserId/updatedByUserId, batched across the whole page.
                raw.createdDate(), raw.createdBy(), null, raw.updatedDate(), raw.updatedBy(), null);
    }

    // Extracted from assembleProjectedItem purely to keep that method's own cognitive complexity
    // down. Binary properties are gated by property:read exactly like scalar ones -- there is no
    // separate download grant; read is equivalent to download. Takes its pieces explicitly (not an
    // AssemblyContext) so fetchPropertiesByRegisterItemId can call it too, for a linked item's own
    // binary properties.
    private void mergeReadableBinaryProperties(Map<String, Object> props, UUID registerItemId, Set<UUID> markerIds,
            Map<UUID, List<AssembledBinary>> binaryPropsByItem, @Nullable Map<UUID, Set<UUID>> effectiveReadGrants,
            Map<UUID, List<String>> ownPropertyNames) {
        List<AssembledBinary> bins = binaryPropsByItem.get(registerItemId);
        if (bins == null) return;
        Set<UUID> readableBinaryIds = effectiveReadGrants == null ? null
                : unionGrantedIds(markerIds, effectiveReadGrants);
        for (var b : bins) {
            if (readableBinaryIds != null && !readableBinaryIds.contains(b.propertyId())) continue;
            // Same path-walking merge namesForIds uses for every other property type -- a
            // BINARY leaf can live inside a property group (e.g. file.content) just like
            // any other leaf, so it needs to land at its schema-nested location, not always
            // flattened to the top level under its own bare name.
            List<String> path = ownPropertyNames.get(b.propertyId());
            if (path != null) putAtPath(props, path, b.value());
        }
    }

    // One ProjectedItemState per state machine that's either active on this item, or inactive but
    // startable by this principal -- a machine that's neither (inactive, and this principal holds
    // no state-machine:start grant for it) has nothing to tell the caller: no current state to
    // report, no Start affordance to offer, so its entry is omitted entirely, same "absence means
    // nothing" convention buildEditTree uses. Returns null (not an empty map) when nothing survives
    // that filter -- either the type has no machines at all, or every machine on it does.
    private Map<String, ProjectedItemState> buildProjectedItemStates(Map<String, Object> statesById,
            List<AdminStateMachineView> stateMachines, Set<UUID> markerIds, RequestPermissionContext permissions) {
        if (stateMachines == null || stateMachines.isEmpty()) {
            return null;
        }
        Map<String, Object> states = statesById == null ? Map.of() : statesById;
        Set<UUID> startGrantedSmIds = permissions.isAdmin() ? Set.of()
                : unionGrantedIds(markerIds, permissions.stateMachineStartGrantsByMarker());
        Set<UUID> executeGrantedTransitionIds = permissions.isAdmin() ? Set.of()
                : unionGrantedIds(markerIds, permissions.transitionExecuteGrantsByMarker());

        Map<String, ProjectedItemState> result = new LinkedHashMap<>();
        for (AdminStateMachineView machine : stateMachines) {
            Optional<AdminStateView> currentState = currentStateOf(machine, states.get(machine.id().toString()));
            if (currentState.isPresent()) {
                AdminStateView cs = currentState.get();
                Map<UUID, String> kindByStateId = machine.states().stream()
                        .collect(Collectors.toMap(AdminStateView::id, AdminStateView::kind));
                List<AvailableTransition> available = cs.transitions().stream()
                        .filter(t -> permissions.isAdmin() || executeGrantedTransitionIds.contains(t.id()))
                        .map(t -> new AvailableTransition(t.id(), t.name(), t.toStateName(),
                                kindByStateId.getOrDefault(t.toStateId(), STATE_KIND_NORMAL)))
                        .toList();
                result.put(machine.name(), new ProjectedItemState(cs.name(), null, false, available));
            } else {
                boolean startable = permissions.isAdmin() || startGrantedSmIds.contains(machine.id());
                if (!startable) continue;
                result.put(machine.name(), new ProjectedItemState(null, null, startable, List.of()));
            }
        }
        return result.isEmpty() ? null : result;
    }

    private static final String STATE_KIND_NORMAL = "NORMAL";
    private static final String STATE_KIND_START = "START";

    // The item's current AdminStateView in this machine, or empty when the machine isn't active
    // (or the recorded state id no longer exists in the schema -- a dangling id is dropped, same
    // as namesForIds does for properties).
    private Optional<AdminStateView> currentStateOf(AdminStateMachineView machine, Object rawStateEntry) {
        if (!(rawStateEntry instanceof Map<?, ?> stateEntry)) return Optional.empty();
        Object currentStateId = stateEntry.get(STATE_CURRENT_STATE_ID);
        if (currentStateId == null) return Optional.empty();
        return machine.states().stream()
                .filter(s -> s.id().toString().equals(currentStateId.toString()))
                .findFirst();
    }

    // id and url are always included regardless of what was requested -- they're structural facts
    // about the binary value, not opt-in "properties" a caller selects among (design-notes section
    // 10), and never part of the binary's own fields document (obj.metadata()) at all. That document's
    // own top-level keys (hash, length, mimeType, metadata, calculated) are merged in directly as
    // siblings of id/url, not nested under an extra wrapper key -- so "File.content.hash.md5",
    // "File.content.length", and "File.content.metadata.exif.Make" are all resolved the same
    // generic way, with no "metadata" special case anywhere in this method.
    private Map<String, Object> assembleBinaryValue(BinaryPropertyObject obj, String binaryBaseUrl,
                                                       @Nullable List<String> propertyPath, @Nullable List<String> requestedProperties) {
        Map<String, Object> value = new HashMap<>();
        value.put("id", obj.id().toString());
        value.put("url", binaryBaseUrl + "/api/binary/" + obj.id());
        if (obj.metadata() != null) {
            value.putAll(slicedBinaryFields(obj.metadata(), propertyPath, requestedProperties));
        }
        return value;
    }

    // requestedProperties == null means no restriction at all (today's default: everything, unsliced).
    // Otherwise, a *broad* request for this property -- its bare name alone -- wins and returns every
    // one of its fields too (see restrictToRequestedProperties' own comment on why that's the wildcard
    // case, not a glob). Only when every matching request names a specific registered path (e.g.
    // "<name>.metadata.exif.Make", "<name>.hash.md5", or a bare "<name>.length") does this narrow the
    // response to just those paths, merged into one partial tree -- restrictToRequestedProperties
    // already guaranteed at least one such path exists whenever this property was included at all, so
    // the specificPaths-empty branch here is a defensive fallback, not an expected outcome.
    private Map<String, Object> slicedBinaryFields(Map<String, Object> fields, @Nullable List<String> propertyPath,
                                                 @Nullable List<String> requestedProperties) {
        if (requestedProperties == null || propertyPath == null) return fields;
        String joined = String.join(".", propertyPath);
        Set<String> requested = Set.copyOf(requestedProperties);
        boolean broad = requested.contains(joined);
        if (broad) return fields;

        String prefix = joined + ".";
        List<List<String>> specificPaths = requested.stream()
                .filter(r -> r.startsWith(prefix))
                .map(r -> List.of(r.substring(prefix.length()).split("\\.")))
                .toList();
        if (specificPaths.isEmpty()) return fields;

        Map<String, Object> sliced = new HashMap<>();
        for (List<String> path : specificPaths) {
            Object leafValue = getAtPath(fields, path);
            if (leafValue != null) putAtPath(sliced, path, leafValue);
        }
        return sliced;
    }

    // Read counterpart to putAtPath -- walks a nested map by path, returning null (not throwing) if
    // any segment is missing or isn't itself a map, e.g. a stale registered path whose binary no
    // longer actually has it.
    private Object getAtPath(Map<String, Object> source, List<String> path) {
        Object cursor = source;
        for (String segment : path) {
            if (!(cursor instanceof Map<?, ?> map)) return null;
            cursor = map.get(segment);
        }
        return cursor;
    }

    // The client's predicate resolved against the schema: names to ids, and each property it reads
    // checked as readable (a request error otherwise). RegisterStore compiles the result.
    private Condition toCondition(@Nullable Predicate predicate, UUID itemTypeId, RequestPermissionContext permissions) {
        if (predicate == null) return Condition.All.INSTANCE;
        return switch (predicate) {
            case AndPredicate and -> new Condition.And(and.predicates().stream().map(p -> toCondition(p, itemTypeId, permissions)).toList());
            case OrPredicate or -> new Condition.Or(or.predicates().stream().map(p -> toCondition(p, itemTypeId, permissions)).toList());
            case NotPredicate not -> new Condition.Not(toCondition(not.predicate(), itemTypeId, permissions));
            case PropertyExistencePredicate p -> new Condition.PropertyExists(readablePropertyId(itemTypeId, p.propertyName(), permissions));
            case PropertyValuePredicate p -> new Condition.PropertyCompare(readablePropertyId(itemTypeId, p.propertyName(), permissions),
                    toComparison(p.operator()), p.value());
            case StateValuePredicate p -> {
                UUID stateMachineId = schemaManager.resolveStateMachineId(itemTypeId, p.stateMachineName());
                yield new Condition.InState(stateMachineId, schemaManager.resolveStateId(stateMachineId, p.stateName()));
            }
        };
    }

    private UUID readablePropertyId(UUID itemTypeId, String propertyName, RequestPermissionContext permissions) {
        UUID propertyId = resolvePropertyId(itemTypeId, propertyName);
        requirePropertyReadableByAnyMarker(permissions, propertyId, propertyName);
        return propertyId;
    }

    private static Comparison toComparison(Operator operator) {
        return switch (operator) {
            case EQUALS -> Comparison.EQUALS;
            case NOT_EQUALS -> Comparison.NOT_EQUALS;
            case LESS_THAN -> Comparison.LESS_THAN;
            case LESS_THAN_OR_EQUAL -> Comparison.LESS_THAN_OR_EQUAL;
            case GREATER_THAN -> Comparison.GREATER_THAN;
            case GREATER_THAN_OR_EQUAL -> Comparison.GREATER_THAN_OR_EQUAL;
            case LIKE -> Comparison.LIKE_IGNORING_CASE;
        };
    }

    // Mode-1 (existence-affecting) item:read, applied in SQL so every count, facet and page reflects
    // it -- never a post-fetch filter (see docs/ntrloc-acl-design-notes.md "Performance model").
    private static Access toAccess(RequestPermissionContext permissions) {
        return permissions.isAdmin() ? Access.Unrestricted.INSTANCE : new Access.AnyMarker(permissions.grantedItemReadMarkerIds());
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
        return RegisterStore.tableNameFor(typeId);
    }

    public static String linkTableNameFor(UUID linkTypeId) {
        return RegisterStore.linkTableNameFor(linkTypeId);
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
