import { append as svgAppend, attr as svgAttr, create as svgCreate, remove as svgRemove } from '../../vendor/diagram-js/tiny-svg/dist/index.js';
import { componentsToPath } from '../../vendor/diagram-js/diagram-js/lib/util/RenderUtil.js';
import { getMovedSourceAnchor, getMovedTargetAnchor } from '../../vendor/diagram-js/diagram-js/lib/features/modeling/cmd/helper/AnchorsHelper.js';

// diagram-js's own MovePreview (vendor/.../features/move/MovePreview.js) gives the shape(s) being
// dragged a live "ghost" that follows the cursor, but explicitly excludes their connections from
// that mechanism (see its removeEdges() helper) -- connections just fade to 30% opacity and sit at
// their pre-drag position for the whole drag, only snapping to the correct final route once the
// drag completes (via ConnectionLayouter, triggered by MoveHelper on drop). This fills that gap: a
// small preview layer that redraws each affected connection's route on every drag tick, delegating
// to the very same injected `layouter` (ConnectionLayouter) the real commit uses -- not a
// second, hand-rolled routing approximation -- so the live dashed preview always matches what
// dropping right now would actually produce (Manhattan-routed if unbent, bend-preserved if the
// connection already has user-placed waypoints), rather than always previewing a straight line and
// only snapping to the real shape at drop. Layout runs against "virtual" shape/connection objects
// (a shallow copy, own waypoints array) rather than the real model -- the real model doesn't (and
// shouldn't) change until the drag actually completes, so a cancelled drag (e.g. Escape) can't
// leave it corrupted. Entirely generic -- no BPMN- or DMN-specific code -- so both
// ntrloc-process-editor and the DRD canvas share this one file unchanged.
export default function ConnectionMovePreview(eventBus, canvas, layouter) {
  let layer = null;
  const lines = new Map();

  function virtualShape(shape, dx, dy) {
    return { ...shape, x: shape.x + dx, y: shape.y + dy };
  }

  // Only "external" connections need a live preview -- one where both endpoints are moving
  // together keep their existing relative waypoints unchanged (diagram-js's own moveConnection
  // path already handles that correctly, matching MoveHelper.moveClosure's own enclosed/external
  // distinction).
  function affectedConnections(shapes) {
    const shapeIds = new Set(shapes.map((s) => s.id));
    const connections = new Map();
    shapes.forEach((shape) => {
      (shape.incoming || []).concat(shape.outgoing || []).forEach((c) => {
        if (shapeIds.has(c.source.id) && shapeIds.has(c.target.id)) return;
        connections.set(c.id, c);
      });
    });
    return [...connections.values()];
  }

  eventBus.on('shape.move.start', (event) => {
    const shapes = event.context.shapes;
    const connections = affectedConnections(shapes);
    if (!connections.length) return;

    layer = svgCreate('g');
    svgAppend(canvas.getActiveLayer(), layer);

    connections.forEach((connection) => {
      const line = svgCreate('path', {
        fill: 'none', stroke: 'var(--muted)', strokeWidth: 2, strokeDasharray: '4,2',
      });
      svgAppend(layer, line);
      lines.set(connection.id, { connection, line });
    });

    event.context.ntrlocMovedShapeIds = new Set(shapes.map((s) => s.id));
  });

  eventBus.on('shape.move.move', (event) => {
    if (!layer) return;
    const movedIds = event.context.ntrlocMovedShapeIds;
    const dx = event.dx || 0, dy = event.dy || 0;

    const delta = { x: dx, y: dy };

    lines.forEach(({ connection, line }) => {
      const sourceMoved = movedIds.has(connection.source.id);
      const targetMoved = movedIds.has(connection.target.id);
      const source = sourceMoved ? virtualShape(connection.source, dx, dy) : connection.source;
      const target = targetMoved ? virtualShape(connection.target, dx, dy) : connection.target;

      // A shallow copy with its own waypoints array -- layoutConnection mutates
      // connection.waypoints in place, and it must not touch the real connection mid-drag.
      const virtualConnection = { ...connection, waypoints: connection.waypoints.slice() };

      // repairConnection (inside layoutConnection) treats hints.connectionStart/connectionEnd as
      // "did this end move, and if so, to where" -- not booleans, the actual re-anchored point, or
      // falsy if that end didn't move -- exactly mirroring what MoveHelper.moveClosure computes for
      // a real, committed move (see its own sourceMoved/targetMoved + getMovedSourceAnchor/
      // getMovedTargetAnchor calls). Without these, repairConnection can't tell either end moved at
      // all and takes its "nothing changed" shortcut, leaving straight connections stuck diagonal
      // during the drag even though the final commit would square them up.
      const connectionStart = sourceMoved && getMovedSourceAnchor(virtualConnection, source, delta);
      const connectionEnd = targetMoved && getMovedTargetAnchor(virtualConnection, target, delta);

      const cropped = layouter.layoutConnection(virtualConnection, {
        source, target, connectionStart, connectionEnd,
      });

      svgAttr(line, {
        d: componentsToPath(cropped.map((p, i) => [i === 0 ? 'M' : 'L', p.x, p.y])),
      });
    });
  });

  eventBus.on(['shape.move.end', 'shape.move.cancel', 'shape.move.cleanup'], () => {
    if (layer) {
      svgRemove(layer);
      layer = null;
    }
    lines.clear();
  });
}

ConnectionMovePreview.$inject = ['eventBus', 'canvas', 'layouter'];
