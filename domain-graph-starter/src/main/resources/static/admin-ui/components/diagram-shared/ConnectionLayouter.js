import inherits from '../../vendor/diagram-js/inherits-browser/dist/index.es.js';
import BaseLayouter from '../../vendor/diagram-js/diagram-js/lib/layout/BaseLayouter.js';
import CroppingConnectionDocking from '../../vendor/diagram-js/diagram-js/lib/layout/CroppingConnectionDocking.js';
import { getMid } from '../../vendor/diagram-js/diagram-js/lib/layout/LayoutUtil.js';
import { connectRectangles, repairConnection, withoutRedundantPoints } from '../../vendor/diagram-js/diagram-js/lib/layout/ManhattanLayout.js';
import { routeAroundObstacles } from './ObstacleAvoidingRouter.js';

// diagram-js's default BaseLayouter connects plain center-to-center and never crops to either
// shape's actual boundary -- fine as a fallback, but it's also what MoveHelper.moveClosure calls
// (via modeling.layoutConnection) every time a shape moves, which is why dragging produced lines
// ending at a shape's center, or at a stale point from before the move. Neither bpmn-js's nor a
// generic diagram-js module wires cropping in automatically, so this does explicitly what's
// needed: docking to where a route actually intersects each shape's boundary -- via
// CroppingConnectionDocking, which already implements exactly that using each shape's real
// rendered path (the owning renderer's getShapePath/getConnectionPath, through graphicsFactory).
// Entirely generic -- no BPMN- or DMN-specific code -- so both ntrloc-process-editor and the DRD
// canvas share this one file unchanged.
export default function ConnectionLayouter(elementRegistry, graphicsFactory) {
  BaseLayouter.call(this);
  this._elementRegistry = elementRegistry;
  this._docking = new CroppingConnectionDocking(elementRegistry, graphicsFactory);
}

ConnectionLayouter.$inject = ['elementRegistry', 'graphicsFactory'];

inherits(ConnectionLayouter, BaseLayouter);

// A waypoint from an existing route may carry its pre-crop position under .original (see
// CroppingConnectionDocking's dockingToPoint) -- that's the point repairConnection actually wants
// to reason about, not the already-cropped boundary point. Falls back to a shape's own center
// when there's no prior waypoint to anchor to at all (a fresh connection).
function connectionDocking(point, shape) {
  return point ? (point.original || point) : getMid(shape);
}

// "Obstacles" for routing around are every other shape in the diagram -- excluding source and
// target's own ancestor chain (a route between two children of the same Sub-Process would
// otherwise treat that Sub-Process's own bounds as something to avoid, which is impossible: the
// whole route necessarily happens inside it) and their descendants (an expanded Sub-Process's own
// children aren't obstacles for a route docking on that Sub-Process's outer boundary). Connections
// themselves (elements with .waypoints instead of width/height) and hidden/collapsed elements
// aren't real visual obstacles either.
//
// Excluded by .id, not object identity: ConnectionMovePreview.js's live drag preview calls this
// with "virtual" shallow-copied source/target (a moved shape's real x/y hasn't changed yet, only
// the copy's has -- see that file's own header comment on why), so a strict Set-of-objects match
// would silently fail to exclude the real, still-in-elementRegistry shape at all, leaving the
// router avoiding a shape's own stale, pre-drag position as if it were a completely different
// obstacle in its way (confirmed live: this alone accounted for the odd, drag-only detours that
// disappeared the moment the drag actually committed and source/target became the same objects
// again).
function getObstacles(elementRegistry, source, target) {
  const excludedIds = new Set();
  function addSelfAndAncestors(shape) {
    for (let s = shape; s; s = s.parent) excludedIds.add(s.id);
  }
  function addDescendants(shape) {
    (shape.children || []).forEach((child) => {
      excludedIds.add(child.id);
      addDescendants(child);
    });
  }
  addSelfAndAncestors(source);
  addSelfAndAncestors(target);
  addDescendants(source);
  addDescendants(target);

  return elementRegistry.getAll().filter((el) => (
    !el.waypoints && el.width && el.height && !el.hidden && !excludedIds.has(el.id)
  ));
}

// Whether an already-computed route actually needs the (more expensive) obstacle-avoiding router
// at all -- most moves don't put a connection anywhere near unrelated content, so it's cheap to
// check the simple route first and only fall back when it's actually warranted. Matters because
// ConnectionMovePreview.js calls layoutConnection on every drag tick, not just on drop.
function crossesObstacle(waypoints, obstacles) {
  const PADDING = 15;
  const inflated = obstacles.map((r) => ({
    x: r.x - PADDING, y: r.y - PADDING, width: r.width + 2 * PADDING, height: r.height + 2 * PADDING,
  }));
  for (let i = 1; i < waypoints.length; i++) {
    const a = waypoints[i - 1], b = waypoints[i];
    const blocked = a.y === b.y
      ? inflated.some((r) => a.y > r.y && a.y < r.y + r.height && Math.max(a.x, b.x) > r.x && Math.min(a.x, b.x) < r.x + r.width)
      : inflated.some((r) => a.x > r.x && a.x < r.x + r.width && Math.max(a.y, b.y) > r.y && Math.min(a.y, b.y) < r.y + r.height);
    if (blocked) return true;
  }
  return false;
}

ConnectionLayouter.prototype.layoutConnection = function(connection, hints) {
  hints = hints || {};
  const source = hints.source || connection.source;
  const target = hints.target || connection.target;

  if (hints.waypoints && hints.waypoints.length) {
    // An explicit route, e.g. ReconnectConnectionHandler forwarding BendpointMove's reversed-
    // reconnect case -- honor it outright rather than either branch below. Checking .length, not
    // just truthiness, matters: ConnectionPreview.js's drawPreview passes
    // `hints.waypoints || connection.waypoints`, and during a live connect-drag (before any route
    // exists) that's `[]` -- truthy, but not an explicit route -- so a bare `if (hints.waypoints)`
    // took this branch anyway and set connection.waypoints to that same empty array. Everything
    // downstream (getCroppedWaypoints -> getDockingPoint -> _getIntersection ->
    // graphicsFactory.getConnectionPath) then built a path from zero waypoints, which the
    // path-intersection library's pathToCurve can't parse -- confirmed live as the exact
    // "Cannot read properties of null (reading 'length')" thrown from isPathCurve the moment the
    // mouse entered a valid drop target (the only point cropping actually runs the malformed path
    // through real intersection math instead of short-circuiting on a missing target).
    connection.waypoints = hints.waypoints;
  } else if (connection.manualRoute) {
    // Only a route a user actually shaped by hand (ConnectionRouteOriginBehavior sets this the
    // moment features/bendpoints' UpdateWaypointsHandler records a manual drag -- see that file)
    // is worth repairing rather than regenerating: repairConnection's whole point is to nudge just
    // the end that moved while leaving everything else -- deliberately placed -- untouched.
    const waypoints = connection.waypoints;
    const connectionStart = hints.connectionStart || connectionDocking(waypoints[0], source);
    const connectionEnd = hints.connectionEnd || connectionDocking(waypoints[waypoints.length - 1], target);

    // repairConnection reads hints.connectionStart/hints.connectionEnd itself (as "did this end
    // move" flags, separate from the actual connectionStart/connectionEnd point args above) to
    // decide whether either end changed at all -- forwarding the original hints through is load-
    // bearing, not cosmetic: without it, both flags read as falsy and repairConnection takes its
    // own "nothing changed" shortcut, returning connection.waypoints completely untouched (this
    // was confirmed live: a moved shape's connection didn't re-route at all until this was fixed).
    connection.waypoints = withoutRedundantPoints(repairConnection(
      source, target, connectionStart, connectionEnd, waypoints,
      { preferredLayouts: ['straight', 'h:h'], ...hints },
    ));
  } else {
    // No manually-placed route to preserve: always recompute from scratch (straight when aligned,
    // a minimal elbow otherwise) rather than asking repairConnection to nudge whatever's already
    // there. connectRectangles has no memory of the prior route -- it derives directions purely
    // from the two shapes' *current* positions -- so unlike repair it can never get stuck.
    //
    // This replaced an earlier version that always repaired, on the theory that repairConnection's
    // hints.connectionStart/connectionEnd handling would resolve both the fresh-2-point case and
    // the already-bent case correctly. In practice repair's "nudge, preserve structure" philosophy
    // -- exactly what you want for a genuinely hand-placed bend -- kept producing worse-than-fresh
    // routes for auto-generated ones, in three distinct ways, all confirmed live:
    //  1. A repaired endpoint can land exactly on a shape corner via a degenerate collinear
    //     approach segment, then stay frozen there since re-cropping a stale interior segment
    //     against the shape's new position stops finding an intersection at all once it's moved
    //     far enough.
    //  2. Once repair's very-last-resort nudge lands on a corner, it *stays* pinned there through
    //     any number of further moves: "0% along the top edge" and "0% along the left edge" are
    //     the same point, so every subsequent repair keeps re-deriving that same relative 0%
    //     position on the shape's current bounds -- the connection visually tracks nothing until
    //     the nudge fails outright and repair finally falls back to connectRectangles anyway.
    //  3. Dragging a shape through a complex path and back can leave repair carrying forward an
    //     interior bendpoint that's no longer geometrically necessary, so a connection that should
    //     be back to a clean 2-segment elbow instead keeps a redundant extra jog from the detour.
    // A fresh connectRectangles call every time sidesteps all three at once, and is cheap enough
    // (pure geometry, no DOM) to not matter performance-wise.
    //
    // Deliberately NOT forwarding hints.connectionStart/connectionEnd here (unlike the manualRoute
    // branch above) -- those are MoveHelper's own getMovedSourceAnchor/getMovedTargetAnchor
    // output, which reprojects the *previous* anchor's relative position onto the shape's new
    // bounds. That's exactly the right anchor to repair from when preserving a hand-placed route,
    // but it's also exactly how history leaks back in for an auto route: if a prior layout ever
    // left an anchor sitting off-center (any of the three failure modes above), the reprojected
    // hint stays off-center too, even once the shapes are back in plain vertical/horizontal
    // alignment (confirmed live: a straight-aligned pair came back bent around a stale off-center
    // hint that traced back to an earlier corner-docked position, well after that position was no
    // longer relevant). Passing no start/end lets connectRectangles fall back to each shape's own
    // getMid() -- the same geometry-only, no-history anchor a brand new connection would use.
    //
    // For a diagonal (non-explicit-orientation) pair of shapes, Manhattan's own getDirections()
    // only picks a fixed 'v:v'/'h:h' for the four *aligned* orientations (top/bottom/left/right)
    // and defers entirely to whichever preference we hand it otherwise (confirmed by reading
    // getDirections directly) -- so which of the four h/v pairings to even ask for is on us.
    // 'h:h'/'v:v' always route through a mid-point detour (getSimpleBendpoints' own S-curve case)
    // even when a single turn would do, so those only win the comparison below when a shape's
    // bounds genuinely force it. That leaves 'h:v' and 'v:h' -- the two single-turn L-shapes -- as
    // the two real candidates, and they always tie on point count (3 points either way): picking
    // between them isn't a "which has fewer points" question at all, it's "which turn happens
    // first."
    //
    // Turning too early used to be the actual failure mode here: for a shape pair separated
    // mostly horizontally, entering the connection's *short* axis (vertical) first means covering
    // that short distance immediately, right next to the source -- typically still in the thick
    // of other diagram content -- before the long horizontal leg even starts. Deferring the turn
    // to the end of the longer leg is still a fine, cheap default when nothing's actually in the
    // way, so it stays as the fast-path pick below -- but it's a heuristic about the two shapes'
    // relative position, not a real check against the rest of the diagram, so it can still be
    // wrong (confirmed live: a wide subprocess connecting up and across to a small gateway far to
    // its right came back cutting straight back through most of the diagram regardless of which
    // axis went first, because the diagram had other content the heuristic never looked at). The
    // crossesObstacle/routeAroundObstacles pair below is what actually looks.
    const candidates = [
      { layout: 'h:h', wp: withoutRedundantPoints(connectRectangles(source, target, undefined, undefined, { preferredLayouts: ['straight', 'h:h'] })) },
      { layout: 'v:v', wp: withoutRedundantPoints(connectRectangles(source, target, undefined, undefined, { preferredLayouts: ['straight', 'v:v'] })) },
      { layout: 'h:v', wp: withoutRedundantPoints(connectRectangles(source, target, undefined, undefined, { preferredLayouts: ['straight', 'h:v'] })) },
      { layout: 'v:h', wp: withoutRedundantPoints(connectRectangles(source, target, undefined, undefined, { preferredLayouts: ['straight', 'v:h'] })) },
    ];

    const minPoints = Math.min(...candidates.map((c) => c.wp.length));
    const simplest = candidates.filter((c) => c.wp.length === minPoints);

    const sourceMid = getMid(source);
    const targetMid = getMid(target);
    const travelHorizontalFirst = Math.abs(targetMid.x - sourceMid.x) >= Math.abs(targetMid.y - sourceMid.y);
    const preferred = simplest.find((c) => c.layout.startsWith(travelHorizontalFirst ? 'h' : 'v')) || simplest[0];

    // The heuristic pick above is cheap and right most of the time, so only pay for a real
    // obstacle check (and the considerably more expensive grid-search fallback) when it's
    // actually warranted -- ConnectionMovePreview.js calls layoutConnection on every drag tick,
    // not just on drop, so this path needs to stay fast in the common case.
    const obstacles = getObstacles(this._elementRegistry, source, target);
    if (crossesObstacle(preferred.wp, obstacles)) {
      const routed = routeAroundObstacles(source, target, obstacles);
      connection.waypoints = routed ? withoutRedundantPoints(routed) : preferred.wp;
    } else {
      connection.waypoints = preferred.wp;
    }
  }

  return this._docking.getCroppedWaypoints(connection, source, target);
};
