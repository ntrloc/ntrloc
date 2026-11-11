package org.ntrloc.graph.draft;

import org.ntrloc.graph.db.partition.changelog.ChangeEntries;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Each draft's changelog (draft_change_log): who changed what in the draft, and when -- its grant changes,
 * reconciliations, participants, and its start and close (docs/ntrloc-system-model-notes.md §13.6). The same
 * entries as live's ChangeLog, in a table of their own, written only by draft code.
 */
@Component
public class DraftChangeLog extends ChangeEntries {

    public DraftChangeLog(JdbcClient jdbcClient) {
        super(jdbcClient, "draft_change_log", "draft_id");
    }

    public void append(UUID draftId, String kind, String elementKey, Collection<UUID> refs, Map<String, ?> before, Map<String, ?> after) {
        super.append(draftId, kind, elementKey, refs, before, after);
    }

    /** The draft's entries, oldest first. */
    public List<Entry> entries(UUID draftId) {
        return entriesIn(draftId);
    }
}
