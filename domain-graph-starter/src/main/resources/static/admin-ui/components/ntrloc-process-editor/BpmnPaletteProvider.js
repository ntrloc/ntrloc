import {
  START_EVENT, END_EVENT, TASK, SCRIPT_TASK, USER_TASK, EXCLUSIVE_GATEWAY, PARALLEL_GATEWAY,
  CALL_ACTIVITY, SUB_PROCESS, EXPANDED_SUB_PROCESS_SIZE, createBpmnShape,
} from './bpmn-elements.js';
import { paletteIconHtml, DMN_TASK_ICON_KEY, SUB_PROCESS_EXPANDED_ICON_KEY, TIMER_START_EVENT_ICON_KEY } from './bpmn-icons.js';

// Palette entries for the reduced element set. Each click starts a drag-to-place via the
// `create` feature (create.start), matching diagram-js's own reference editor pattern.
export default function BpmnPaletteProvider(create, elementFactory, palette, moddle) {
  this._create = create;
  this._elementFactory = elementFactory;
  this._moddle = moddle;

  palette.registerProvider(this);
}

BpmnPaletteProvider.$inject = ['create', 'elementFactory', 'palette', 'moddle'];

BpmnPaletteProvider.prototype.getPaletteEntries = function() {
  const create = this._create;
  const elementFactory = this._elementFactory;
  const moddle = this._moddle;

  // The title becomes both the native `title` attribute (a11y/keyboard fallback) and the
  // flyout's heading; description is the flyout's body copy, shown to the right of the icon on
  // hover via the .ntrloc-palette-flyout CSS in ntrloc-process-editor.js. `variant.iconKey`
  // overrides the icon lookup key (needed for DMN Task, which shares bpmn:ServiceTask's type but
  // not its icon) and `variant.shapeOptions` is threaded through to createBpmnShape (name/
  // flowableType overrides) -- both default to plain per-bpmnType behavior when omitted.
  const startTool = (bpmnType, title, description, variant = {}) => ({
    group: 'bpmn',
    // Deliberately no top-level `title` here -- Palette.js sets that as a native browser
    // `title` attribute, and its tooltip popup was rendering on top of (and hiding) the entry's
    // own .ntrloc-palette-flyout description below, which already shows this same title plus the
    // description on hover. `title` stays a local var, still used inside the flyout markup.
    html: `
      <div class="entry ntrloc-palette-entry" draggable="true">
        <span class="ntrloc-palette-icon">${paletteIconHtml(variant.iconKey || bpmnType)}</span>
        <div class="ntrloc-palette-flyout">
          <strong>${title}</strong>
          <p>${description}</p>
        </div>
      </div>`,
    action: {
      click: (event) => {
        const shape = createBpmnShape(elementFactory, moddle, bpmnType, undefined, variant.shapeOptions);
        create.start(event, shape);
      },
    },
  });

  return {
    'create.start-event': startTool(
      START_EVENT, 'Start Event', 'Marks where a process instance begins. Every process needs exactly one.'),
    'create.timer-start-event': startTool(
      START_EVENT, 'Timer Start Event',
      'Marks where a process instance begins automatically, on a schedule -- a delay, a fixed date, or a repeating cycle -- rather than by an outside trigger.',
      { iconKey: TIMER_START_EVENT_ICON_KEY, shapeOptions: { name: 'Timer Start Event', eventDefinitionType: 'bpmn:TimerEventDefinition' } }),
    'create.end-event': startTool(
      END_EVENT, 'End Event', 'Marks where a path through the process ends.'),
    'create.task': startTool(
      TASK, 'Task', 'A single unit of work carried out as part of the process.'),
    'create.script-task': startTool(
      SCRIPT_TASK, 'Script Task',
      'Runs an inline script (Groovy by default) as part of the process, no external delegate needed.'),
    'create.user-task': startTool(
      USER_TASK, 'User Task',
      'Pauses the process for a person to act -- assign it to someone directly or to a group of candidates.'),
    'create.dmn-task': startTool(
      'bpmn:ServiceTask', 'DMN Task',
      'Evaluates a decision table against the process\'s current variables and stores the result.',
      { iconKey: DMN_TASK_ICON_KEY, shapeOptions: { flowableType: 'dmn', name: 'DMN Task' } }),
    'create.gateway': startTool(
      EXCLUSIVE_GATEWAY, 'Exclusive Gateway',
      'Splits or merges the flow based on a condition -- exactly one outgoing path is taken.'),
    'create.parallel-gateway': startTool(
      PARALLEL_GATEWAY, 'Parallel Gateway',
      'Splits the flow into multiple simultaneous paths, or waits for every incoming path to arrive before continuing -- no condition, every path is taken.'),
    'create.call-activity': startTool(
      CALL_ACTIVITY, 'Call Activity',
      'Starts another deployed process and waits for it to finish before continuing.'),
    // Always expanded on drop, with its own Start/End Event already inside (see
    // SubProcessAutoSeedBehavior.js) -- collapsed vs. expanded isn't a choice made at creation
    // time, it's purely a later view-toggle (the context pad's collapse/expand entry), matching
    // how BPMN itself treats it: one element, isExpanded is diagram info, not a semantic type.
    'create.subprocess': startTool(
      SUB_PROCESS, 'Sub-Process',
      'A self-contained group of steps, shown as a container you can drop other elements into and connect. Collapse it later to tuck the details away.',
      { iconKey: SUB_PROCESS_EXPANDED_ICON_KEY, shapeOptions: { size: EXPANDED_SUB_PROCESS_SIZE } }),
  };
};
