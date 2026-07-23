import { SUB_PROCESS, START_EVENT, END_EVENT, SEQUENCE_FLOW, nextId } from './bpmn-elements.js';

// A Sub-Process is always expanded when dropped from the palette (see BpmnPaletteProvider.js),
// but an empty one isn't actually valid, runnable BPMN -- Flowable's SubProcessActivityBehavior
// throws "No initial activity found for subprocess" if it has no internal Start Event to begin
// execution from (verified against flowable-engine's own source, not assumed). Rather than make
// the user immediately drag a Start and End Event in themselves before the container is even
// usable, this auto-seeds both (connected) the moment a fresh Sub-Process is created, so it's
// valid and runnable from the instant it's placed.
//
// Listens on the CommandStack's own postExecuted event, same mechanism and same reasoning as
// SubProcessAutoResizeBehavior.js -- only fires for live modeling actions (never during
// bpmn-io.js's importXml, which populates the canvas directly via canvas.addShape, bypassing the
// command stack entirely), so an imported Sub-Process with its own real authored content is never
// touched by this.
const INNER_EVENT_SIZE = 36;
const PADDING = 30;

export default function SubProcessAutoSeedBehavior(eventBus, elementFactory, moddle, modeling) {
  eventBus.on('commandStack.shape.create.postExecuted', (event) => {
    const shape = event.context.shape;
    if (shape.type !== SUB_PROCESS) return;
    // A Sub-Process created with the collapsed toggle already tripped (not possible via today's
    // single always-expanded palette entry, but a future collapsed-created path shouldn't seed
    // invisible content the user never asked for) or one that already has children (e.g. a
    // multi-element paste/duplicate, not something this editor does today, but a reasonable
    // defensive check) is left alone.
    if (shape.collapsed || (shape.children && shape.children.length > 0)) return;

    const startBo = moddle.create(START_EVENT, { id: nextId('StartEvent') });
    const startShape = elementFactory.createShape({
      type: START_EVENT,
      id: startBo.id,
      businessObject: startBo,
      x: shape.x + PADDING,
      y: shape.y + shape.height / 2 - INNER_EVENT_SIZE / 2,
      width: INNER_EVENT_SIZE,
      height: INNER_EVENT_SIZE,
    });
    modeling.createShape(startShape,
        { x: startShape.x, y: startShape.y, width: startShape.width, height: startShape.height }, shape);

    const endBo = moddle.create(END_EVENT, { id: nextId('EndEvent') });
    const endShape = elementFactory.createShape({
      type: END_EVENT,
      id: endBo.id,
      businessObject: endBo,
      x: shape.x + shape.width - PADDING - INNER_EVENT_SIZE,
      y: shape.y + shape.height / 2 - INNER_EVENT_SIZE / 2,
      width: INNER_EVENT_SIZE,
      height: INNER_EVENT_SIZE,
    });
    modeling.createShape(endShape,
        { x: endShape.x, y: endShape.y, width: endShape.width, height: endShape.height }, shape);

    modeling.connect(startShape, endShape, {
      type: SEQUENCE_FLOW,
      businessObject: moddle.create(SEQUENCE_FLOW, {
        id: nextId('SequenceFlow'),
        sourceRef: startBo,
        targetRef: endBo,
      }),
    });
  });
}

SubProcessAutoSeedBehavior.$inject = ['eventBus', 'elementFactory', 'moddle', 'modeling'];
