package org.ntrloc.graph.access;

import java.util.UUID;

// Step to a link itself, to read its own properties (as opposed to StepToItem, which steps past the
// link to the item on the other end). `each` marks whether a to-many link is iterated (correlated per
// instance via a CountedPredicate) or flattened.
public record StepToLink(UUID linkPerspectiveId, boolean each, Context next) implements Context {
}
