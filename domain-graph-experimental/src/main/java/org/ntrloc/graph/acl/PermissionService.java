package org.ntrloc.graph.acl;

import org.ntrloc.graph.acl.repository.AclRepository;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class PermissionService {

    public static final String ITEM_READ = "item:read";

    private final AclRepository repo;

    public PermissionService(AclRepository repo) {
        this.repo = repo;
    }

    /** Union of grants across every marker the principal holds directly or via any group. */
    public Set<UUID> effectiveMarkers(NtrlocPrincipal principal, String operation) {
        return repo.getGrantedMarkerIds(principal.id(), principal.groupIds(), operation);
    }

    /** item_type_id -> marker_ids assigned to it, batched for callers filtering many types at once. */
    public Map<UUID, List<UUID>> getItemTypeMarkerAssignments() {
        return repo.getMarkerIdsByItemType();
    }

    /**
     * Single-item-type read check. Default deny: an item type with zero assigned markers is
     * invisible to everyone — there is no superuser bypass in this slice.
     */
    public boolean canReadItemType(NtrlocPrincipal principal, UUID itemTypeId) {
        List<UUID> markersOnType = repo.getMarkerIdsByItemType().getOrDefault(itemTypeId, List.of());
        if (markersOnType.isEmpty()) {
            return false;
        }
        Set<UUID> granted = effectiveMarkers(principal, ITEM_READ);
        return markersOnType.stream().anyMatch(granted::contains);
    }
}
