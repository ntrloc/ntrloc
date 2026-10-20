package org.ntrloc.graph.projection;

import com.fasterxml.jackson.annotation.JsonIgnore;
import org.springframework.lang.Nullable;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

// states is keyed by state machine name (resolved from the register's id-keyed storage at
// assembly time -- see RegisterPartitionManager's own comments on this), a sibling of properties,
// not folded into it: state-machine info is a distinct concept from an item's own properties. Null
// (not an empty map) for the common case of an item type with no state machines at all.
//
// displayLabel is always populated (never null in practice) -- computed at assembly time from the
// item type's effective display-label pattern (RegisterPartitionManager.computeDisplayLabel), or
// the item's own id if no pattern is configured or evaluation failed. Every UI consumer should use
// this directly rather than guessing a label from properties.
//
// markers: a superuser gets every marker on the item; a non-superuser gets only the markers they
// hold an item:read grant on (the existence of markers they can't read is never disclosed). Since a
// non-superuser only sees an item at all because one of its markers is granted, this list is
// non-empty in practice for them. Currently only populated on the top-level projected item, not on
// any item reached through a link's own nested ProjectedItem -- a real follow-up, not an oversight
// (RegisterPartitionManager.fetchLinksByItem skips fetching marker ids at all for a superuser
// today, purely as a filtering-cost optimization that predates this field existing).
//
// createdDate/createdBy read straight off the register row -- that row already carries the
// authoritative values (set at staging/commit time by LedgerRegisterCoordinatorImpl, see
// RegisterPartitionManager.insertItemRow's own comment), so this is never a ledger read at
// projection time. createdDate/createdBy never change after the item's first commit, no matter
// how many updates follow.
//
// updatedDate/updatedBy are @Nullable and genuinely null for an item that's only ever been
// created -- there is no "updated" until a real update happens, not an updated equal to the
// item's own creation. Once the item has been updated at least once, they reflect the most
// recent update's actor/time, same as createdDate/createdBy do for creation.
//
// createdBy/updatedBy are UserInfo, resolved from the register row's own raw
// createdByUserId/updatedByUserId -- RegisterPartitionManager populates only the *UserId fields
// (register must never import the security partition, directly or via an injected resolver: it
// has no way to produce a real UserInfo), leaving createdBy/updatedBy null; EntityManagerImpl
// (which already legitimately crosses partition boundaries) resolves them afterward, batched
// across the whole page, before anything reaches a controller. createdByUserId/updatedByUserId
// are @JsonIgnore -- an implementation seam for that enrichment step, never part of the actual
// wire response. updatedBy is additionally null whenever updatedByUserId itself is null.
public record ProjectedItem(UUID itemId, String itemType, Map<String, Object> properties, Map<String, List<ProjectedLink>> links, @Nullable Map<String, ProjectedItemState> states, @Nullable ProjectedItemPermissions permissions, String displayLabel, @Nullable List<String> markers,
                             OffsetDateTime createdDate, @JsonIgnore UUID createdByUserId, @Nullable UserInfo createdBy,
                             @Nullable OffsetDateTime updatedDate, @JsonIgnore @Nullable UUID updatedByUserId, @Nullable UserInfo updatedBy) {
}
