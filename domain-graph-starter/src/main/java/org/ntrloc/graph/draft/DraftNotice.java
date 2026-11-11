package org.ntrloc.graph.draft;

import org.ntrloc.graph.db.partition.changelog.ChangeEntries;

/**
 * A live change a draft is told about (docs/ntrloc-system-model-notes.md §13.6): the latest change to one
 * element since the draft started, or since it was last acknowledged, and how many there were.
 */
public record DraftNotice(ChangeEntries.Entry latest, int changes) {}
