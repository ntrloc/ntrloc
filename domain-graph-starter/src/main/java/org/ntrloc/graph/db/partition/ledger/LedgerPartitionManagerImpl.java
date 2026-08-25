package org.ntrloc.graph.db.partition.ledger;

import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Component
@DependsOnDatabaseInitialization
public class LedgerPartitionManagerImpl implements LedgerPartitionManager {

    private static final String COL_PAYLOAD = "payload";
    private static final String PARAM_TRANSACTION_ID = "transactionId";

    private final JdbcClient jdbcClient;
    private final ObjectMapper objectMapper;

    public LedgerPartitionManagerImpl(JdbcClient jdbcClient, ObjectMapper objectMapper) {
        this.jdbcClient = jdbcClient;
        this.objectMapper = objectMapper;
    }

    @Override
    public void append(List<LedgerEntry> entries, UUID transactionId, String actorExternalId) {
        entries.forEach(entry -> insert(entry, transactionId, actorExternalId));
    }

    private void insert(LedgerEntry entry, UUID transactionId, String actorExternalId) {
        TargetRef target = targetOf(entry);
        jdbcClient.sql("""
                INSERT INTO ledger_entry (target_type, target_id, entry_type, payload, transaction_id, state, actor_external_id)
                VALUES (:targetType, :targetId, :entryType, :payload::jsonb, :transactionId, 'UNCOMMITTED', :actorExternalId)
                """)
                .param("targetType", target.type())
                .param("targetId", target.id())
                .param("entryType", entryTypeOf(entry))
                .param(COL_PAYLOAD, writeEntry(entry))
                .param(PARAM_TRANSACTION_ID, transactionId)
                .param("actorExternalId", actorExternalId)
                .update();
    }

    @Override
    public void commit(UUID transactionId, UUID commitId) {
        jdbcClient.sql("""
                UPDATE ledger_entry SET state = 'COMMITTED', commit_id = :commitId
                WHERE transaction_id = :transactionId AND state = 'UNCOMMITTED'
                """)
                .param("commitId", commitId)
                .param(PARAM_TRANSACTION_ID, transactionId)
                .update();
    }

    @Override
    public void abort(UUID transactionId) {
        jdbcClient.sql("DELETE FROM ledger_entry WHERE transaction_id = :transactionId AND state = 'UNCOMMITTED'")
                .param(PARAM_TRANSACTION_ID, transactionId)
                .update();
    }

    @Override
    public List<LedgerEntry> readItemStream(UUID itemId) {
        return readStream("ITEM", itemId);
    }

    @Override
    public List<LedgerEntry> readLinkStream(UUID linkId) {
        return readStream("LINK", linkId);
    }

    @Override
    public List<LedgerEntryRecord> readItemStreamWithMetadata(UUID itemId) {
        return jdbcClient.sql("""
                SELECT payload::text AS payload, created_at, actor_external_id FROM ledger_entry
                WHERE target_type = 'ITEM' AND target_id = :itemId AND state = 'COMMITTED'
                ORDER BY sequence_number
                """)
                .param("itemId", itemId)
                .query((rs, n) -> new LedgerEntryRecord(
                        readEntry(rs.getString(COL_PAYLOAD)),
                        rs.getObject("created_at", OffsetDateTime.class),
                        rs.getString("actor_external_id")))
                .list();
    }

    private List<LedgerEntry> readStream(String targetType, UUID targetId) {
        return jdbcClient.sql("""
                SELECT payload::text AS payload FROM ledger_entry
                WHERE target_type = :targetType AND target_id = :targetId AND state = 'COMMITTED'
                ORDER BY sequence_number
                """)
                .param("targetType", targetType)
                .param("targetId", targetId)
                .query((rs, n) -> readEntry(rs.getString(COL_PAYLOAD)))
                .list();
    }

    @Override
    public List<LedgerEntry> readTransaction(UUID transactionId) {
        return jdbcClient.sql("""
                SELECT payload::text AS payload FROM ledger_entry
                WHERE transaction_id = :transactionId
                ORDER BY sequence_number
                """)
                .param(PARAM_TRANSACTION_ID, transactionId)
                .query((rs, n) -> readEntry(rs.getString(COL_PAYLOAD)))
                .list();
    }

    private record TargetRef(String type, UUID id) {
    }

    private TargetRef targetOf(LedgerEntry entry) {
        if (entry instanceof ItemCreateEntry e) return new TargetRef("ITEM", e.itemId());
        if (entry instanceof ItemUpdateEntry e) return new TargetRef("ITEM", e.itemId());
        if (entry instanceof ItemDeleteEntry e) return new TargetRef("ITEM", e.itemId());
        if (entry instanceof LinkCreateEntry e) return new TargetRef("LINK", e.linkId());
        if (entry instanceof LinkUpdateEntry e) return new TargetRef("LINK", e.linkId());
        if (entry instanceof LinkDeleteEntry e) return new TargetRef("LINK", e.linkId());
        if (entry instanceof ItemMarkerAddEntry e) return new TargetRef("ITEM", e.itemId());
        if (entry instanceof ItemMarkerRemoveEntry e) return new TargetRef("ITEM", e.itemId());
        if (entry instanceof LinkMarkerAddEntry e) return new TargetRef("LINK", e.linkId());
        if (entry instanceof LinkMarkerRemoveEntry e) return new TargetRef("LINK", e.linkId());
        throw new IllegalArgumentException("Unsupported entry: " + entry.getClass().getSimpleName());
    }

    private String entryTypeOf(LedgerEntry entry) {
        if (entry instanceof ItemCreateEntry) return "ITEM_CREATE";
        if (entry instanceof ItemUpdateEntry) return "ITEM_UPDATE";
        if (entry instanceof ItemDeleteEntry) return "ITEM_DELETE";
        if (entry instanceof LinkCreateEntry) return "LINK_CREATE";
        if (entry instanceof LinkUpdateEntry) return "LINK_UPDATE";
        if (entry instanceof LinkDeleteEntry) return "LINK_DELETE";
        if (entry instanceof ItemMarkerAddEntry) return "ITEM_MARKER_ADD";
        if (entry instanceof ItemMarkerRemoveEntry) return "ITEM_MARKER_REMOVE";
        if (entry instanceof LinkMarkerAddEntry) return "LINK_MARKER_ADD";
        if (entry instanceof LinkMarkerRemoveEntry) return "LINK_MARKER_REMOVE";
        throw new IllegalArgumentException("Unsupported entry: " + entry.getClass().getSimpleName());
    }

    private String writeEntry(LedgerEntry entry) {
        return objectMapper.writeValueAsString(entry);
    }

    private LedgerEntry readEntry(String json) {
        return objectMapper.readValue(json, LedgerEntry.class);
    }
}
