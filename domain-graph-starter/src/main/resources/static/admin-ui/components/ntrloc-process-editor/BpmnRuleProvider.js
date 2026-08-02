import inherits from '../../vendor/diagram-js/inherits-browser/dist/index.es.js';
import RuleProvider from '../../vendor/diagram-js/diagram-js/lib/features/rules/RuleProvider.js';
import { START_EVENT, END_EVENT, isFlowNode, isContainer, SEQUENCE_FLOW } from './bpmn-elements.js';

// Reduced-scope connection rules: sequence flows only, nothing flows out of an End Event or
// into a Start Event, any flow node may otherwise connect to any other *within the same
// container* (a Sub-Process boundary can't be crossed by a plain sequence flow -- that needs a
// boundary event, out of scope here). Returning an attrs object (rather than a bare true) from
// the connection.create rule is what tells diagram-js's `connect` feature which concrete type/
// businessObject to give the new connection -- see Connect.js's `if (isObject(canExecute))
// attrs = canExecute`.
//
// Containment (shape.create / elements.move): an expanded Sub-Process is the only element type
// that can ever be a valid drop/move target other than the canvas root itself (see
// bpmn-elements.js's isContainer) -- a collapsed one is opaque, same as any other task-shaped box.
export default function BpmnRuleProvider(eventBus, moddle, canvas) {
  // RuleProvider's own constructor calls this.init() synchronously, before control returns here
  // -- _moddle/_canvas must already be set by the time that happens, since init() reads them.
  this._moddle = moddle;
  this._canvas = canvas;
  RuleProvider.call(this, eventBus);
}

inherits(BpmnRuleProvider, RuleProvider);

BpmnRuleProvider.$inject = ['eventBus', 'moddle', 'canvas'];

// Shared by connection.create and connection.reconnect (dragging a sequence flow's own end
// handle onto a different node, via features/bendpoints -- same "is this a legal sequence flow"
// question, just for an existing connection's new endpoint instead of a brand-new one).
function isValidConnection(source, target) {
  if (!isFlowNode(source) || !isFlowNode(target)) return false;
  if (source.type === END_EVENT) return false;
  if (target.type === START_EVENT) return false;
  if (source === target) return false;
  if (source.parent !== target.parent) return false;
  return true;
}

BpmnRuleProvider.prototype.init = function() {
  const moddle = this._moddle;
  const canvas = this._canvas;

  this.addRule('connection.create', function(context) {
    const { source, target } = context;

    if (!isValidConnection(source, target)) return false;

    return {
      type: SEQUENCE_FLOW,
      businessObject: moddle.create(SEQUENCE_FLOW, {
        id: `SequenceFlow_${Date.now().toString(36)}`,
        sourceRef: source.businessObject,
        targetRef: target.businessObject,
      }),
    };
  });

  this.addRule('connection.reconnect', function(context) {
    return isValidConnection(context.source, context.target);
  });

  this.addRule('shape.create', function(context) {
    return isContainer(context.target, canvas.getRootElement());
  });

  this.addRule('elements.move', function(context) {
    const { shapes, target } = context;
    // Move.js's own 'shape.move.start' precheck (MEDIUM_PRIORITY handler) calls this rule with
    // no target at all -- hover hasn't been determined yet at that point, it's supplied only by
    // the later per-hover check ('shape.move.move', LOW_PRIORITY, passing the actual hovered
    // element). Unlike Create.js (which guards `if (!target) return false` itself before ever
    // invoking the shape.create rule), Move.js leaves that to the rule -- rejecting here aborted
    // every drag before it could even start. Verified live: this broke plain canvas dragging
    // entirely until fixed.
    if (!target) return true;

    // Reparenting within the same container is always fine regardless of the containment check
    // below -- this is just an ordinary move, the overwhelmingly common case.
    if (shapes.every((shape) => shape.parent === target)) return true;

    if (!isContainer(target, canvas.getRootElement())) return false;

    // Reject moving a shape into its own descendant (would create a containment cycle) -- walk
    // up from the target through its parent chain looking for any shape being moved, same shape
    // as the DRD canvas's DrdRuleProvider.canReach check, but over containment, not dependencies.
    return shapes.every((shape) => {
      let ancestor = target;
      while (ancestor) {
        if (ancestor === shape) return false;
        ancestor = ancestor.parent;
      }
      return true;
    });
  });

  // No interactive Resize feature is vendored (see SubProcessAutoResizeBehavior.js's note) --
  // this rule has no consumer, kept as an explicit blanket "no" rather than removed so a future
  // resize module addition fails safe (no drag handles ever appear) until this is revisited.
  this.addRule('shape.resize', function() {
    return false;
  });
};
