package org.ntrloc.graph.access;

import java.util.Map;
import java.util.UUID;

// Where a pipeline's own diagram places each of its elements -- every carrier block id, Start/End,
// and every signal/pin id that can appear as a node. Deliberately separate from Pipeline itself
// (see MarkerDecisionPipelineRepository's own comment): this is the "how it's drawn" layer, stored
// alongside the pipeline it describes, never embedded in or read by anything that evaluates one --
// same split BPMN itself draws between a <process> and its <bpmndi:BPMNDiagram>.
//
// No validation against the pipeline's own ids here or anywhere server-side -- a stray entry for a
// since-deleted element, or a missing one for a newly added element, is cosmetically inert, never
// behaviorally dangerous, so the editor that writes this owns keeping it in sync rather than the
// server policing it. Revisit if that ever proves unworkable in practice.
//
// edgeBends: one admin-adjusted value per edge, keyed by the same edge key the diagram itself uses
// (e.g. "<outputPinId>-><inputPinId>", "signal:<pinId>=><ownerId>") -- the editor's own auto-routed
// orthogonal path always has exactly one adjustable bend (a vertical run's own x, or a backward
// detour's own lane y; see markerPipelineLayout.js's orthogonalPath), so one number per edge is
// enough to override it. Absent for an edge the admin never touched -- the editor computes its own
// default bend the same way it always has.
public record PipelineLayout(Map<UUID, Point> positions, Map<String, Double> edgeBends) {

    public PipelineLayout {
        if (positions == null) positions = Map.of();
        if (edgeBends == null) edgeBends = Map.of();
    }
}
