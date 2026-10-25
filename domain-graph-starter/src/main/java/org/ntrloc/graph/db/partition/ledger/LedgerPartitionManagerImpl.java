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
    public void append(List<LedgerEntry> entries, UUID transactionId, UUID createdBy, OffsetDateTime createdDate) {
        entries.forEach(entry -> insert(entry, transactionId, createdBy, createdDate));
    }

    private void insert(LedgerEntry entry, UUID transactionId, UUID createdBy, OffsetDateTime createdDate) {
        TargetRef target = targetOf(entry);
        jdbcClient.sql("""
                INSERT INTO ledger_entry (target_type, target_id, entry_type, payload, transaction_id, state, created_by, created_date)
                VALUES (:targetType, :targetId, :entryType, :payload::jsonb, :transactionId, 'UNCOMMITTED', :createdBy, :createdDate)
                """)
                .param("targetType", target.type())
                .param("targetId", target.id())
                .param("entryType", entryTypeOf(entry))
                .param(COL_PAYLOAD, writeEntry(entry))
                .param(PARAM_TRANSACTION_ID, transactionId)
                .param("createdBy", createdBy)
                .param("createdDate", createdDate)
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
                SELECT payload::text AS payload, created_date, created_by FROM ledger_entry
                WHERE target_type = 'ITEM' AND target_id = :itemId AND state = 'COMMITTED'
                ORDER BY sequence_number
                """)
                .param("itemId", itemId)
                .query((rs, _) -> new LedgerEntryRecord(
                        readEntry(rs.getString(COL_PAYLOAD)),
                        rs.getObject("created_date", OffsetDateTime.class),
                        rs.getObject("created_by", UUID.class)))
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
                .query((rs, _) -> readEntry(rs.getString(COL_PAYLOAD)))
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
                .query((rs, _) -> readEntry(rs.getString(COL_PAYLOAD)))
                .list();
    }

    private record TargetRef(String type, UUID id) {
    }

    private TargetRef targetOf(LedgerEntry entry) {
        return switch (entry) {
            case ItemCreateEntry e -> new TargetRef("ITEM", e.itemId());
            case ItemUpdateEntry e -> new TargetRef("ITEM", e.itemId());
            case ItemDeleteEntry e -> new TargetRef("ITEM", e.itemId());
            case LinkCreateEntry e -> new TargetRef("LINK", e.linkId());
            case LinkUpdateEntry e -> new TargetRef("LINK", e.linkId());
            case LinkDeleteEntry e -> new TargetRef("LINK", e.linkId());
        };
    }

    private String entryTypeOf(LedgerEntry entry) {
        return switch (entry) {
            case ItemCreateEntry _ -> "ITEM_CREATE";
            case ItemUpdateEntry _ -> "ITEM_UPDATE";
            case ItemDeleteEntry _ -> "ITEM_DELETE";
            case LinkCreateEntry _ -> "LINK_CREATE";
            case LinkUpdateEntry _ -> "LINK_UPDATE";
            case LinkDeleteEntry _ -> "LINK_DELETE";
        };
    }

    private String writeEntry(LedgerEntry entry) {
        return objectMapper.writeValueAsString(entry);
    }

    private LedgerEntry readEntry(String json) {
        return objectMapper.readValue(json, LedgerEntry.class);
    }
}
