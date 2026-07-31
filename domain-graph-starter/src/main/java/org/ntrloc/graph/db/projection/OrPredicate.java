package org.ntrloc.graph.db.projection;

import java.util.List;

public record OrPredicate(List<Predicate> predicates) implements Predicate {}
