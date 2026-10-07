package org.ntrloc.graph.access;

// General-purpose string pattern match (substring/wildcard). Kept distinct from
// StringStartsWithPredicate deliberately -- the narrower operator spares an admin from having to
// write wildcard syntax for the common prefix case.
public record StringLikePredicate(String pattern) implements StringPredicate {
}
