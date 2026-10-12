package org.ntrloc.graph.access;

// "self" as a comparand, not just a literal -- the one thing that let a reverse sub-traversal compare
// back against the pipeline's own root entity from arbitrary depth.
public record EqualsSelfPredicate(Context subject) implements IdentityPredicate {
}
