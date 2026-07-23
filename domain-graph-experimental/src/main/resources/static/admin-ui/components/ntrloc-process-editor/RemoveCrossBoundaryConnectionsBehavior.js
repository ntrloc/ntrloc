// A plain sequence flow can't legally cross a Sub-Process boundary (BpmnRuleProvider enforces
// this for connection.create/connection.reconnect -- see that file's header comment). Moving a
// shape doesn't go through either of those rules, though: dragging a task out of (or into, or
// between) a Sub-Process leaves its existing connections exactly as they were, now silently
// spanning two different containers. Same postExecuted-listener pattern as
// SubProcessAutoResizeBehavior (the actual, always-correct "this specific move just happened"
// signal, not a higher-level "shape moved" event) -- scoped to connections touching a shape that
// was just moved, deduped so a connection between two shapes moved together in the same gesture
// isn't checked twice.
export default function RemoveCrossBoundaryConnectionsBehavior(eventBus, modeling) {
  eventBus.on('commandStack.elements.move.postExecuted', (event) => {
    const shapes = event.context.shapes || [];
    const seen = new Set();

    shapes.forEach((shape) => {
      (shape.incoming || []).concat(shape.outgoing || []).forEach((connection) => {
        if (seen.has(connection.id)) return;
        seen.add(connection.id);

        if (connection.source.parent !== connection.target.parent) {
          modeling.removeConnection(connection);
        }
      });
    });
  });
}

RemoveCrossBoundaryConnectionsBehavior.$inject = ['eventBus', 'modeling'];
