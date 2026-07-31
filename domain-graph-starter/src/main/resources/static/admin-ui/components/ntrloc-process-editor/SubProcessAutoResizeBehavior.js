import { SUB_PROCESS } from './bpmn-elements.js';

// This vendored diagram-js subset has no interactive Resize feature (no drag handles -- see
// `find .../vendor/diagram-js/diagram-js/lib/features`, there's no `resize/` directory), so an
// expanded Sub-Process can never be manually resized. Growing it automatically whenever a child
// would extend past its right/bottom edge covers the same practical need -- a container big
// enough for what's dropped inside it -- without needing drag-handle UI. Only grows right/bottom,
// never left/top: those would require shifting the shape's own x/y, which visually displaces
// every other child relative to the parent's origin for no benefit here (new children/drags
// naturally land in that direction from the existing content, not off the top-left edge).
//
// Listens on the CommandStack's own postExecuted events (`commandStack.<command>.postExecuted`,
// event.context mirrors exactly what Modeling.js passed into commandStack.execute -- verified
// directly against CommandStack.js's _fire/_internalExecute, not assumed) rather than a
// higher-level "shape added" event, since that's the actual, always-correct signal for "this
// specific modeling command just placed/moved a shape."
const PADDING = 30;
const TOP_LABEL_ALLOWANCE = 50;

export default function SubProcessAutoResizeBehavior(eventBus, modeling) {
  function growToFit(shape) {
    const parent = shape && shape.parent;
    if (!parent || parent.type !== SUB_PROCESS || shape === parent) return;

    const neededWidth = shape.x + shape.width + PADDING - parent.x;
    const neededHeight = shape.y + shape.height + PADDING - parent.y;

    if (neededWidth <= parent.width && neededHeight <= parent.height) return;

    modeling.resizeShape(parent, {
      x: parent.x,
      y: parent.y,
      width: Math.max(parent.width, neededWidth),
      height: Math.max(parent.height, neededHeight, TOP_LABEL_ALLOWANCE + PADDING),
    });
  }

  eventBus.on('commandStack.shape.create.postExecuted', (event) => {
    growToFit(event.context.shape);
  });

  eventBus.on('commandStack.elements.move.postExecuted', (event) => {
    (event.context.shapes || []).forEach(growToFit);
  });
}

SubProcessAutoResizeBehavior.$inject = ['eventBus', 'modeling'];
