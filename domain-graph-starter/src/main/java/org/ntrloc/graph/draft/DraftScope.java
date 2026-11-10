package org.ntrloc.graph.draft;

import org.ntrloc.graph.principal.NtrlocPrincipal;

import java.util.UUID;

/**
 * Where a draft request operates: an open draft, and the user, who may work in it. A controller under
 * /api/drafts/{draftId}/... takes this as a parameter; DraftScopeResolver makes one only after checking
 * the user may enter the draft (an admin, or a participant), so holding one is proof of access
 * (docs/ntrloc-system-model-notes.md §9.1).
 */
public record DraftScope(DraftRecord draft, NtrlocPrincipal user) {

    public UUID draftId() {
        return draft.id();
    }
}
