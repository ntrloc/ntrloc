package org.ntrloc.graph.db.partition.register;

import org.ntrloc.graph.access.FactResolver;
import org.ntrloc.graph.db.partition.schema.event.SchemaChangeEvent;
import org.ntrloc.graph.db.partition.schema.event.SchemaChangeListener;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

// The register's write side and the lookups the write side needs: staging, commit and abort of item
// and link rows, marker postings, current-value reads for mutations and marker pipelines (FactResolver),
// and each type's table as the schema changes. Projections are ProjectionEngine's, over RegisterStore.
// What's here moves into RegisterStore next (docs/ntrloc-architecture-rationalization.md §9, step 1.5).
@Component
public class RegisterPartitionManager implements SchemaChangeListener, FactResolver {

    private static final String PARAM_ITEM_ID = "itemId";
    private static final String PARAM_ITEM_TYPE_ID = "itemTypeId";
    private static final String COL_ITEM_ID = "item_id";
    private static final String COL_PROPERTIES = "properties";
    private static final String COL_STATES = "states";
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

    private final JdbcClient jdbcClient;
    private final ObjectMapper objectMapper;

    public RegisterPartitionManager(JdbcClient jdbcClient, ObjectMapper objectMapper) {
        this.jdbcClient = jdbcClient;
        this.objectMapper = objectMapper;
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
