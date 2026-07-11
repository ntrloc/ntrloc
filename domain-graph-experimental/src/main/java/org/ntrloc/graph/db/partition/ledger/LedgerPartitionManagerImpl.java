package org.ntrloc.graph.db.partition.ledger;

import org.springframework.context.annotation.DependsOn;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.UUID;

@Component
@DependsOn("ledgerInitializer")
public class LedgerPartitionManagerImpl implements LedgerPartitionManager {

    private final JdbcClient jdbcClient;
    private final ObjectMapper objectMapper;

    public LedgerPartitionManagerImpl(JdbcClient jdbcClient, ObjectMapper objectMapper) {
        this.jdbcClient = jdbcClient;
        this.objectMapper = objectMapper;
    }

    @Override
    public void append(List<LedgerEntry> entries, UUID transactionId) {
        entries.forEach(entry -> insert(entry, transactionId));
    }

    private void insert(LedgerEntry entry, UUID transactionId) {
        TargetRef target = targetOf(entry);
        jdbcClient.sql("""
                INSERT INTO ledger_entry (target_type, target_id, entry_type, payload, transaction_id, state)
                VALUES (:targetType, :targetId, :entryType, :payload::jsonb, :transactionId, 'UNCOMMITTED')
                """)
                .param("targetType", target.type())
                .param("targetId", target.id())
                .param("entryType", entryTypeOf(entry))
                .param("payload", writeEntry(entry))
                .param("transactionId", transactionId)
                .update();
    }

    @Override
    public void commit(UUID transactionId, UUID commitId) {
        jdbcClient.sql("""
                UPDATE ledger_entry SET state = 'COMMITTED', commit_id = :commitId
                WHERE transaction_id = :transactionId AND state = 'UNCOMMITTED'
                """)
                .param("commitId", commitId)
                .param("transactionId", transactionId)
                .update();
    }

    @Override
    public void abort(UUID transactionId) {
        jdbcClient.sql("DELETE FROM ledger_entry WHERE transaction_id = :transactionId AND state = 'UNCOMMITTED'")
                .param("transactionId", transactionId)
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

    private List<LedgerEntry> readStream(String targetType, UUID targetId) {
        return jdbcClient.sql("""
                SELECT payload::text AS payload FROM ledger_entry
                WHERE target_type = :targetType AND target_id = :targetId AND state = 'COMMITTED'
                ORDER BY sequence_number
                """)
                .param("targetType", targetType)
                .param("targetId", targetId)
                .query((rs, n) -> readEntry(rs.getString("payload")))
                .list();
    }

    @Override
    public List<LedgerEntry> readTransaction(UUID transactionId) {
        return jdbcClient.sql("""
                SELECT payload::text AS payload FROM ledger_entry
                WHERE transaction_id = :transactionId
                ORDER BY sequence_number
                """)
                .param("transactionId", transactionId)
                .query((rs, n) -> readEntry(rs.getString("payload")))
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
            case ItemCreateEntry e -> "ITEM_CREATE";
            case ItemUpdateEntry e -> "ITEM_UPDATE";
            case ItemDeleteEntry e -> "ITEM_DELETE";
            case LinkCreateEntry e -> "LINK_CREATE";
            case LinkUpdateEntry e -> "LINK_UPDATE";
            case LinkDeleteEntry e -> "LINK_DELETE";
        };
    }

    private String writeEntry(LedgerEntry entry) {
        return objectMapper.writeValueAsString(entry);
    }

    private LedgerEntry readEntry(String json) {
        return objectMapper.readValue(json, LedgerEntry.class);
    }
}
