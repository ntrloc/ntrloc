package org.ntrloc.graph.projection;

public record PropertyValuePredicate(String propertyName, Operator operator, String value) implements Predicate {}
