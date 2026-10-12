package org.ntrloc.graph.access;

import java.time.Instant;

public record DateListMaxPredicate(Context subject, DateComparisonOperator operator, Instant value, Instant valueTo) implements DateListPredicate {
}
