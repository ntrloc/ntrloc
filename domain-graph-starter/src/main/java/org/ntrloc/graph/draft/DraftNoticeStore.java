package org.ntrloc.graph.draft;

import org.ntrloc.graph.db.partition.changelog.ChangeEntries;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

// What a draft has to hear about from live (docs/ntrloc-system-model-notes.md §13.6): live's changelog
// entries since the draft started, the latest per element, less what it has acknowledged. Reads live's log
// (drafts read live, never the reverse); acknowledgements are the draft's own (draft_notice_ack).
// Written only by DraftAccessService.
@Component
public class DraftNoticeStore {

    private final JdbcClient jdbcClient;

    public DraftNoticeStore(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public List<DraftNotice> open(UUID draftId, long since) {
        return jdbcClient.sql("SELECT DISTINCT ON (l.element_key) " + ChangeEntries.ENTRY_COLUMNS + ","
                        + " COUNT(*) OVER (PARTITION BY l.element_key) AS changes"
                        + " FROM config_change_log l"
                        + " LEFT JOIN draft_notice_ack a ON a.draft_id = :draftId AND a.element_key = l.element_key"
                        + " WHERE l.seq > :since AND (a.seq IS NULL OR l.seq > a.seq)"
                        + " ORDER BY l.element_key, l.seq DESC")
                .param("draftId", draftId).param("since", since)
                .query((rs, _) -> new DraftNotice(ChangeEntries.entry(rs), rs.getInt("changes")))
                .list().stream()
                .sorted((a, b) -> Long.compare(a.latest().seq(), b.latest().seq()))
                .toList();
    }

    /** Acknowledges the element's changes up to `seq`; a later change opens it again. */
    public void acknowledge(UUID draftId, String elementKey, long seq, UUID by) {
        jdbcClient.sql("""
                INSERT INTO draft_notice_ack (draft_id, element_key, seq, acked_by) VALUES (:draftId, :elementKey, :seq, :by)
                ON CONFLICT (draft_id, element_key) DO UPDATE SET seq = GREATEST(draft_notice_ack.seq, EXCLUDED.seq),
                    acked_by = EXCLUDED.acked_by, acked_at = now()
                """).param("draftId", draftId).param("elementKey", elementKey).param("seq", seq).param("by", by).update();
    }
}
