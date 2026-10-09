package org.ntrloc.graph.access;

// A node's position in a pipeline diagram -- purely visual, never read by PipelineEvaluator or
// WatchDerivation. Plain coordinates, not pixel/unit-specific; the editor that writes them owns
// what coordinate system they're in.
public record Point(double x, double y) {
}
