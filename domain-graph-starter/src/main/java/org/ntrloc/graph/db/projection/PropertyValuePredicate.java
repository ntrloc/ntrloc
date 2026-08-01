package org.ntrloc.graph.db.projection;

public record PropertyValuePredicate(String propertyName, Operator operator, String value) implements Predicate {}
