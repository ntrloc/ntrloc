package org.ntrloc.graph.db.partition.binary;

import org.ntrloc.graph.db.partition.schema.SchemaManager;
import org.ntrloc.graph.db.partition.schema.definition.PropertyType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

// Populates/reconciles the binary dynamic-property registry (binary_dynamic_property +
// binary_property_dynamic_property) -- see docs/ntrloc-dynamic-properties-design-notes.md section
// 10. Called by RegisterPartitionManager whenever a binary property's value is attached, detached,
// or repointed to a different binary (never on read); will also be the reconciliation entry point
// for re-extraction once that exists (BinaryPartitionManager.updateMetadata's own trigger 3). Shared
// here so all triggers stay in lockstep rather than reimplementing the flatten/adjust walk per site.
// Takes an already-parsed metadata map, not a raw JSON string: every caller already has one on hand
// (BinaryPropertyObject.metadata() is parsed at the BinaryPartitionManager boundary; a re-extraction
// diff needs a parsed map to compare old/new anyway), so there's no reason to round-trip through JSON
// text here.
//
// Also tells SchemaManager when a *structural* change happened -- a join row created or deleted,
// meaning some property's AdminBinaryPropertyDefinitionView.dynamicFields actually gained or lost an
// entry -- not on every reference-count increment/decrement, which never changes what's advertised.
// Confirmed necessary, not just a nice-to-have: without it, sort/filter resolution against a newly
// (or no-longer) discovered path silently used a stale cached schema. Deferred until after the
// enclosing transaction commits (scheduleSchemaRefreshAfterCommit), never synchronously mid-
// transaction -- a synchronous call here would broadcast to other cluster nodes before the row they'd
// re-read on reload is even visible to them.
@Component
public class BinaryDynamicPropertyRegistrar {

    private final JdbcClient jdbcClient;
    private final SchemaManager schemaManager;

    public BinaryDynamicPropertyRegistrar(JdbcClient jdbcClient, SchemaManager schemaManager) {
        this.jdbcClient = jdbcClient;
        this.schemaManager = schemaManager;
    }

    // A binary property gained a reference to a binary (create, or update pointing at a new/
    // different binary) -- walk its current metadata and bump every observed path's counter for
    // this property by one, minting a new catalog row the first time a path is ever seen anywhere.
    public void onAttach(UUID propertyId, @Nullable Map<String, Object> metadata) {
        forEachPath(metadata, path -> incrementReferenceCount(propertyId, path));
    }

    // A binary property lost its reference to a binary (repointed away, cleared, or the item
    // deleted) -- walk the *departing* binary's metadata and drop every one of its paths' counters
    // for this property by one, deleting the join row once it reaches zero. A path also reachable
    // through a different binary still attached under the same property (or the same binary still
    // attached via a different item) simply doesn't reach zero -- no special-casing needed here.
    public void onDetach(UUID propertyId, @Nullable Map<String, Object> metadata) {
        forEachPath(metadata, path -> decrementReferenceCount(propertyId, path));
    }

    // A binary's own metadata changed underneath it (re-extraction: an extractor upgrade, or an
    // admin narrowing/widening the media-processor exclusion config) -- not an item mutation at all,
    // so triggers 1-2 above don't apply. Diffs old vs. new, finds every distinct property currently
    // referencing this binary (a binary is content-addressed and independent of any item, so more
    // than one property -- even across unrelated item types -- can share it), and for each one,
    // adjusts every added/removed path by however many *current* references exist for that specific
    // (property, binary) pair -- not by one, since more than one item can share the same binary under
    // the same property (see design-notes section 10's worked example: getting this wrong would
    // desynchronize the counters the first time a shared binary is reprocessed).
    public void onMetadataChanged(UUID binaryContentId, @Nullable Map<String, Object> oldMetadata, @Nullable Map<String, Object> newMetadata) {
        Map<String, PropertyType> oldPaths = flattenPaths(oldMetadata == null ? Map.of() : oldMetadata);
        Map<String, PropertyType> newPaths = flattenPaths(newMetadata == null ? Map.of() : newMetadata);

        Set<String> removedPaths = new HashSet<>(oldPaths.keySet());
        removedPaths.removeAll(newPaths.keySet());
        Set<String> addedPaths = new HashSet<>(newPaths.keySet());
        addedPaths.removeAll(oldPaths.keySet());
        if (removedPaths.isEmpty() && addedPaths.isEmpty()) return;

        referenceCountsByProperty(binaryContentId).forEach((propertyId, count) -> {
            for (int i = 0; i < count; i++) {
                removedPaths.forEach(path -> decrementReferenceCount(propertyId, new DiscoveredPath(path, oldPaths.get(path))));
                addedPaths.forEach(path -> incrementReferenceCount(propertyId, new DiscoveredPath(path, newPaths.get(path))));
            }
        });
    }

    // How many distinct items currently point each property at this exact binary -- register_binary_
    // property is owned by the register partition, not this one, but this registry already sits
    // between the two (it links a schema_property id to facts about binary content), and
    // RegisterPartitionManager already calls into this class the other direction (attach/detach), so
    // reading this one table here doesn't introduce a new coupling direction, just uses the existing
    // one from the other side.
    private Map<UUID, Integer> referenceCountsByProperty(UUID binaryContentId) {
        Map<UUID, Integer> result = new HashMap<>();
        jdbcClient.sql("SELECT property_id, COUNT(*) AS n FROM register_binary_property WHERE binary_id = :binaryId GROUP BY property_id")
                .param("binaryId", binaryContentId)
                .query((rs, n) -> Map.entry(rs.getObject("property_id", UUID.class), rs.getInt("n")))
                .list()
                .forEach(e -> result.put(e.getKey(), e.getValue()));
        return result;
    }

    private void forEachPath(@Nullable Map<String, Object> metadata, java.util.function.Consumer<DiscoveredPath> action) {
        if (metadata == null || metadata.isEmpty()) return;
        flattenPaths(metadata).forEach((path, type) -> action.accept(new DiscoveredPath(path, type)));
    }

    private record DiscoveredPath(String path, PropertyType type) {}

    // Flattens a binary's metadata into dot-paths with an inferred PropertyType -- the same
    // recursive-descent shape RegisterPartitionManager.propertyPaths uses for ordinary properties,
    // just keyed by string path instead of a schema id, since a discovered path has no id of its own
    // until ensureCatalogRow mints one. Arrays are skipped entirely, not descended into -- structured
    // list elements ("bags of structures") are still an open question in design-notes section 6;
    // this pass only handles scalars, matching what's actually needed today. A null leaf carries no
    // type information, so it's skipped too -- a later non-null observation of the same path (from a
    // different binary) still registers normally.
    private Map<String, PropertyType> flattenPaths(Map<String, Object> metadata) {
        Map<String, PropertyType> result = new HashMap<>();
        flattenPaths(metadata, "", result);
        return result;
    }

    @SuppressWarnings("unchecked")
    private void flattenPaths(Map<String, Object> node, String pathPrefix, Map<String, PropertyType> result) {
        node.forEach((key, value) -> {
            String path = pathPrefix.isEmpty() ? key : pathPrefix + "." + key;
            if (value == null) {
                // no type to infer
            } else if (value instanceof Map<?, ?> child) {
                flattenPaths((Map<String, Object>) child, path, result);
            } else if (value instanceof List<?>) {
                // structured list elements: not modeled yet (design-notes section 6)
            } else if (value instanceof String) {
                result.put(path, PropertyType.STRING);
            } else if (value instanceof Boolean) {
                result.put(path, PropertyType.BOOLEAN);
            } else if (value instanceof Double) {
                result.put(path, PropertyType.DOUBLE);
            } else if (value instanceof Number) {
                result.put(path, PropertyType.LONG);
            }
            // else: unrecognized JSON scalar shape -- ignore rather than fail the whole walk
        });
    }

    private static final String UPSERT_CATALOG_SQL = """
            INSERT INTO binary_dynamic_property (path, type) VALUES (:path, :type)
            ON CONFLICT (path) DO NOTHING
            """;

    // Minting is a plain unique-constraint upsert, not a cluster lock -- mirrors design-notes section
    // 6's own reasoning for item-level dynamic-property minting: two concurrent first-observations of
    // the same brand-new path race safely into one row, Postgres's own constraint serializes them.
    // type is first-observed-wins: once a path exists, a later observation never changes its recorded
    // type, even if a differently-shaped value shows up under the same path elsewhere (mirrors
    // design-notes section 6's "later mismatched values are still stored, sort falls back to text").
    private UUID ensureCatalogRow(String path, PropertyType type) {
        jdbcClient.sql(UPSERT_CATALOG_SQL)
                .param("path", path)
                .param("type", type.name())
                .update();
        return jdbcClient.sql("SELECT id FROM binary_dynamic_property WHERE path = :path")
                .param("path", path)
                .query(UUID.class)
                .single();
    }

    // (xmax = 0) is the same "did this INSERT actually insert, or hit the ON CONFLICT branch" idiom
    // BinaryPartitionManagerImpl.insert already uses -- true only the first time this property gains
    // this path, which is exactly when dynamicFields structurally changes.
    private static final String INCREMENT_SQL = """
            INSERT INTO binary_property_dynamic_property (property_id, dynamic_property_id, reference_count)
            VALUES (:propertyId, :dynamicPropertyId, 1)
            ON CONFLICT (property_id, dynamic_property_id)
            DO UPDATE SET reference_count = binary_property_dynamic_property.reference_count + 1
            RETURNING (xmax = 0) AS inserted
            """;

    private void incrementReferenceCount(UUID propertyId, DiscoveredPath discovered) {
        UUID dynamicPropertyId = ensureCatalogRow(discovered.path(), discovered.type());
        boolean insertedNewJoinRow = jdbcClient.sql(INCREMENT_SQL)
                .param("propertyId", propertyId)
                .param("dynamicPropertyId", dynamicPropertyId)
                .query(Boolean.class)
                .single();
        if (insertedNewJoinRow) scheduleSchemaRefreshAfterCommit();
    }

    private static final String DECREMENT_SQL = """
            UPDATE binary_property_dynamic_property SET reference_count = reference_count - 1
            WHERE property_id = :propertyId AND dynamic_property_id = :dynamicPropertyId
            """;
    private static final String DELETE_IF_ZERO_SQL = """
            DELETE FROM binary_property_dynamic_property
            WHERE property_id = :propertyId AND dynamic_property_id = :dynamicPropertyId AND reference_count <= 0
            """;

    // Two statements, not one: the table carries no CHECK enforcing reference_count > 0 (see its own
    // comment in the baseline migration) specifically so this decrement can't be rejected before the
    // follow-up delete runs -- both execute in the same transaction as the register mutation that
    // triggered them, so the transient zero row is never visible outside it.
    private void decrementReferenceCount(UUID propertyId, DiscoveredPath discovered) {
        UUID dynamicPropertyId = ensureCatalogRow(discovered.path(), discovered.type());
        jdbcClient.sql(DECREMENT_SQL)
                .param("propertyId", propertyId)
                .param("dynamicPropertyId", dynamicPropertyId)
                .update();
        int deletedRows = jdbcClient.sql(DELETE_IF_ZERO_SQL)
                .param("propertyId", propertyId)
                .param("dynamicPropertyId", dynamicPropertyId)
                .update();
        if (deletedRows > 0) scheduleSchemaRefreshAfterCommit();
    }

    // Deduplicated per transaction via a bound resource: many paths can structurally change within
    // one attach/detach call (a single binary can introduce dozens of new paths at once), and each
    // would otherwise schedule its own redundant refresh. Falls back to an immediate refresh when
    // called with no active transaction (e.g. directly from a test) rather than silently doing
    // nothing.
    private static final Object REFRESH_SCHEDULED_RESOURCE_KEY = new Object();

    private void scheduleSchemaRefreshAfterCommit() {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            schemaManager.refreshCache();
            return;
        }
        if (TransactionSynchronizationManager.hasResource(REFRESH_SCHEDULED_RESOURCE_KEY)) return;
        TransactionSynchronizationManager.bindResource(REFRESH_SCHEDULED_RESOURCE_KEY, Boolean.TRUE);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                schemaManager.refreshCache();
            }

            @Override
            public void afterCompletion(int status) {
                TransactionSynchronizationManager.unbindResourceIfPossible(REFRESH_SCHEDULED_RESOURCE_KEY);
            }
        });
    }
}
