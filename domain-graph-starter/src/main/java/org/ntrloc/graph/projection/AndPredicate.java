package org.ntrloc.graph.projection;

import java.util.List;

public record AndPredicate(List<Predicate> predicates) implements Predicate {}
