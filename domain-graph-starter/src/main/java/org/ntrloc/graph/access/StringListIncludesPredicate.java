package org.ntrloc.graph.access;

// Set membership -- deliberately distinct from StringLikePredicate's substring match despite both
// reading as "contains" in plain English. Different operand types, different semantics.
public record StringListIncludesPredicate(Context subject, String value) implements StringListPredicate {
}
