// Shared constants/helpers for the DRD canvas -- only one shape type and one connection type
// exist, so this is much smaller than bpmn-elements.js's equivalent.
export const DECISION_NODE = 'drd:DecisionNode';
export const REQUIREMENT = 'drd:Requirement';

export function isDecisionNode(element) {
  return !!(element && element.type === DECISION_NODE);
}

// Builds a diagram-js Shape for a decision node. Unlike the BPMN editor, there's no moddle
// schema here -- a shape's businessObject *is* the plain model object itself (dmn-io.js's
// `decisions` array entries), so editing a node's name/table content never needs a separate sync
// step. Position is the one exception: diagram-js tracks a shape's x/y/width/height on the shape
// itself, not businessObject, so those get read back out of the element registry at save time
// instead (see ntrloc-decision-table-editor.js's save()).
export function createDrdShape(elementFactory, node) {
  return elementFactory.createShape({
    type: DECISION_NODE,
    id: node.id,
    businessObject: node,
    x: node.x || 0,
    y: node.y || 0,
    width: node.width,
    height: node.height,
  });
}
