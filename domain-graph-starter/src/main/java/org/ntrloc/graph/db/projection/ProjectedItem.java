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
public record ProjectedItem(UUID itemId, String itemType, Map<String, Object> properties, Map<String, List<ProjectedLink>> links, @Nullable Map<String, ProjectedItemState> states, @Nullable ProjectedItemPermissions permissions, String displayLabel) {
}
