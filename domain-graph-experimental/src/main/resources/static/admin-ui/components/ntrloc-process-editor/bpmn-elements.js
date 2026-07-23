// Shared constants for the reduced BPMN element set this editor supports (see
// docs/ntrloc-workflow-summary.md): Start Event, End Event, Task, Exclusive Gateway,
// Sequence Flow. Anything else in an imported diagram (pools, boundary events, subprocesses,
// ...) is intentionally out of scope for now.

export const START_EVENT = 'bpmn:StartEvent';
export const END_EVENT = 'bpmn:EndEvent';
export const TASK = 'bpmn:Task';
export const SCRIPT_TASK = 'bpmn:ScriptTask';
export const USER_TASK = 'bpmn:UserTask';
export const EXCLUSIVE_GATEWAY = 'bpmn:ExclusiveGateway';
export const PARALLEL_GATEWAY = 'bpmn:ParallelGateway';
export const CALL_ACTIVITY = 'bpmn:CallActivity';
export const SUB_PROCESS = 'bpmn:SubProcess';
export const SEQUENCE_FLOW = 'bpmn:SequenceFlow';

// The palette only ever creates a plain bpmn:Task, but real pre-existing processes may already
// use a more specific task subtype (our own hello-world process's "sayHello" step is a
// bpmn:ServiceTask) -- all render/behave identically here, as a generic task box, rather than
// silently failing to import/display an element outside the exact type this editor authors.
export const TASK_SUBTYPES = [
  'bpmn:Task', 'bpmn:ServiceTask', 'bpmn:UserTask', 'bpmn:ScriptTask',
  'bpmn:BusinessRuleTask', 'bpmn:ManualTask', 'bpmn:SendTask', 'bpmn:ReceiveTask',
];

export const SHAPE_TYPES = [
  START_EVENT, END_EVENT, ...TASK_SUBTYPES, EXCLUSIVE_GATEWAY, PARALLEL_GATEWAY,
  CALL_ACTIVITY, SUB_PROCESS,
];

// Sub-Process's default footprint depends on whether it's collapsed (task-sized, opaque) or
// expanded (a real container, needs real room for children) -- that distinction lives on the
// shape (element.collapsed, diagram-js's own built-in property, see isExpandedSubProcess below),
// not the type, so DEFAULT_SIZE only ever supplies the collapsed size here; the palette's
// options.size overrides it for a freshly-created (always-expanded) Sub-Process, and the
// context-pad's collapse/expand toggle uses this same constant as the fallback expanded size for
// one that's never been explicitly resized.
export const EXPANDED_SUB_PROCESS_SIZE = { width: 360, height: 220 };

export const DEFAULT_SIZE = {
  [START_EVENT]: { width: 36, height: 36 },
  [END_EVENT]: { width: 36, height: 36 },
  [EXCLUSIVE_GATEWAY]: { width: 50, height: 50 },
  [PARALLEL_GATEWAY]: { width: 50, height: 50 },
  [CALL_ACTIVITY]: { width: 100, height: 80 },
  [SUB_PROCESS]: { width: 100, height: 80 },
};
for (const taskType of TASK_SUBTYPES) {
  DEFAULT_SIZE[taskType] = { width: 100, height: 80 };
}

export function isTask(element) {
  return element && TASK_SUBTYPES.includes(element.type);
}

export function isCallActivity(element) {
  return !!(element && element.type === CALL_ACTIVITY);
}

export function isSubProcess(element) {
  return !!(element && element.type === SUB_PROCESS);
}

// A Sub-Process's collapsed/expanded state is diagram info (BPMNDI's isExpanded attribute), not
// part of the semantic bpmn:SubProcess element itself -- see bpmn-io.js. Tracked via diagram-js's
// own built-in `element.collapsed` property (ModelingModule's toggleCollapse command reads/writes
// it directly, and recursively hides/shows children to match -- see SubProcessToggleBehavior.js),
// rather than a separate custom flag: undefined/falsy means expanded, matching a freshly-created
// Sub-Process's default with no extra setup needed.
export function isExpandedSubProcess(element) {
  return isSubProcess(element) && !element.collapsed;
}

// The small +/- marker box drawn at the top-right corner of a Sub-Process (BpmnRenderer.js, both
// the collapsed "+" and expanded "-" cases use the exact same geometry) -- a window minimize/
// maximize-style corner control -- is also the click target that toggles it
// (SubProcessToggleBehavior.js) -- a single shared source for these coordinates so the drawn
// marker and its hit-test can never drift out of sync with each other. Returned in the shape's
// own local coordinate space (0,0 = its top-left corner), matching how BpmnRenderer.js draws
// everything else on a shape. Deliberately not bottom-center: that spot is reserved for the loop/
// multi-instance marker below, which needs to coexist with this one without visual contention.
const TOGGLE_MARKER_SIZE = 14;
const TOGGLE_MARKER_MARGIN = 6;

export function getSubProcessToggleMarkerBounds(element) {
  return {
    x: element.width - TOGGLE_MARKER_SIZE - TOGGLE_MARKER_MARGIN,
    y: TOGGLE_MARKER_MARGIN,
    width: TOGGLE_MARKER_SIZE,
    height: TOGGLE_MARKER_SIZE,
  };
}

// The loop/multi-instance marker glyph (BpmnRenderer.js) drawn at the bottom-center of a Sub-
// Process, both collapsed and expanded -- the standard BPMN spot for this mark, now uncontested
// since the expand/collapse toggle above moved to the top-right corner. Same shared-geometry
// reasoning as the toggle marker: this is also SubProcessLoopTypeMenuBehavior's anchor point.
const LOOP_MARKER_SIZE = 16;
const LOOP_MARKER_BOTTOM_MARGIN = 6;

export function getSubProcessLoopMarkerBounds(element) {
  return {
    x: element.width / 2 - LOOP_MARKER_SIZE / 2,
    y: element.height - LOOP_MARKER_SIZE - LOOP_MARKER_BOTTOM_MARGIN,
    width: LOOP_MARKER_SIZE,
    height: LOOP_MARKER_SIZE,
  };
}

// A Sub-Process's repetition behavior is carried entirely by its own loopCharacteristics child
// (standard BPMN, not a Flowable extension) -- bpmn:StandardLoopCharacteristics for "loop until
// condition" (testBefore always false here: this app only models the post-test/"repeat until"
// form, not the pre-test/"while" form), bpmn:MultiInstanceLoopCharacteristics with isSequential
// true/false for the two multi-instance variants, absent entirely for a plain one-time
// Sub-Process. One string type code standing in for that three-way (four, counting "none") shape
// keeps the renderer/context-pad-menu/inspector-panel all switching on the same small vocabulary
// instead of each re-deriving it from loopCharacteristics.$type + isSequential separately.
export const LOOP_TYPE_NONE = 'none';
export const LOOP_TYPE_STANDARD = 'loop';
export const LOOP_TYPE_MI_PARALLEL = 'parallel';
export const LOOP_TYPE_MI_SEQUENTIAL = 'sequential';

export function getLoopType(element) {
  const lc = element && element.businessObject && element.businessObject.loopCharacteristics;
  if (!lc) return LOOP_TYPE_NONE;
  if (lc.$type === 'bpmn:MultiInstanceLoopCharacteristics') {
    return lc.isSequential ? LOOP_TYPE_MI_SEQUENTIAL : LOOP_TYPE_MI_PARALLEL;
  }
  if (lc.$type === 'bpmn:StandardLoopCharacteristics') {
    return LOOP_TYPE_STANDARD;
  }
  return LOOP_TYPE_NONE;
}

export const LOOP_TYPE_LABELS = {
  [LOOP_TYPE_NONE]: 'One-time',
  [LOOP_TYPE_STANDARD]: 'Loop until condition',
  [LOOP_TYPE_MI_PARALLEL]: 'Multi-Instance (Parallel)',
  [LOOP_TYPE_MI_SEQUENTIAL]: 'Multi-Instance (Sequential)',
};

// Builds (or clears) loopCharacteristics for the chosen type -- the one place that knows the
// concrete moddle shape behind each LOOP_TYPE_* code, so the context-pad menu that calls this
// doesn't need to. flowable:collection/flowable:elementVariable on the multi-instance variants
// are Flowable-namespace attributes (bpmn-moddle has no Flowable extension schema loaded, same
// situation as flowable:type/flowable:candidateUsers elsewhere in this app), so they're seeded
// as raw $attrs entries here rather than real modeled properties -- the inspector panel reads/
// writes those same two keys directly (ntrloc-process-editor.js's renderSubProcessLoopFields).
export function setLoopType(moddle, businessObject, loopType) {
  if (loopType === LOOP_TYPE_STANDARD) {
    businessObject.loopCharacteristics = moddle.create('bpmn:StandardLoopCharacteristics', {
      testBefore: false,
      loopCondition: moddle.create('bpmn:FormalExpression', { body: '' }),
    });
  } else if (loopType === LOOP_TYPE_MI_PARALLEL || loopType === LOOP_TYPE_MI_SEQUENTIAL) {
    const lc = moddle.create('bpmn:MultiInstanceLoopCharacteristics', {
      isSequential: loopType === LOOP_TYPE_MI_SEQUENTIAL,
    });
    lc.$attrs['flowable:collection'] = '';
    lc.$attrs['flowable:elementVariable'] = '';
    businessObject.loopCharacteristics = lc;
  } else {
    businessObject.loopCharacteristics = undefined;
  }
}

// Anything a plain sequence flow can legally connect to a container's *boundary* would need a
// boundary event (out of scope) -- but a container can still be a valid connect *endpoint* like
// any other flow node when a flow lives entirely inside/outside it, so this stays a simple type
// check, not a containment check (that's BpmnRuleProvider's job).
export function isContainer(element, rootElement) {
  return element === rootElement || isExpandedSubProcess(element);
}

// A "DMN Task" isn't a distinct BPMN element type -- it's a plain bpmn:ServiceTask carrying
// flowable:type="dmn" (the only shape Flowable's DmnActivityBehavior actually recognizes at
// runtime; <businessRuleTask> is unrelated legacy Drools/KIE integration, verified via
// decompiling BusinessRuleTaskActivityBehavior vs DmnActivityBehavior). Since bpmn:ServiceTask is
// already a shared TASK_SUBTYPES entry, this compound check is how the palette/renderer/panel
// tell a DMN Task apart from any other Service Task.
export function isDmnTask(element) {
  const bo = element && element.businessObject;
  return !!(bo && bo.$type === 'bpmn:ServiceTask' && bo.$attrs && bo.$attrs['flowable:type'] === 'dmn');
}

// Reads a DMN Task's decisionTableReferenceKey field extension (<flowable:field
// name="decisionTableReferenceKey"><flowable:string>KEY</flowable:string></flowable:field>,
// nested inside extensionElements) -- read-only, no moddle instance needed, unlike the write side
// (ntrloc-process-editor.js's setDecisionTableReferenceKey, which needs moddle.createAny() to
// build the element if it's missing). Shared by the inspector panel's own Decision Table select
// and BpmnContextPadProvider.js's "Go to Decision Table" context-pad entry -- both need to know
// the current key, only one of them ever needs to change it.
export function getDecisionTableReferenceKey(bo) {
  const values = bo.extensionElements && bo.extensionElements.values;
  if (!values) return '';
  const fieldEl = values.find((v) => v.$type === 'flowable:field' && v.name === 'decisionTableReferenceKey');
  const stringEl = fieldEl && fieldEl.$children && fieldEl.$children[0];
  return (stringEl && stringEl.$body) || '';
}

export function isFlowNode(element) {
  return element && SHAPE_TYPES.includes(element.type);
}

// A Timer Start Event isn't a distinct BPMN element type either -- it's a plain bpmn:StartEvent
// carrying a bpmn:TimerEventDefinition child (the standard BPMN 2.0 way to type a start event;
// message/signal/conditional start events follow the exact same eventDefinitions pattern, just
// out of scope for now). Same compound-check shape as isDmnTask above, for the same reason: a
// plain type check alone can't tell a Timer Start Event apart from a plain none-start event.
export function isTimerStartEvent(element) {
  const bo = element && element.businessObject;
  return !!(bo && bo.$type === START_EVENT
    && (bo.eventDefinitions || []).some((ed) => ed.$type === 'bpmn:TimerEventDefinition'));
}

let idCounter = 0;

export function nextId(prefix) {
  idCounter += 1;
  return `${prefix}_${Date.now().toString(36)}_${idCounter}`;
}

const DEFAULT_NAME = {
  [TASK]: 'Task',
  [SCRIPT_TASK]: 'Script Task',
  [USER_TASK]: 'User Task',
  [CALL_ACTIVITY]: 'Call Activity',
  [SUB_PROCESS]: 'Sub-Process',
};

// Builds a fresh diagram-js Shape (with a matching bpmn-moddle businessObject already attached)
// for a brand-new element created via the palette -- never used for elements coming from an
// XML import, which carry their own real businessObject and BPMNDI-derived position instead.
// `options.name` overrides DEFAULT_NAME (needed for bpmn:ServiceTask, which has no entry there
// since the palette never creates a plain one); `options.flowableType` sets flowable:type (e.g.
// "dmn") as a raw $attrs entry, same mechanism as the panel's existing flowable:assignee etc. --
// moddle.create() itself rejects foreign-namespace properties, so this is poked on afterward.
// `options.size` overrides DEFAULT_SIZE (needed for a freshly-created Sub-Process's larger,
// always-expanded footprint -- there's no "create collapsed" option anymore, see
// BpmnPaletteProvider.js: a Sub-Process is always expanded when dropped, with its own
// auto-seeded Start/End Event already inside, see SubProcessAutoSeedBehavior.js -- collapsing is
// something you do afterward via the context pad, not a choice made at creation time).
export function createBpmnShape(elementFactory, moddle, bpmnType, position, options = {}) {
  const id = nextId(bpmnType.replace('bpmn:', ''));
  // A Script Task with no scriptFormat/script is legal BPMN but not a useful starting point --
  // Groovy is the default we ship (see the domain-graph-experimental pom's groovy-jsr223
  // dependency), so a freshly-dropped Script Task is immediately runnable rather than needing
  // the panel wired up first just to make it valid.
  const scriptDefaults = bpmnType === SCRIPT_TASK ? { scriptFormat: 'groovy', script: '' } : {};
  const businessObject = moddle.create(bpmnType, {
    id, name: options.name || DEFAULT_NAME[bpmnType], ...scriptDefaults,
  });
  if (options.flowableType) {
    businessObject.$attrs['flowable:type'] = options.flowableType;
  }
  // e.g. 'bpmn:TimerEventDefinition' for a Timer Start Event -- a plain declared moddle property
  // (bpmn:Event.eventDefinitions), not a foreign-namespace attribute like flowableType above, so
  // a direct assignment after create() is enough (same mechanism bpmn-io.js's exportXml already
  // relies on for businessObject.flowElements).
  if (options.eventDefinitionType) {
    businessObject.eventDefinitions = [moddle.create(options.eventDefinitionType, {})];
  }
  const size = options.size || DEFAULT_SIZE[bpmnType];

  return elementFactory.createShape({
    type: bpmnType,
    id,
    businessObject,
    ...size,
    ...position,
  });
}
