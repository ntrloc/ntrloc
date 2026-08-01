import inherits from '../../vendor/diagram-js/inherits-browser/dist/index.es.js';
import BaseRenderer from '../../vendor/diagram-js/diagram-js/lib/draw/BaseRenderer.js';
import { componentsToPath, createLine } from '../../vendor/diagram-js/diagram-js/lib/util/RenderUtil.js';
import { append as svgAppend, create as svgCreate } from '../../vendor/diagram-js/tiny-svg/dist/index.js';
import {
  START_EVENT, END_EVENT, EXCLUSIVE_GATEWAY, PARALLEL_GATEWAY, CALL_ACTIVITY, SUB_PROCESS,
  SEQUENCE_FLOW, SCRIPT_TASK, USER_TASK, isTask, isDmnTask, isCallActivity, isExpandedSubProcess,
  isTimerStartEvent, getSubProcessToggleMarkerBounds, getSubProcessLoopMarkerBounds, getLoopType,
  LOOP_TYPE_STANDARD, LOOP_TYPE_MI_PARALLEL, LOOP_TYPE_MI_SEQUENTIAL,
} from './bpmn-elements.js';

function polar(cx, cy, r, deg) {
  const rad = (deg * Math.PI) / 180;
  return { x: cx + r * Math.cos(rad), y: cy + r * Math.sin(rad) };
}

// Standard Loop's marker: a circular arrow, drawn as a many-segment polyline (not an SVG arc
// command) plus an arrowhead triangle oriented from the actual last-segment tangent -- sidesteps
// having to reason about `A`'s large-arc/sweep flag semantics for a 300 degree sweep, at a size
// where a 24-segment polyline is visually indistinguishable from a true arc anyway.
function drawLoopArrow(parentGfx, bounds, color) {
  const cx = bounds.x + bounds.width / 2;
  const cy = bounds.y + bounds.height / 2;
  const r = bounds.width / 2 - 1;
  const startDeg = -60;
  const endDeg = 240;
  const steps = 24;
  const points = [];
  for (let i = 0; i <= steps; i += 1) {
    points.push(polar(cx, cy, r, startDeg + ((endDeg - startDeg) * i) / steps));
  }
  const d = points.map((p, i) => `${i === 0 ? 'M' : 'L'} ${p.x} ${p.y}`).join(' ');
  const arc = svgCreate('path', { d, fill: 'none', stroke: color, strokeWidth: 1.5, strokeLinecap: 'round' });

  const tip = points[points.length - 1];
  const prev = points[points.length - 2];
  const dx = tip.x - prev.x, dy = tip.y - prev.y;
  const len = Math.hypot(dx, dy) || 1;
  const ux = dx / len, uy = dy / len;
  const px = -uy, py = ux;
  const backCenter = { x: tip.x - ux * 5, y: tip.y - uy * 5 };
  const left = { x: backCenter.x + px * 3, y: backCenter.y + py * 3 };
  const right = { x: backCenter.x - px * 3, y: backCenter.y - py * 3 };
  const arrowHead = svgCreate('polygon', {
    points: `${tip.x},${tip.y} ${left.x},${left.y} ${right.x},${right.y}`,
    fill: color,
  });
  svgAppend(parentGfx, arc);
  svgAppend(parentGfx, arrowHead);
}

// Multi-Instance's marker: three parallel bars -- vertical side-by-side for Parallel, horizontal
// stacked for Sequential -- the standard BPMN glyph pair for this distinction.
function drawMultiInstanceBars(parentGfx, bounds, color, sequential) {
  const { x, y, width, height } = bounds;
  const pad = 2;
  const count = 3;
  if (sequential) {
    const gap = (height - pad * 2) / (count - 1);
    for (let i = 0; i < count; i += 1) {
      const ly = y + pad + gap * i;
      svgAppend(parentGfx, svgCreate('line', {
        x1: x + pad, y1: ly, x2: x + width - pad, y2: ly, stroke: color, strokeWidth: 1.5, strokeLinecap: 'round',
      }));
    }
  } else {
    const gap = (width - pad * 2) / (count - 1);
    for (let i = 0; i < count; i += 1) {
      const lx = x + pad + gap * i;
      svgAppend(parentGfx, svgCreate('line', {
        x1: lx, y1: y + pad, x2: lx, y2: y + height - pad, stroke: color, strokeWidth: 1.5, strokeLinecap: 'round',
      }));
    }
  }
}

// Shared by both the collapsed and expanded Sub-Process draw paths below -- one dispatcher on
// getLoopType so neither path can drift out of sync with the other on which glyph a given
// loopCharacteristics shape maps to.
function drawSubProcessLoopMarker(parentGfx, element, color) {
  const loopType = getLoopType(element);
  if (loopType === LOOP_TYPE_STANDARD) {
    drawLoopArrow(parentGfx, getSubProcessLoopMarkerBounds(element), color);
  } else if (loopType === LOOP_TYPE_MI_PARALLEL) {
    drawMultiInstanceBars(parentGfx, getSubProcessLoopMarkerBounds(element), color, false);
  } else if (loopType === LOOP_TYPE_MI_SEQUENTIAL) {
    drawMultiInstanceBars(parentGfx, getSubProcessLoopMarkerBounds(element), color, true);
  }
}

// Per-task-type accent colors and corner mark -- keyed by bpmn type so a plain Task, Script Task,
// and User Task each read as their own thing at a glance (fixed colors, see the --*-task-fill/
// stroke custom properties in ntrloc-process-editor.js) while sharing the same rounded-rect body.
// DMN Task is checked separately (isDmnTask(element), not a TASK_VARIANTS[type] lookup) since it
// shares bpmn:ServiceTask's type with any other Service Task -- a plain type-keyed lookup can't
// tell them apart.
const TASK_VARIANTS = {
  [SCRIPT_TASK]: { fillVar: '--script-task-fill', strokeVar: '--script-task-stroke' },
  [USER_TASK]: { fillVar: '--user-task-fill', strokeVar: '--user-task-stroke' },
  [CALL_ACTIVITY]: { fillVar: '--call-activity-fill', strokeVar: '--call-activity-stroke' },
  // Collapsed Sub-Process renders through this same box+label path (see isTask-adjacent check in
  // drawShape below) and gets its own accent too, distinct from a plain Task/Call Activity.
  [SUB_PROCESS]: { fillVar: '--subprocess-fill', strokeVar: '--subprocess-stroke' },
};
const DMN_TASK_VARIANT = { fillVar: '--dmn-task-fill', strokeVar: '--dmn-task-stroke' };
const DEFAULT_TASK_VARIANT = { fillVar: '--task-fill', strokeVar: '--task-stroke' };

const RENDER_PRIORITY = 2000;

// Draws the reduced BPMN element set as plain SVG shapes -- no bpmn-font icon glyphs (that font
// belongs to bpmn-js's licensed distribution, not something we can reuse), just shape + label.
// arrowheadMarkerId is DI-injected (ntrloc-process-editor.js's initCanvas, 'value' binding) rather
// than a hardcoded string -- see arrowhead-marker.js's own header comment for why a fixed id here
// breaks the moment two process tabs are open at once.
export default function BpmnRenderer(eventBus, arrowheadMarkerId) {
  BaseRenderer.call(this, eventBus, RENDER_PRIORITY);
  this._arrowheadMarkerId = arrowheadMarkerId;
}

inherits(BpmnRenderer, BaseRenderer);

BpmnRenderer.$inject = ['eventBus', 'arrowheadMarkerId'];

BpmnRenderer.prototype.canRender = function(element) {
  return element.type === START_EVENT || element.type === END_EVENT
      || element.type === EXCLUSIVE_GATEWAY || element.type === PARALLEL_GATEWAY
      || element.type === SEQUENCE_FLOW
      || element.type === SUB_PROCESS
      || isTask(element) || isCallActivity(element);
};

BpmnRenderer.prototype.drawShape = function(parentGfx, element) {
  const type = element.type;

  if (type === START_EVENT || type === END_EVENT) {
    // Solid, saturated fill per type (green start, red end -- see --start-fill/--end-fill in
    // ntrloc-process-editor.js) rather than a plain outline: color alone now carries the
    // start/end distinction, matching the convention shown at open-bpmn.org, with a thin light
    // ring to lift the shape off the dark canvas rather than differing stroke weights.
    const circle = svgCreate('circle', {
      cx: element.width / 2,
      cy: element.height / 2,
      r: element.width / 2 - 1,
      fill: type === END_EVENT ? 'var(--end-fill)' : 'var(--start-fill)',
      stroke: type === END_EVENT ? 'var(--end-stroke)' : 'var(--start-stroke)',
      strokeWidth: 2,
    });
    svgAppend(parentGfx, circle);

    // Timer Start Event: a small clock face inside the same green circle -- BPMN's own convention
    // for "this is a timer-typed event" is the icon, not a different fill/stroke color, matching
    // bpmn-icons.js's palette entry for the exact same shape at icon scale.
    if (type === START_EVENT && isTimerStartEvent(element)) {
      const cx = element.width / 2, cy = element.height / 2, r = element.width * 0.24;
      const face = svgCreate('circle', {
        cx, cy, r, fill: 'none', stroke: 'white', strokeWidth: 1.4,
      });
      const hourHand = svgCreate('line', {
        x1: cx, y1: cy, x2: cx, y2: cy - r * 0.65, stroke: 'white', strokeWidth: 1.4, strokeLinecap: 'round',
      });
      const minuteHand = svgCreate('line', {
        x1: cx, y1: cy, x2: cx + r * 0.55, y2: cy + r * 0.3, stroke: 'white', strokeWidth: 1.4, strokeLinecap: 'round',
      });
      svgAppend(parentGfx, face);
      svgAppend(parentGfx, hourHand);
      svgAppend(parentGfx, minuteHand);
    }
  } else if (isTask(element) || isCallActivity(element) || (type === SUB_PROCESS && !isExpandedSubProcess(element))) {
    // Call Activity and a collapsed Sub-Process are visually opaque, task-shaped boxes -- same
    // box+label path as a real Task, just with their own accent color and corner glyph below.
    // --task-fill/--task-stroke (ntrloc-process-editor.js) are a fixed, deliberately saturated
    // blue -- not derived from the app's own --bg -- so Task reads as its own type at a glance,
    // consistent with Start/End Event and Gateway's own fixed accent colors. Script Task/User Task
    // get their own accent pair each (see TASK_VARIANTS above) so every task type stays
    // distinguishable on canvas, not just in the palette.
    const variant = isDmnTask(element) ? DMN_TASK_VARIANT : (TASK_VARIANTS[type] || DEFAULT_TASK_VARIANT);
    const rect = svgCreate('rect', {
      x: 0,
      y: 0,
      width: element.width,
      height: element.height,
      rx: 8,
      ry: 8,
      fill: `var(${variant.fillVar})`,
      stroke: `var(${variant.strokeVar})`,
      strokeWidth: 2,
    });
    svgAppend(parentGfx, rect);

    // A plain rounded rect alone doesn't read as "a task" the way a circle/diamond already reads
    // as an event/gateway -- this small corner mark mirrors BpmnPaletteProvider.js's task icons
    // (bpmn-icons.js) so the same shape means the same thing in the palette and on canvas: a
    // checklist for a generic Task, a "</>" glyph for a Script Task, a person glyph for User Task.
    if (type === SCRIPT_TASK) {
      const mark = svgCreate('text', {
        x: 8, y: 20,
        'font-size': '13px',
        'font-family': 'monospace',
        fill: 'white',
      });
      mark.textContent = '</>';
      svgAppend(parentGfx, mark);
    } else if (type === USER_TASK) {
      const head = svgCreate('circle', { cx: 13, cy: 12, r: 3.5, fill: 'white' });
      const shoulders = svgCreate('path', {
        d: 'M6 21c0-3.9 3.1-6.5 7-6.5s7 2.6 7 6.5',
        fill: 'none',
        stroke: 'white',
        strokeWidth: 2,
        strokeLinecap: 'round',
      });
      svgAppend(parentGfx, head);
      svgAppend(parentGfx, shoulders);
    } else if (isDmnTask(element)) {
      // Small table-grid mark, matching bpmn-icons.js's DMN Task palette icon.
      const gx = 6, gy = 6, gw = 14, gh = 14;
      const outline = svgCreate('rect', {
        x: gx, y: gy, width: gw, height: gh, fill: 'none', stroke: 'white', strokeWidth: 1.3,
      });
      const vLine = svgCreate('line', {
        x1: gx + gw / 2, y1: gy, x2: gx + gw / 2, y2: gy + gh, stroke: 'white', strokeWidth: 1.3,
      });
      const hLine = svgCreate('line', {
        x1: gx, y1: gy + gh / 2, x2: gx + gw, y2: gy + gh / 2, stroke: 'white', strokeWidth: 1.3,
      });
      svgAppend(parentGfx, outline);
      svgAppend(parentGfx, vLine);
      svgAppend(parentGfx, hLine);
    } else if (isCallActivity(element)) {
      // Arrow feeding into a small box -- reads as "hands off to another process," at the same
      // corner scale/stroke weight as the other task-glyph marks above.
      const gx = 6, gy = 6, gh = 14;
      const box = svgCreate('rect', {
        x: gx + 4, y: gy, width: 10, height: gh, fill: 'none', stroke: 'white', strokeWidth: 1.3,
      });
      const arrow = svgCreate('path', {
        d: `M ${gx - 2} ${gy + gh / 2} L ${gx + 6} ${gy + gh / 2} `
          + `M ${gx + 3} ${gy + gh / 2 - 3} L ${gx + 6} ${gy + gh / 2} L ${gx + 3} ${gy + gh / 2 + 3}`,
        fill: 'none', stroke: 'white', strokeWidth: 1.3, strokeLinecap: 'round', strokeLinejoin: 'round',
      });
      svgAppend(parentGfx, box);
      svgAppend(parentGfx, arrow);
    } else if (type === SUB_PROCESS) {
      // Collapsed Sub-Process: the standard BPMN "+" marker box at the bottom-center of the
      // shape, not a corner glyph -- signals "there's more inside" without implying task-type
      // categories the corner marks above are for. Also the click target that expands it again
      // (SubProcessToggleBehavior.js) -- getSubProcessToggleMarkerBounds is the single shared
      // source for this geometry so the drawn box and its hit-test never drift apart.
      const { x: mx, y: my, width: mw, height: mh } = getSubProcessToggleMarkerBounds(element);
      const box = svgCreate('rect', {
        x: mx, y: my, width: mw, height: mh, rx: 2, fill: 'none', stroke: 'white', strokeWidth: 1.3,
      });
      const hLine = svgCreate('line', {
        x1: mx + 3, y1: my + mh / 2, x2: mx + mw - 3, y2: my + mh / 2, stroke: 'white', strokeWidth: 1.3,
      });
      const vLine = svgCreate('line', {
        x1: mx + mw / 2, y1: my + 3, x2: mx + mw / 2, y2: my + mh - 3, stroke: 'white', strokeWidth: 1.3,
      });
      svgAppend(parentGfx, box);
      svgAppend(parentGfx, hLine);
      svgAppend(parentGfx, vLine);
      drawSubProcessLoopMarker(parentGfx, element, 'white');
    } else {
      [0, 4, 8].forEach((dy) => {
        const line = svgCreate('line', {
          x1: 8, y1: 8 + dy, x2: dy === 8 ? 16 : 20, y2: 8 + dy,
          stroke: 'white',
          strokeWidth: 1.5,
        });
        svgAppend(parentGfx, line);
      });
    }
  } else if (type === EXCLUSIVE_GATEWAY || type === PARALLEL_GATEWAY) {
    const w = element.width, h = element.height;
    const diamond = svgCreate('polygon', {
      points: `${w / 2},0 ${w},${h / 2} ${w / 2},${h} 0,${h / 2}`,
      fill: 'var(--gateway-fill)',
      stroke: 'var(--gateway-stroke)',
      strokeWidth: 2,
    });
    svgAppend(parentGfx, diamond);
    if (type === EXCLUSIVE_GATEWAY) {
      const mark = svgCreate('text', {
        x: w / 2,
        y: h / 2 + 6,
        'text-anchor': 'middle',
        'font-size': '20px',
        fill: 'white',
      });
      mark.textContent = 'X';
      svgAppend(parentGfx, mark);
    } else {
      // Parallel Gateway: a "+" built from two strokes rather than a text glyph -- crisper at
      // this scale than relying on font rendering for a two-character symbol, same stroke
      // language as Exclusive's "X".
      const cx = w / 2, cy = h / 2, len = 11;
      const vLine = svgCreate('line', {
        x1: cx, y1: cy - len / 2, x2: cx, y2: cy + len / 2, stroke: 'white', strokeWidth: 3, strokeLinecap: 'round',
      });
      const hLine = svgCreate('line', {
        x1: cx - len / 2, y1: cy, x2: cx + len / 2, y2: cy, stroke: 'white', strokeWidth: 3, strokeLinecap: 'round',
      });
      svgAppend(parentGfx, vLine);
      svgAppend(parentGfx, hLine);
    }
  } else if (type === SUB_PROCESS && isExpandedSubProcess(element)) {
    // Expanded Sub-Process: a large, mostly-hollow container so nested children read clearly
    // against it -- low fill-opacity rather than the flat solid fill every other shape here uses.
    const rect = svgCreate('rect', {
      x: 0, y: 0, width: element.width, height: element.height, rx: 8, ry: 8,
      fill: 'var(--subprocess-fill)',
      'fill-opacity': 0.12,
      stroke: 'var(--subprocess-stroke)',
      strokeWidth: 2,
    });
    svgAppend(parentGfx, rect);

    // Bottom-center "-" marker, mirroring collapsed's "+" -- not required by the BPMN spec (an
    // expanded container is already self-evident from being drawn as one), but a common tool
    // convention that pairs with it: since this is a real click target that collapses it again
    // (SubProcessToggleBehavior.js), the matching mark signals "click to collapse" the same way
    // "+" signals "click to expand."
    const { x: mx, y: my, width: mw, height: mh } = getSubProcessToggleMarkerBounds(element);
    const box = svgCreate('rect', {
      x: mx, y: my, width: mw, height: mh, rx: 2, fill: 'none', stroke: 'var(--subprocess-stroke)', strokeWidth: 1.3,
    });
    const hLine = svgCreate('line', {
      x1: mx + 3, y1: my + mh / 2, x2: mx + mw - 3, y2: my + mh / 2, stroke: 'var(--subprocess-stroke)', strokeWidth: 1.3,
    });
    svgAppend(parentGfx, box);
    svgAppend(parentGfx, hLine);
    drawSubProcessLoopMarker(parentGfx, element, 'var(--subprocess-stroke)');
  }

  // Every shape now has its own solid, fairly dark fill (or none, for the label sitting below
  // Start/End Event), so a single light label color reads fine everywhere.
  const name = element.businessObject && element.businessObject.name;
  if (name) {
    const fontSize = 12;
    const isBoxLabel = isTask(element) || isCallActivity(element)
      || (type === SUB_PROCESS && !isExpandedSubProcess(element));
    if (isBoxLabel) {
      // A Task-like shape's label sits inside a fixed-size, non-resizable box (see
      // BpmnRuleProvider.js's shape.resize rule) -- a single unwrapped <text> just overflows both
      // edges once the name is longer than a couple of words. Wrapping to fit the box, the way
      // bpmn.io/Camunda Modeler do, is what keeps a name like "Log Greeting Length" fully
      // readable and inside the shape instead of spilling into whatever's drawn next to it.
      const lines = wrapLabel(parentGfx, name, element.width - 16, fontSize);
      const lineHeight = fontSize + 2;
      const startY = element.height / 2 - ((lines.length - 1) * lineHeight) / 2 + 4;
      lines.forEach((line, i) => {
        const label = svgCreate('text', {
          x: element.width / 2,
          y: startY + i * lineHeight,
          'text-anchor': 'middle',
          'font-size': fontSize + 'px',
          fill: 'white',
        });
        label.textContent = line;
        svgAppend(parentGfx, label);
      });
    } else if (type === SUB_PROCESS && isExpandedSubProcess(element)) {
      // Pinned to the top-left corner, single line, not wrapped or centered -- centering would
      // collide with contained children, and this matches standard BPMN expanded-subprocess
      // label placement.
      const label = svgCreate('text', {
        x: 10,
        y: 18,
        'text-anchor': 'start',
        'font-size': fontSize + 'px',
        fill: 'var(--text)',
      });
      label.textContent = name;
      svgAppend(parentGfx, label);
    } else {
      const label = svgCreate('text', {
        x: element.width / 2,
        y: element.height + 14,
        'text-anchor': 'middle',
        'font-size': fontSize + 'px',
        fill: 'var(--text)',
      });
      label.textContent = name;
      svgAppend(parentGfx, label);
    }
  }

  return parentGfx;
};

// Greedily breaks `text` into lines no wider than `maxWidth` at the given font size, measuring
// against the actual rendered font (via a scratch <text> node's getComputedTextLength()) rather
// than an approximate average character width -- font metrics vary enough per-character that an
// estimate would either wrap too early or let lines quietly overflow again.
function wrapLabel(parentGfx, text, maxWidth, fontSize) {
  const measurer = svgCreate('text', { 'font-size': fontSize + 'px' });
  svgAppend(parentGfx, measurer);

  const words = text.split(' ');
  const lines = [];
  let current = '';
  for (const word of words) {
    const candidate = current ? `${current} ${word}` : word;
    measurer.textContent = candidate;
    if (current && measurer.getComputedTextLength() > maxWidth) {
      lines.push(current);
      current = word;
    } else {
      current = candidate;
    }
  }
  if (current) lines.push(current);

  parentGfx.removeChild(measurer);
  return lines;
}

BpmnRenderer.prototype.drawConnection = function(parentGfx, connection) {
  // Runs mostly over open canvas (the dark app background), not over a shape's white fill, so
  // this needs to be a light color too -- unlike shape strokes/labels, which sit on white.
  // markerEnd references the <marker> ntrloc-process-editor.js adds to the canvas's <svg> once,
  // before anything is drawn (SVG markers must already exist in the document to be referenced) --
  // this._arrowheadMarkerId, not a hardcoded id (see arrowhead-marker.js).
  const line = createLine(connection.waypoints, {
    stroke: 'var(--muted)',
    strokeWidth: 2,
    markerEnd: `url(#${this._arrowheadMarkerId})`,
  });
  svgAppend(parentGfx, line);
  return line;
};

// Must match each type's actual drawn shape, not just its bounding box -- this is what
// CroppingConnectionDocking (BpmnLayouter.js) intersects a connection's line against to find
// where it should actually dock. A bounding-box rectangle for a Start/End Event's *circle* means
// connections crop against the square's corner instead of the visually smaller circle, landing
// noticeably outside the drawn outline whenever a connection approaches at a diagonal.
BpmnRenderer.prototype.getShapePath = function(shape) {
  const { x, y, width, height } = shape;
  const type = shape.type;

  if (type === START_EVENT || type === END_EVENT) {
    const cx = x + width / 2, cy = y + height / 2, r = width / 2 - 1;
    return componentsToPath([
      ['M', cx - r, cy],
      ['A', r, r, 0, 1, 0, cx + r, cy],
      ['A', r, r, 0, 1, 0, cx - r, cy],
      ['Z'],
    ]);
  }

  if (type === EXCLUSIVE_GATEWAY || type === PARALLEL_GATEWAY) {
    const cx = x + width / 2, cy = y + height / 2;
    return componentsToPath([
      ['M', cx, y],
      ['L', x + width, cy],
      ['L', cx, y + height],
      ['L', x, cy],
      ['Z'],
    ]);
  }

  return componentsToPath([
    ['M', x, y],
    ['l', width, 0],
    ['l', 0, height],
    ['l', -width, 0],
    ['z'],
  ]);
};

BpmnRenderer.prototype.getConnectionPath = function(connection) {
  const waypoints = connection.waypoints || [];
  return componentsToPath(waypoints.map((point, idx) => [idx === 0 ? 'M' : 'L', point.x, point.y]));
};
