import inherits from '../../vendor/diagram-js/inherits-browser/dist/index.es.js';
import RuleProvider from '../../vendor/diagram-js/diagram-js/lib/features/rules/RuleProvider.js';
import { isDecisionNode, REQUIREMENT } from './DrdElements.js';
import { nextId } from './dmn-io.js';

// Connection rules for the DRD canvas -- mirrors BpmnRuleProvider.js's connection.create shape,
// but with a real constraint BPMN doesn't need: a DRD must be a DAG (Flowable's decisionService
// topological evaluation has no defined behavior for a cycle), so this rejects any edge that
// would close one, on top of the usual self-loop/duplicate-edge checks.
export default function DrdRuleProvider(eventBus, elementRegistry) {
  this._elementRegistry = elementRegistry;
  RuleProvider.call(this, eventBus);
}

inherits(DrdRuleProvider, RuleProvider);

DrdRuleProvider.$inject = ['eventBus', 'elementRegistry'];

DrdRuleProvider.prototype.init = function() {
  const elementRegistry = this._elementRegistry;

  this.addRule('connection.create', function(context) {
    const { source, target } = context;

    if (!isDecisionNode(source) || !isDecisionNode(target)) return false;
    if (source === target) return false;

    const existingRequirements = elementRegistry.filter((el) => el.type === REQUIREMENT);

    const duplicate = existingRequirements.some((c) => c.source === source && c.target === target);
    if (duplicate) return false;

    // Reject cycles: if `target` can already (transitively) reach `source` via existing
    // requirement arrows, adding source -> target here would close a loop.
    function canReach(from, to, visited) {
      if (from === to) return true;
      if (visited.has(from)) return false;
      visited.add(from);
      return existingRequirements
          .filter((c) => c.source === from)
          .some((c) => canReach(c.target, to, visited));
    }
    if (canReach(target, source, new Set())) return false;

    return {
      type: REQUIREMENT,
      businessObject: { id: nextId('ir'), requiredDecisionId: source.id, dependentDecisionId: target.id },
    };
  });

  this.addRule('shape.create', function() {
    return true;
  });

  this.addRule('shape.resize', function() {
    return false;
  });
};
