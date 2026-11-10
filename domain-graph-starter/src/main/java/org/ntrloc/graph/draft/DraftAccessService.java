package org.ntrloc.graph.draft;

import org.ntrloc.graph.db.partition.authorization.repository.GrantsView;
import org.ntrloc.graph.db.partition.authorization.repository.Permission;
import org.ntrloc.graph.db.partition.authorization.repository.UserGrantsView;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

// AccessService's counterpart inside a draft: the only writer of DraftGrantStore. Phase 1 drafts grants
// only; markers stay live.
@Service
public class DraftAccessService {

    private final DraftGrantStore draftGrantStore;

    public DraftAccessService(DraftGrantStore draftGrantStore) {
        this.draftGrantStore = draftGrantStore;
    }

    public GrantsView getGrantsView(DraftScope scope) {
        return draftGrantStore.getGrantsView(scope.draftId());
    }

    /** As AccessService.getUserGrants, from the draft's grants: the scope's user, as they are in the draft. */
    public UserGrantsView getUserGrants(DraftScope scope) {
        var user = scope.user();
        return user.isAdmin() ? UserGrantsView.forAdmin() : getGrantsView(scope).forUser(user.id(), user.groupIds());
    }

    public List<DraftGrantOverride> overrides(UUID draftId) {
        return draftGrantStore.overrides(draftId);
    }

    /** As AccessService.setPermission, in the draft: live is untouched. Not once the draft has closed. */
    @Transactional
    public void setPermission(DraftScope scope, String principalType, UUID principalId, Permission permission, boolean value) {
        if (!draftGrantStore.holdOpen(scope.draftId())) throw new DraftService.DraftNotFoundException();
        draftGrantStore.setPermission(scope.draftId(), principalType, principalId, permission, value);
    }

    /** Called as the draft closes, in the same transaction. */
    public void draftClosed(UUID draftId) {
        draftGrantStore.forget(draftId);
    }
}
