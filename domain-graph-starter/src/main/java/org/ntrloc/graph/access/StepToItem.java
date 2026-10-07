package org.ntrloc.graph.access;

import java.util.UUID;

// Step to the item on the other end of a link -- either as a continuation from a StepToLink (the
// link's "target"/"link target"), or directly from an item context, skipping the link itself (the
// "linked item" shortcut, for when the link's own properties don't matter). Both land in the same
// kind of context afterward; this one type covers both paths. `each` marks whether a to-many link is
// iterated or flattened.
public record StepToItem(UUID linkPerspectiveId, boolean each, Context next) implements Context {
}
