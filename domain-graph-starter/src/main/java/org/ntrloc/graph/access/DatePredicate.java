package org.ntrloc.graph.access;

import java.time.Instant;

// Dates get before/after instead of reusing generic >/< -- same reasoning as startsWith existing
// alongside like: a narrower, domain-appropriate operator an admin reads naturally, even though the
// underlying comparison is identical. One class with an operator field, same as NumberPredicate, for
// the same reason: every date comparison is safely interchangeable. valueTo is only used for BETWEEN.
public record DatePredicate(DateComparisonOperator operator, Instant value, Instant valueTo) implements Predicate {
}
