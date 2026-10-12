package org.ntrloc.graph.access;

public record StringNotEqualsPredicate(Context subject, String value) implements StringPredicate {
}
