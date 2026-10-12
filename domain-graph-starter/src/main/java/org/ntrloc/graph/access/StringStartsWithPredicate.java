package org.ntrloc.graph.access;

public record StringStartsWithPredicate(Context subject, String prefix) implements StringPredicate {
}
