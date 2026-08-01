import { forEach } from '../../../../min-dash/dist/index.js';

import {
  event as domEvent,
  query as domQuery,
  queryAll as domQueryAll
} from '../../../../min-dom/dist/index.js';

import {
  BENDPOINT_CLS,
  SEGMENT_DRAGGER_CLS,
  addBendpoint,
  addSegmentDragger,
  calculateSegmentMoveRegion,
  getConnectionIntersection
} from './BendpointUtil.js';

import {
  escapeCSS
} from '../../util/EscapeUtil.js';

import {
  pointsAligned,
  getMidPoint
} from '../../util/Geometry.js';

import {
  isPrimaryButton
} from '../../util/Mouse.js';

import {
  append as svgAppend,
  attr as svgAttr,
  classes as svgClasses,
  create as svgCreate,
  remove as svgRemove
} from '../../../../tiny-svg/dist/index.js';

import {
  translate
} from '../../util/SvgTransformUtil.js';

/**
 * @typedef {import('./BendpointMove.js').default} BendpointMove
 * @typedef {import('../../core/Canvas.js').default} Canvas
 * @typedef {import('./ConnectionSegmentMove.js').default} ConnectionSegmentMove
 * @typedef {import('../../core/EventBus.js').default} EventBus
 * @typedef {import('../interaction-events/InteractionEvents.js').default} InteractionEvents
 */

/**
 * A service that adds editable bendpoints to connections.
 *
 * @param {EventBus} eventBus
 * @param {Canvas} canvas
 * @param {InteractionEvents} interactionEvents
 * @param {BendpointMove} bendpointMove
 * @param {ConnectionSegmentMove} connectionSegmentMove
 */
export default function Bendpoints(
    eventBus, canvas, interactionEvents,
    bendpointMove, connectionSegmentMove) {

  /**
   * Returns true if intersection point is inside middle region of segment, adjusted by
   * optional threshold
   */
  function isIntersectionMiddle(intersection, waypoints, treshold) {
    var idx = intersection.index,
        p = intersection.point,
        p0, p1, mid, aligned, xDelta, yDelta;

    if (idx <= 0 || intersection.bendpoint) {
      return false;
    }

    p0 = waypoints[idx - 1];
    p1 = waypoints[idx];
    mid = getMidPoint(p0, p1),
    aligned = pointsAligned(p0, p1);
    xDelta = Math.abs(p.x - mid.x);
    yDelta = Math.abs(p.y - mid.y);

    return aligned && xDelta <= treshold && yDelta <= treshold;
  }

  /**
   * Calculates the threshold from a connection's middle which fits the two-third-region
   */
  function calculateIntersectionThreshold(connection, intersection) {
    var waypoints = connection.waypoints,
        relevantSegment, alignment, segmentLength, threshold;

    if (intersection.index <= 0 || intersection.bendpoint) {
      return null;
    }

    // segment relative to connection intersection
    relevantSegment = {
      start: waypoints[intersection.index - 1],
      end: waypoints[intersection.index]
    };

    alignment = pointsAligned(relevantSegment.start, relevantSegment.end);

    if (!alignment) {
      return null;
    }

    if (alignment === 'h') {
      segmentLength = relevantSegment.end.x - relevantSegment.start.x;
    } else {
      segmentLength = relevantSegment.end.y - relevantSegment.start.y;
    }

    // calculate threshold relative to 2/3 of segment length
    threshold = calculateSegmentMoveRegion(segmentLength) / 2;

    return threshold;
  }

  // ntrloc deviation from stock diagram-js: interior bendpoints are neither creatable nor
  // individually draggable -- only the two ends remain draggable, for reconnecting to a
  // different element, and only ConnectionSegmentMove (the "middle of segment" branch below)
  // remains as the way to reshape a route by hand. Manually adding/moving one interior point at
  // a time let a route go non-orthogonal (a bendpoint isn't constrained to stay aligned with its
  // neighbors) and fought against ConnectionLayouter's own job of finding a route -- keeping
  // reshaping to "drag a whole horizontal/vertical segment" guarantees every result stays
  // Manhattan, and leaves exactly one, unambiguous manual escape hatch.
  function activateBendpointMove(event, connection) {
    var waypoints = connection.waypoints,
        intersection = getConnectionIntersection(canvas, waypoints, event),
        threshold,
        isEndpoint;

    if (!intersection) {
      return;
    }

    threshold = calculateIntersectionThreshold(connection, intersection);

    var result;

    isEndpoint = intersection.index === 0 || intersection.index === waypoints.length - 1;

    if (isIntersectionMiddle(intersection, waypoints, threshold)) {
      result = connectionSegmentMove.start(event, connection, intersection.index);
    } else if (intersection.bendpoint && isEndpoint) {
      result = bendpointMove.start(event, connection, intersection.index, false);
    }

    // stop event propagation to avoid dragging from being handled by other
    // features
    if (result !== false) {
      return true;
    }
  }

  function bindInteractionEvents(node, eventName, element) {

    domEvent.bind(node, eventName, function(event) {
      interactionEvents.triggerMouseEvent(eventName, event, element);
      event.stopPropagation();
    });
  }

  function getBendpointsContainer(element, create) {

    var layer = canvas.getLayer('overlays'),
        gfx = domQuery('.djs-bendpoints[data-element-id="' + escapeCSS(element.id) + '"]', layer);

    if (!gfx && create) {
      gfx = svgCreate('g');
      svgAttr(gfx, { 'data-element-id': element.id });
      svgClasses(gfx).add('djs-bendpoints');

      svgAppend(layer, gfx);

      bindInteractionEvents(gfx, 'mousedown', element);
      bindInteractionEvents(gfx, 'click', element);
      bindInteractionEvents(gfx, 'dblclick', element);
    }

    return gfx;
  }

  function getSegmentDragger(idx, parentGfx) {
    return domQuery(
      '.djs-segment-dragger[data-segment-idx="' + idx + '"]',
      parentGfx
    );
  }

  // ntrloc deviation from stock diagram-js: only the two endpoint markers are drawn (interior
  // ones are neither interactive nor rendered -- see activateBendpointMove's comment), and no
  // floating "insert a point here" marker is created at all, since that gesture no longer exists.
  function createBendpoints(gfx, connection) {
    var waypoints = connection.waypoints;

    [ 0, waypoints.length - 1 ].forEach(function(idx) {
      var p = waypoints[idx];
      var bendpoint = addBendpoint(gfx);

      svgAppend(gfx, bendpoint);

      translate(bendpoint, p.x, p.y);
    });
  }

  function createSegmentDraggers(gfx, connection) {

    var waypoints = connection.waypoints;

    var segmentStart,
        segmentEnd,
        segmentDraggerGfx;

    for (var i = 1; i < waypoints.length; i++) {

      segmentStart = waypoints[i - 1];
      segmentEnd = waypoints[i];

      if (pointsAligned(segmentStart, segmentEnd)) {
        segmentDraggerGfx = addSegmentDragger(gfx, segmentStart, segmentEnd);

        svgAttr(segmentDraggerGfx, { 'data-segment-idx': i });

        bindInteractionEvents(segmentDraggerGfx, 'mousemove', connection);
      }
    }
  }

  function clearBendpoints(gfx) {
    forEach(domQueryAll('.' + BENDPOINT_CLS, gfx), function(node) {
      svgRemove(node);
    });
  }

  function clearSegmentDraggers(gfx) {
    forEach(domQueryAll('.' + SEGMENT_DRAGGER_CLS, gfx), function(node) {
      svgRemove(node);
    });
  }

  function addHandles(connection) {

    var gfx = getBendpointsContainer(connection);

    if (!gfx) {
      gfx = getBendpointsContainer(connection, true);

      createBendpoints(gfx, connection);
      createSegmentDraggers(gfx, connection);
    }

    return gfx;
  }

  function updateHandles(connection) {

    var gfx = getBendpointsContainer(connection);

    if (gfx) {
      clearSegmentDraggers(gfx);
      clearBendpoints(gfx);
      createSegmentDraggers(gfx, connection);
      createBendpoints(gfx, connection);
    }
  }

  function updateFloatingBendpointPosition(parentGfx, intersection) {
    var floating = domQuery('.floating', parentGfx),
        point = intersection.point;

    if (!floating) {
      return;
    }

    translate(floating, point.x, point.y);

    // mark as positioned so it may be shown; keeping it hidden until then
    // avoids a ghost bendpoint appearing at (0, 0)
    svgClasses(floating).add('positioned');
  }

  function updateSegmentDraggerPosition(parentGfx, intersection, waypoints) {

    var draggerGfx = getSegmentDragger(intersection.index, parentGfx),
        segmentStart = waypoints[intersection.index - 1],
        segmentEnd = waypoints[intersection.index],
        point = intersection.point,
        mid = getMidPoint(segmentStart, segmentEnd),
        alignment = pointsAligned(segmentStart, segmentEnd),
        draggerVisual, relativePosition;

    if (!draggerGfx) {
      return;
    }

    draggerVisual = getDraggerVisual(draggerGfx);

    relativePosition = {
      x: point.x - mid.x,
      y: point.y - mid.y
    };

    if (alignment === 'v') {

      // rotate position
      relativePosition = {
        x: relativePosition.y,
        y: relativePosition.x
      };
    }

    translate(draggerVisual, relativePosition.x, relativePosition.y);
  }

  eventBus.on('connection.changed', function(event) {
    updateHandles(event.element);
  });

  eventBus.on('connection.remove', function(event) {
    var gfx = getBendpointsContainer(event.element);

    if (gfx) {
      svgRemove(gfx);
    }
  });

  eventBus.on('element.marker.update', function(event) {

    var element = event.element,
        bendpointsGfx;

    if (!element.waypoints) {
      return;
    }

    bendpointsGfx = addHandles(element);

    if (event.add) {
      svgClasses(bendpointsGfx).add(event.marker);
    } else {
      svgClasses(bendpointsGfx).remove(event.marker);
    }
  });

  eventBus.on('element.mousemove', function(event) {

    var element = event.element,
        waypoints = element.waypoints,
        bendpointsGfx,
        intersection;

    if (waypoints) {
      bendpointsGfx = getBendpointsContainer(element, true);

      intersection = getConnectionIntersection(canvas, waypoints, event.originalEvent);

      if (!intersection) {
        return;
      }

      updateFloatingBendpointPosition(bendpointsGfx, intersection);

      if (!intersection.bendpoint) {
        updateSegmentDraggerPosition(bendpointsGfx, intersection, waypoints);
      }

    }
  });

  eventBus.on('element.mousedown', function(event) {

    if (!isPrimaryButton(event)) {
      return;
    }

    var originalEvent = event.originalEvent,
        element = event.element;

    if (!element.waypoints) {
      return;
    }

    return activateBendpointMove(originalEvent, element);
  });

  eventBus.on('selection.changed', function(event) {
    var newSelection = event.newSelection,
        primary = newSelection[0];

    if (primary && primary.waypoints) {
      addHandles(primary);
    }
  });

  eventBus.on('element.hover', function(event) {
    var element = event.element;

    if (element.waypoints) {
      addHandles(element);
    }
  });

  eventBus.on('element.out', function(event) {
    var element = event.element;

    if (!element.waypoints) {
      return;
    }

    var bendpointsGfx = getBendpointsContainer(element);

    if (!bendpointsGfx) {
      return;
    }

    // reset floating bendpoint so it does not re-appear at a stale position
    // before being re-positioned on the next element.mousemove
    var floating = domQuery('.floating', bendpointsGfx);

    if (floating) {
      svgClasses(floating).remove('positioned');
    }
  });

  // update bendpoint container data attribute on element ID change
  eventBus.on('element.updateId', function(context) {
    var element = context.element,
        newId = context.newId;

    if (element.waypoints) {
      var bendpointContainer = getBendpointsContainer(element);

      if (bendpointContainer) {
        svgAttr(bendpointContainer, { 'data-element-id': newId });
      }
    }
  });

  // API

  this.addHandles = addHandles;
  this.updateHandles = updateHandles;
  this.getBendpointsContainer = getBendpointsContainer;
  this.getSegmentDragger = getSegmentDragger;
}

Bendpoints.$inject = [
  'eventBus',
  'canvas',
  'interactionEvents',
  'bendpointMove',
  'connectionSegmentMove'
];



// helper /////////////

function getDraggerVisual(draggerGfx) {
  return domQuery('.djs-visual', draggerGfx);
}