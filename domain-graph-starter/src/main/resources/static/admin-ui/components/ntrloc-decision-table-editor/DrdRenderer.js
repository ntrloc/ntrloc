import inherits from '../../vendor/diagram-js/inherits-browser/dist/index.es.js';
import BaseRenderer from '../../vendor/diagram-js/diagram-js/lib/draw/BaseRenderer.js';
import { componentsToPath, createLine } from '../../vendor/diagram-js/diagram-js/lib/util/RenderUtil.js';
import { append as svgAppend, create as svgCreate } from '../../vendor/diagram-js/tiny-svg/dist/index.js';
import { isDecisionNode, REQUIREMENT } from './DrdElements.js';

const RENDER_PRIORITY = 2000;

// Draws DRD decision nodes and requirement arrows as plain SVG -- mirrors BpmnRenderer.js's
// approach (no licensed icon font, shape + label only), reduced to the one shape type a DRD
// needs here. --dmn-task-fill/--dmn-task-stroke are defined in ntrloc-process-editor.js's
// injected :root block, not here -- a deliberate cross-file reuse (both files are always loaded
// regardless of which tab is active, see index.html) so a DRD node and the BPMN editor's "DMN
// Task" element read as the same underlying thing: a decision table.
// arrowheadMarkerId is DI-injected (ntrloc-decision-table-editor.js's initCanvas, 'value'
// binding) rather than a hardcoded string -- see arrowhead-marker.js's own header comment for why
// a fixed id here breaks the moment two DRD tabs are open at once.
export default function DrdRenderer(eventBus, arrowheadMarkerId) {
  BaseRenderer.call(this, eventBus, RENDER_PRIORITY);
  this._arrowheadMarkerId = arrowheadMarkerId;
}

inherits(DrdRenderer, BaseRenderer);

DrdRenderer.$inject = ['eventBus', 'arrowheadMarkerId'];

DrdRenderer.prototype.canRender = function(element) {
  return isDecisionNode(element) || element.type === REQUIREMENT;
};

DrdRenderer.prototype.drawShape = function(parentGfx, element) {
  const rect = svgCreate('rect', {
    x: 0, y: 0, width: element.width, height: element.height, rx: 8, ry: 8,
    fill: 'var(--dmn-task-fill)', stroke: 'var(--dmn-task-stroke)', strokeWidth: 2,
  });
  svgAppend(parentGfx, rect);

  // Small table-grid mark, matching bpmn-icons.js's DMN Task palette icon and BpmnRenderer.js's
  // corner glyph for a DMN Task on the BPMN canvas.
  const gx = 8, gy = 8, gw = 16, gh = 16;
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

  const name = element.businessObject && element.businessObject.name;
  if (name) {
    const fontSize = 12;
    const lines = wrapLabel(parentGfx, name, element.width - 16, fontSize);
    const lineHeight = fontSize + 2;
    const startY = element.height / 2 - ((lines.length - 1) * lineHeight) / 2 + 6;
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
  }

  return parentGfx;
};

// Same greedy word-wrap as BpmnRenderer.js's own wrapLabel -- duplicated rather than shared since
// it's a small, self-contained 15 lines and the two renderers otherwise have no common base class
// to hang a shared helper off of.
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

DrdRenderer.prototype.drawConnection = function(parentGfx, connection) {
  const line = createLine(connection.waypoints, {
    stroke: 'var(--muted)',
    strokeWidth: 2,
    markerEnd: `url(#${this._arrowheadMarkerId})`,
  });
  svgAppend(parentGfx, line);
  return line;
};

// Plain rectangle path -- matches the drawn shape, used by CroppingConnectionDocking (via
// ConnectionLayouter/ConnectionMovePreview) to crop requirement arrows to the box edge rather
// than the center.
DrdRenderer.prototype.getShapePath = function(shape) {
  const { x, y, width, height } = shape;
  return componentsToPath([
    ['M', x, y],
    ['l', width, 0],
    ['l', 0, height],
    ['l', -width, 0],
    ['z'],
  ]);
};

DrdRenderer.prototype.getConnectionPath = function(connection) {
  const waypoints = connection.waypoints || [];
  return componentsToPath(waypoints.map((point, idx) => [idx === 0 ? 'M' : 'L', point.x, point.y]));
};
