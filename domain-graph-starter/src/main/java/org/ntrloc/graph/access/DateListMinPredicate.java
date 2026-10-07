package org.ntrloc.graph.access;

import java.time.Instant;

public record DateListMinPredicate(DateComparisonOperator operator, Instant value, Instant valueTo) implements DateListPredicate {
}
