package org.ntrloc.graph.projection;

import java.util.List;

public record OrPredicate(List<Predicate> predicates) implements Predicate {}
