package org.ntrloc.graph.access;

// How much of a set of booleans needs to hold -- ALL = completely, ANY = at least partially, NONE =
// not at all. Shared by two structurally different mechanisms that both need the same vocabulary: a
// TotalityPredicate combines several *predicates* nested inside one condition's own fact-reading
// (producing that condition's single boolean); a Gate combines several already-resolved *condition*
// pin values to decide whether a carrier block fires. These operate at different layers (predicates
// that haven't resolved to a condition yet, vs. conditions that already have) and never substitute for
// each other -- Quantity is just the one concept both of them need, standalone rather than nested
// inside either, since nesting it in one would wrongly imply the other borrowed it.
public enum Quantity {
    ANY,
    ALL,
    NONE
}
