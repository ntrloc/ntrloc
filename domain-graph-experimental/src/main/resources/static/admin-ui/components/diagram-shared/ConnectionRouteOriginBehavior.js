// Marks connection.manualRoute the moment a user actually drags a bendpoint or segment --
// features/bendpoints' BendpointMove.js and ConnectionSegmentMove.js are the only callers of
// modeling.updateWaypoints (verified via grep across the vendored bendpoints feature), so this
// command is an unambiguous "a person shaped this route by hand" signal, as opposed to
// ConnectionLayouter's own automatic re-routing, which never goes through the command stack at all
// (it assigns connection.waypoints directly during layout, before a command commits). See
// ConnectionLayouter.js's own comment for why that distinction matters: repairing a route is only
// the right call once it's actually hand-placed, never for one this layouter generated itself.
// Entirely generic -- no BPMN- or DMN-specific code -- so both ntrloc-process-editor and the DRD
// canvas share this one file unchanged (DRD doesn't currently wire up bendpoints at all, so this
// is a no-op there today, but costs nothing to include for when it does).
export default function ConnectionRouteOriginBehavior(eventBus) {
  eventBus.on('commandStack.connection.updateWaypoints.postExecuted', (event) => {
    event.context.connection.manualRoute = true;
  });
}

ConnectionRouteOriginBehavior.$inject = ['eventBus'];
