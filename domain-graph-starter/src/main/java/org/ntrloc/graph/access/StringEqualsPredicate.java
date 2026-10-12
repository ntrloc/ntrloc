package org.ntrloc.graph.access;

public record StringEqualsPredicate(Context subject, String value) implements StringPredicate {
}
