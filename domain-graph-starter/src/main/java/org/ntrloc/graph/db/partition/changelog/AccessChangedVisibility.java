package org.ntrloc.graph.db.partition.changelog;

import org.ntrloc.graph.events.ClusterEvent;
import org.ntrloc.graph.events.ClusterEventVisibility;
import org.ntrloc.graph.principal.NtrlocPrincipal;
import org.springframework.stereotype.Component;

// "accessChanged" (ChangeLog) goes to admins only: only they see Access, and only they work with a draft's
// live changes.
@Component
public class AccessChangedVisibility implements ClusterEventVisibility {

    @Override
    public boolean supports(String eventType) {
        return ChangeLog.ACCESS_CHANGED.equals(eventType);
    }

    @Override
    public boolean isVisible(ClusterEvent event, NtrlocPrincipal principal) {
        return principal.isAdmin();
    }
}
