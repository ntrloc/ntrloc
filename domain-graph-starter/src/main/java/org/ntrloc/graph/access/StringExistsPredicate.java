package org.ntrloc.graph.access;

// Valid only where the schema allows the property to be absent -- see the link/property existence-
// check rule in "Visual / authoring language" in the design notes.
public record StringExistsPredicate(Context subject) implements StringPredicate {
}
