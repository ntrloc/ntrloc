import {
  isSubProcess, SUB_PROCESS, DEFAULT_SIZE, EXPANDED_SUB_PROCESS_SIZE, getSubProcessToggleMarkerBounds,
} from './bpmn-elements.js';

// Clicking the +/- marker drawn on a Sub-Process (BpmnRenderer.js) toggles it collapsed/expanded
// -- the standard BPMN-tool convention (Camunda Modeler, bpmn.io) for this exact interaction,
// rather than a context-pad entry or a double-click with no established meaning here.
//
// diagram-js's InteractionEvents only hands 'element.click' listeners { element, gfx,
// originalEvent } (verified directly against InteractionEvents.js's fire() -- no pre-converted
// diagram-space coordinates), so the click position is derived from the shape's own rendered
// bounding rect instead: (clientX/Y - rect.left/top) / rect.width/height gives a 0..1 fraction of
// the shape that, multiplied by element.width/height, lands in the exact same local coordinate
// space BpmnRenderer.js draws the marker in -- this naturally accounts for the canvas's current
// zoom and pan without needing to reach into Canvas's own viewbox state at all.
export default function SubProcessToggleBehavior(eventBus, modeling) {
  eventBus.on('element.click', (event) => {
    const { element, gfx, originalEvent } = event;
    if (!isSubProcess(element)) return;

    const rect = gfx.getBoundingClientRect();
    if (!rect.width || !rect.height) return;
    const localX = (originalEvent.clientX - rect.left) / rect.width * element.width;
    const localY = (originalEvent.clientY - rect.top) / rect.height * element.height;

    const marker = getSubProcessToggleMarkerBounds(element);
    const hit = localX >= marker.x && localX <= marker.x + marker.width
      && localY >= marker.y && localY <= marker.y + marker.height;
    if (!hit) return;

    // modeling.toggleCollapse is diagram-js's own built-in command (ModelingModule) -- undo-safe,
    // recursively hides/shows children to match, but doesn't touch geometry itself (see its own
    // ToggleShapeCollapseHandler.js), so the size swap happens here around it: shrink to a task-
    // sized box on collapse, restore on expand. The size to restore to is remembered on the shape
    // itself (element.expandedBounds) rather than always snapping back to the same default, so
    // re-expanding doesn't look jarring if it had been resized before collapsing.
    if (element.collapsed) {
      const bounds = element.expandedBounds || EXPANDED_SUB_PROCESS_SIZE;
      modeling.toggleCollapse(element);
      modeling.resizeShape(element, { x: element.x, y: element.y, width: bounds.width, height: bounds.height });
    } else {
      element.expandedBounds = { width: element.width, height: element.height };
      modeling.toggleCollapse(element);
      const collapsedSize = DEFAULT_SIZE[SUB_PROCESS];
      modeling.resizeShape(element, { x: element.x, y: element.y, width: collapsedSize.width, height: collapsedSize.height });
    }
  });
}

SubProcessToggleBehavior.$inject = ['eventBus', 'modeling'];
