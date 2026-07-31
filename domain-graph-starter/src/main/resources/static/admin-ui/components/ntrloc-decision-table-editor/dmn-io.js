// Hand-rolled DMN 1.3 XML serialize/parse -- no dmn-moddle available (same bpmn.io watermark
// license issue that ruled out bpmn-js for the BPMN editor), but the XML shape needed here is
// small and fully known (verified against Flowable 8.0.0's own model/xml-converter/DMNDI-export
// source, not the OMG spec text), so direct DOMParser/template-literal round-tripping is
// straightforward, unlike full BPMN.
//
// A "DRD" here is always a bundle: one or more decision tables (nodes) plus dependency arrows
// (informationRequirement) between them, always wrapped in a single <decisionService> on save --
// even a lone, dependency-free table is a "bundle of one" (one outputDecision, nothing
// encapsulated, no informationRequirement anywhere). This keeps one XML shape for every case
// instead of branching between "simple" and "linked" decisions. The bundle's own id/name (the
// deploy key any BPMN DMN Task references) is exposed as decisionServiceKey/decisionServiceName;
// each node's own id is internal and never user-facing.
export const DMN_NAMESPACE = 'https://www.omg.org/spec/DMN/20191111/MODEL/';
export const DMNDI_NAMESPACE = 'https://www.omg.org/spec/DMN/20191111/DMNDI/';
export const DC_NAMESPACE = 'http://www.omg.org/spec/DMN/20180521/DC/';
export const DI_NAMESPACE = 'http://www.omg.org/spec/DMN/20180521/DI/';

export const HIT_POLICIES = ['UNIQUE', 'FIRST', 'PRIORITY', 'ANY', 'UNORDERED', 'RULE ORDER', 'OUTPUT ORDER', 'COLLECT'];

export const TYPE_REFS = ['string', 'number', 'boolean', 'date'];

export const NODE_WIDTH = 180;
export const NODE_HEIGHT = 80;

let idCounter = 0;

export function nextId(prefix) {
  idCounter += 1;
  return `${prefix}_${Date.now().toString(36)}_${idCounter}`;
}

function emptyDecisionData() {
  const input = { id: nextId('input'), label: 'Input', expression: '', typeRef: 'string' };
  const output = { id: nextId('output'), label: 'Output', name: 'output', typeRef: 'string' };
  return {
    hitPolicy: 'UNIQUE',
    inputs: [input],
    outputs: [output],
    rules: [{ id: nextId('rule'), inputEntries: [''], outputEntries: [''] }],
  };
}

// A brand-new node's position is left null -- applyAutoLayout (called by the canvas on every
// render) fills in any node still missing x/y, whether that's every node in a fresh DRD or just
// one freshly added to an existing one.
export function newDecisionNode(name) {
  return {
    id: nextId('decision'),
    name: name || 'Decision',
    x: null, y: null, width: NODE_WIDTH, height: NODE_HEIGHT,
    ...emptyDecisionData(),
  };
}

export function emptyDrdModel() {
  return {
    decisionServiceKey: '',
    decisionServiceName: '',
    decisions: [newDecisionNode('Decision 1')],
    requirements: [],
  };
}

// Depth-layered auto-layout for any decision still missing a position (freshly added, or the
// whole model on first import when no DMNDI existed yet) -- depth 0 = requires nothing, depth =
// 1 + max(depth of required decisions) otherwise, laid out in left-to-right columns by depth
// (matching the BPMN editor's own left-to-right reading convention), stacking same-depth nodes
// vertically within a column. DAG-aware evolution of bpmn-io.js's flat left-to-right
// autoLayoutX/AUTO_LAYOUT_Y/AUTO_LAYOUT_GAP fallback for imports with no BPMNDI.
const COLUMN_GAP = 120;
const ROW_GAP = 40;
const START_X = 60;
const START_Y = 60;

function computeDepths(decisions, requirements) {
  const requiredIdsByDecision = new Map(decisions.map((d) => [d.id, []]));
  requirements.forEach((r) => {
    const list = requiredIdsByDecision.get(r.dependentDecisionId);
    if (list) list.push(r.requiredDecisionId);
  });

  const depths = new Map();
  function depthOf(id, seen) {
    if (depths.has(id)) return depths.get(id);
    // Cycle guard -- DrdRuleProvider blocks creating one via the canvas, but a hand-edited/
    // malformed import could still contain one; treat it as depth 0 rather than recursing forever.
    if (seen.has(id)) return 0;
    seen.add(id);
    const required = requiredIdsByDecision.get(id) || [];
    const depth = required.length === 0 ? 0 : 1 + Math.max(...required.map((rid) => depthOf(rid, seen)));
    depths.set(id, depth);
    return depth;
  }

  decisions.forEach((d) => depthOf(d.id, new Set()));
  return depths;
}

export function applyAutoLayout(decisions, requirements) {
  const depths = computeDepths(decisions, requirements);
  const columnCounts = new Map();
  decisions.forEach((d) => {
    if (d.x != null && d.y != null) return;
    const depth = depths.get(d.id) || 0;
    const rowIndex = columnCounts.get(depth) || 0;
    columnCounts.set(depth, rowIndex + 1);
    d.x = START_X + depth * (NODE_WIDTH + COLUMN_GAP);
    d.y = START_Y + rowIndex * (NODE_HEIGHT + ROW_GAP);
    d.width = d.width || NODE_WIDTH;
    d.height = d.height || NODE_HEIGHT;
  });
}

// outputDecisions = decisions nothing else in the bundle requires (sinks -- the bundle's actual
// results); encapsulatedDecisions = every other decision (consumed internally by another decision
// in the same bundle, not directly returned). Verified against Flowable's own DecisionService
// model/XML converter -- see docs/plan notes for the exact element shape this feeds.
function classifyDecisions(decisions, requirements) {
  const requiredIds = new Set(requirements.map((r) => r.requiredDecisionId));
  return {
    outputDecisions: decisions.filter((d) => !requiredIds.has(d.id)),
    encapsulatedDecisions: decisions.filter((d) => requiredIds.has(d.id)),
  };
}

function escapeXml(value) {
  return String(value ?? '')
      .replace(/&/g, '&amp;')
      .replace(/</g, '&lt;')
      .replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;');
}

function serializeDecisionBlock(decision, requirements) {
  const ownRequirements = requirements.filter((r) => r.dependentDecisionId === decision.id);
  const requirementsXml = ownRequirements.map((r) => `
    <informationRequirement id="${escapeXml(r.id)}">
      <requiredDecision href="#${escapeXml(r.requiredDecisionId)}"/>
    </informationRequirement>`).join('');

  const inputsXml = decision.inputs.map((input) => `
      <input id="${escapeXml(input.id)}" label="${escapeXml(input.label)}">
        <inputExpression id="${escapeXml(input.id)}_expr" typeRef="${escapeXml(input.typeRef)}"><text>${escapeXml(input.expression)}</text></inputExpression>
      </input>`).join('');

  const outputsXml = decision.outputs.map((output) => `
      <output id="${escapeXml(output.id)}" label="${escapeXml(output.label)}" name="${escapeXml(output.name)}" typeRef="${escapeXml(output.typeRef)}"/>`).join('');

  const rulesXml = decision.rules.map((rule) => `
      <rule id="${escapeXml(rule.id)}">
        ${rule.inputEntries.map((text, i) => `<inputEntry id="${escapeXml(rule.id)}_in${i}"><text>${escapeXml(text)}</text></inputEntry>`).join('\n        ')}
        ${rule.outputEntries.map((text, i) => `<outputEntry id="${escapeXml(rule.id)}_out${i}"><text>${escapeXml(text)}</text></outputEntry>`).join('\n        ')}
      </rule>`).join('');

  return `
  <decision id="${escapeXml(decision.id)}" name="${escapeXml(decision.name)}">${requirementsXml}
    <decisionTable id="decisionTable_${escapeXml(decision.id)}" hitPolicy="${escapeXml(decision.hitPolicy)}">${inputsXml}${outputsXml}${rulesXml}
    </decisionTable>
  </decision>`;
}

export function serializeDrd(model) {
  const { decisions, requirements, decisionServiceKey, decisionServiceName } = model;
  const decisionsXml = decisions.map((d) => serializeDecisionBlock(d, requirements)).join('');

  const { outputDecisions, encapsulatedDecisions } = classifyDecisions(decisions, requirements);
  const decisionServiceXml = `
  <decisionService id="${escapeXml(decisionServiceKey)}" name="${escapeXml(decisionServiceName)}">
    ${outputDecisions.map((d) => `<outputDecision href="#${escapeXml(d.id)}"/>`).join('\n    ')}
    ${encapsulatedDecisions.map((d) => `<encapsulatedDecision href="#${escapeXml(d.id)}"/>`).join('\n    ')}
  </decisionService>`;

  const shapesXml = decisions.map((d) => `
      <dmndi:DMNShape id="DMNShape_${escapeXml(d.id)}" dmnElementRef="${escapeXml(d.id)}">
        <dc:Bounds x="${d.x}" y="${d.y}" width="${d.width}" height="${d.height}"/>
      </dmndi:DMNShape>`).join('');

  // Flowable's own deploy-time diagram generator (DefaultDecisionRequirementsDiagramGenerator,
  // called to render the deployment's preview PNG resource) requires a GraphicInfo for the
  // decisionService element itself, not just its inner decisions -- verified live: deploying
  // without one throws FlowableImageException "Could not find graphic info for decision service".
  // It further requires a DMNDecisionServiceDividerLine child (DmnDefinition.
  // getDecisionServiceDividerGraphicInfo returning null otherwise throws a NullPointerException
  // inside drawDecisionService) -- real DMN tools draw a decision service as a rectangle split by
  // this line into an "output decisions" region above and an "encapsulated decisions" region
  // below; our own canvas never renders this shape at all (DrdRenderer.js draws individual nodes
  // and arrows directly), so both this box and its divider exist purely to satisfy Flowable's
  // deploy-time image generator, not for anything this editor itself displays.
  const SERVICE_PADDING = 30;
  const minX = Math.min(...decisions.map((d) => d.x));
  const minY = Math.min(...decisions.map((d) => d.y));
  const maxX = Math.max(...decisions.map((d) => d.x + d.width));
  const maxY = Math.max(...decisions.map((d) => d.y + d.height));
  const serviceX = minX - SERVICE_PADDING;
  const serviceY = minY - SERVICE_PADDING;
  const serviceWidth = maxX - minX + SERVICE_PADDING * 2;
  const serviceHeight = maxY - minY + SERVICE_PADDING * 2;
  const dividerY = serviceY + serviceHeight / 2;
  const serviceShapeXml = `
      <dmndi:DMNShape id="DMNShape_${escapeXml(decisionServiceKey)}" dmnElementRef="${escapeXml(decisionServiceKey)}">
        <dc:Bounds x="${serviceX}" y="${serviceY}" width="${serviceWidth}" height="${serviceHeight}"/>
        <dmndi:DMNDecisionServiceDividerLine>
          <di:waypoint x="${serviceX}" y="${dividerY}"/>
          <di:waypoint x="${serviceX + serviceWidth}" y="${dividerY}"/>
        </dmndi:DMNDecisionServiceDividerLine>
      </dmndi:DMNShape>`;

  const edgesXml = requirements.map((r) => {
    const from = decisions.find((d) => d.id === r.requiredDecisionId);
    const to = decisions.find((d) => d.id === r.dependentDecisionId);
    if (!from || !to) return '';
    // Right-edge-center of the required (upstream) node to left-edge-center of the dependent
    // (downstream) one -- matches the left-to-right column layout above; the real diagram-js
    // canvas re-crops these properly via ConnectionLayouter, this is just the persisted fallback.
    const x1 = from.x + from.width, y1 = from.y + from.height / 2;
    const x2 = to.x, y2 = to.y + to.height / 2;
    return `
      <dmndi:DMNEdge id="DMNEdge_${escapeXml(r.id)}" dmnElementRef="${escapeXml(r.id)}">
        <di:waypoint x="${x1}" y="${y1}"/>
        <di:waypoint x="${x2}" y="${y2}"/>
      </dmndi:DMNEdge>`;
  }).join('');

  return `<?xml version="1.0" encoding="UTF-8"?>
<definitions id="definitions_${escapeXml(decisionServiceKey)}" name="${escapeXml(decisionServiceName)}" namespace="http://flowable.org/dmn"
             xmlns="${DMN_NAMESPACE}"
             xmlns:dmndi="${DMNDI_NAMESPACE}"
             xmlns:dc="${DC_NAMESPACE}"
             xmlns:di="${DI_NAMESPACE}"
             xmlns:flowable="http://flowable.org/dmn">${decisionsXml}${decisionServiceXml}
  <dmndi:DMNDI>
    <dmndi:DMNDiagram id="DMNDiagram_${escapeXml(decisionServiceKey)}">${serviceShapeXml}${shapesXml}${edgesXml}
    </dmndi:DMNDiagram>
  </dmndi:DMNDI>
</definitions>
`;
}

// Backward-compatible with a bare, unwrapped <decision> (today's shape, and any already-deployed
// single tables, including the seed resource before this feature existed) -- a <decisionService>
// is never assumed present on read, only always written on save. Decisions without a
// <decisionTable> (nothing this editor could have authored) are skipped rather than crashing.
export function parseDrd(xmlString) {
  const doc = new DOMParser().parseFromString(xmlString, 'application/xml');
  if (doc.querySelector('parsererror')) {
    throw new Error('Malformed DMN XML');
  }

  const decisionEls = Array.from(doc.getElementsByTagNameNS(DMN_NAMESPACE, 'decision'));
  if (decisionEls.length === 0) throw new Error('No decision found in this DMN XML');

  const decisionServiceEl = doc.getElementsByTagNameNS(DMN_NAMESPACE, 'decisionService')[0];

  const shapesByRef = new Map();
  Array.from(doc.getElementsByTagNameNS(DMNDI_NAMESPACE, 'DMNShape')).forEach((shapeEl) => {
    const ref = shapeEl.getAttribute('dmnElementRef');
    const boundsEl = shapeEl.getElementsByTagNameNS(DC_NAMESPACE, 'Bounds')[0];
    if (ref && boundsEl) {
      shapesByRef.set(ref, {
        x: Number(boundsEl.getAttribute('x')),
        y: Number(boundsEl.getAttribute('y')),
        width: Number(boundsEl.getAttribute('width')) || NODE_WIDTH,
        height: Number(boundsEl.getAttribute('height')) || NODE_HEIGHT,
      });
    }
  });

  const decisions = decisionEls.map((decisionEl) => {
    const decisionTableEl = decisionEl.getElementsByTagNameNS(DMN_NAMESPACE, 'decisionTable')[0];
    if (!decisionTableEl) return null;

    const id = decisionEl.getAttribute('id');
    const hitPolicy = decisionTableEl.getAttribute('hitPolicy') || 'UNIQUE';

    const inputs = Array.from(decisionTableEl.getElementsByTagNameNS(DMN_NAMESPACE, 'input')).map((inputEl) => {
      const exprEl = inputEl.getElementsByTagNameNS(DMN_NAMESPACE, 'inputExpression')[0];
      const textEl = exprEl && exprEl.getElementsByTagNameNS(DMN_NAMESPACE, 'text')[0];
      return {
        id: inputEl.getAttribute('id') || nextId('input'),
        label: inputEl.getAttribute('label') || '',
        expression: textEl ? textEl.textContent : '',
        typeRef: (exprEl && exprEl.getAttribute('typeRef')) || 'string',
      };
    });

    const outputs = Array.from(decisionTableEl.getElementsByTagNameNS(DMN_NAMESPACE, 'output')).map((outputEl) => ({
      id: outputEl.getAttribute('id') || nextId('output'),
      label: outputEl.getAttribute('label') || '',
      name: outputEl.getAttribute('name') || '',
      typeRef: outputEl.getAttribute('typeRef') || 'string',
    }));

    const rules = Array.from(decisionTableEl.getElementsByTagNameNS(DMN_NAMESPACE, 'rule')).map((ruleEl) => ({
      id: ruleEl.getAttribute('id') || nextId('rule'),
      inputEntries: Array.from(ruleEl.getElementsByTagNameNS(DMN_NAMESPACE, 'inputEntry')).map((entryEl) => {
        const textEl = entryEl.getElementsByTagNameNS(DMN_NAMESPACE, 'text')[0];
        return textEl ? textEl.textContent : '';
      }),
      outputEntries: Array.from(ruleEl.getElementsByTagNameNS(DMN_NAMESPACE, 'outputEntry')).map((entryEl) => {
        const textEl = entryEl.getElementsByTagNameNS(DMN_NAMESPACE, 'text')[0];
        return textEl ? textEl.textContent : '';
      }),
    }));

    const shape = shapesByRef.get(id);
    return {
      id,
      name: decisionEl.getAttribute('name') || '',
      x: shape ? shape.x : null,
      y: shape ? shape.y : null,
      width: shape ? shape.width : NODE_WIDTH,
      height: shape ? shape.height : NODE_HEIGHT,
      hitPolicy, inputs, outputs, rules,
    };
  }).filter(Boolean);

  const decisionIds = new Set(decisions.map((d) => d.id));
  const requirements = [];
  decisionEls.forEach((decisionEl) => {
    const dependentId = decisionEl.getAttribute('id');
    if (!decisionIds.has(dependentId)) return;
    Array.from(decisionEl.getElementsByTagNameNS(DMN_NAMESPACE, 'informationRequirement')).forEach((irEl) => {
      const requiredDecisionEl = irEl.getElementsByTagNameNS(DMN_NAMESPACE, 'requiredDecision')[0];
      const href = requiredDecisionEl && requiredDecisionEl.getAttribute('href');
      const requiredId = href ? href.replace(/^#/, '') : null;
      if (!requiredId || !decisionIds.has(requiredId)) return;
      requirements.push({
        id: irEl.getAttribute('id') || nextId('ir'),
        requiredDecisionId: requiredId,
        dependentDecisionId: dependentId,
      });
    });
  });

  const decisionServiceKey = decisionServiceEl ? decisionServiceEl.getAttribute('id') : decisions[0].id;
  const decisionServiceName = decisionServiceEl ? decisionServiceEl.getAttribute('name') : decisions[0].name;

  applyAutoLayout(decisions, requirements);

  return { decisionServiceKey, decisionServiceName, decisions, requirements };
}
