package org.ntrloc.graph.access;

// The empty context: the item the pipeline belongs to. The default/root case -- nothing has
// established a different context yet.
public record Self() implements Context {
}
