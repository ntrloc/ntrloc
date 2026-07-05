package org.ntrloc.graph.db.projection;

import java.util.List;

public record AndPredicate(List<Predicate> predicates) implements Predicate {}
