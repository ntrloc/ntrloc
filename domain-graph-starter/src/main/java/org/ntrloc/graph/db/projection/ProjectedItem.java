package org.ntrloc.graph.db.projection;

import org.springframework.lang.Nullable;

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
public record ProjectedItem(UUID itemId, String itemType, Map<String, Object> properties, Map<String, List<ProjectedLink>> links, @Nullable Map<String, ProjectedItemState> states, @Nullable ProjectedItemPermissions permissions, String displayLabel, @Nullable List<String> markers) {
}
