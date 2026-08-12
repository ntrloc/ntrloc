import { DEFAULT_SIZE, EXPANDED_SUB_PROCESS_SIZE, SUB_PROCESS, isFlowNode, nextId } from './bpmn-elements.js';

// A process's required-to-run inputs, expressed with real BPMN 2.0 (bpmn:InputOutputSpecification/
// dataInput/bpmn:ItemDefinition), not a Flowable extension element -- the spec already has a
// mechanism for exactly this, so there's no reason to invent a parallel one. Deliberately uses
// only a slice of what the full spec allows: one bpmn:InputSet holding every *required* variable's
// dataInput id (the spec's own way of marking a combination of inputs as needed to start), any
// dataInput NOT referenced there is implicitly optional -- not the fuller multi-InputSet "several
// alternative valid combinations" mechanism, since this app has no use for expressing alternatives.
// itemSubjectRef -> bpmn:ItemDefinition.structureRef carries the type, as one of these strings --
// the same vocabulary PropertyType (schema/mutation side) uses, for one consistent type vocabulary
// across the app, though nothing here technically requires them to match that enum.
export const PROCESS_VARIABLE_TYPES = ['STRING', 'INT', 'LONG', 'DOUBLE', 'BOOLEAN', 'DATE', 'DATETIME', 'OBJECT'];

// Reads a process's declared variables back out of its ioSpecification (see buildIoSpecification
// below for how one gets built on export) -- {name, type, required} triples, the shape
// ntrloc-process-editor.js's Required Variables panel and the Run dialog both work with directly.
// dataInputRefs/itemSubjectRef are both isReference properties in the moddle schema, resolved to
// the real DataInput/ItemDefinition objects (not left as raw id strings) once the whole document
// has been parsed by the same moddle.fromXML() call -- defensively handled either way regardless,
// in case a hand-edited or foreign-tool-produced XML only has the id string form.
export function readProcessVariables(process) {
  const ioSpecification = process.ioSpecification;
  if (!ioSpecification) return [];

  const requiredIds = new Set(
    (ioSpecification.inputSets || []).flatMap((set) => (set.dataInputRefs || [])
      .map((ref) => (typeof ref === 'string' ? ref : ref.id))));

  return (ioSpecification.dataInputs || []).map((dataInput) => {
    const itemSubjectRef = dataInput.itemSubjectRef;
    const structureRef = itemSubjectRef && (typeof itemSubjectRef === 'string' ? itemSubjectRef : itemSubjectRef.structureRef);
    return {
      name: dataInput.name,
      type: structureRef || 'STRING',
      required: requiredIds.has(dataInput.id),
    };
  });
}

// The reverse of readProcessVariables -- builds a fresh bpmn:InputOutputSpecification (plus the
// bpmn:ItemDefinitions it references, which must live in Definitions.rootElements alongside the
// process itself, not nested inside it -- ItemDefinition's superClass is RootElement, confirmed
// against the vendored bpmn-moddle bundle's own schema) from the {name, type, required} triples
// the inspector panel edits directly. Returns { ioSpecification: undefined, itemDefinitions: [] }
// for an empty list rather than an InputOutputSpecification with zero dataInputs -- matches this
// file's existing convention of omitting optional structure entirely when there's nothing to say
// (see the process's own documentation field, exportXml below).
export function buildIoSpecification(moddle, variables) {
  if (!variables || variables.length === 0) {
    return { ioSpecification: undefined, itemDefinitions: [] };
  }

  const itemDefinitions = [];
  const dataInputs = [];
  for (const variable of variables) {
    const itemDefinition = moddle.create('bpmn:ItemDefinition', { id: nextId('itemDef'), structureRef: variable.type });
    itemDefinitions.push(itemDefinition);
    dataInputs.push(moddle.create('bpmn:DataInput', { id: nextId('input'), name: variable.name, itemSubjectRef: itemDefinition }));
  }

  // Both inputSets and outputSets are minOccurs="1" on tInputOutputSpecification's own content
  // model, even with zero dataOutputs and/or zero *required* dataInputs -- confirmed live via
  // Flowable's own deploy-time XSD validation ("cvc-complex-type.2.4.b: ... One of
  // '{inputSet, outputSet}' is expected"), not assumed from reading the schema alone. So exactly
  // one of each is always emitted here whenever ioSpecification exists at all, the inputSet's
  // dataInputRefs empty if nothing is marked required -- an all-optional variable list is a real,
  // legal case (documented-but-not-enforced inputs), not the same as "no ioSpecification".
  const requiredDataInputs = dataInputs.filter((_, i) => variables[i].required);
  const inputSets = [moddle.create('bpmn:InputSet', { dataInputRefs: requiredDataInputs })];
  const outputSets = [moddle.create('bpmn:OutputSet', {})];

  const ioSpecification = moddle.create('bpmn:InputOutputSpecification', { dataInputs, inputSets, outputSets });
  return { ioSpecification, itemDefinitions };
}

// Translates between BPMN 2.0 XML (via bpmn-moddle) and diagram-js's in-memory shape/connection
// model. Positions/sizes come from the BPMNDI (bpmndi:BPMNDiagram/BPMNPlane) section when
// present; imported processes that predate this editor (e.g. our own hand-authored
// hello-world.bpmn20.xml) have no BPMNDI at all, so those fall back to a simple left-to-right
// auto-layout in flowElements order instead of every shape stacking at (0,0).
//
// Sub-Process nesting: both bpmn:Process and bpmn:SubProcess expose a flowElements array (the
// BPMN metamodel's FlowElementsContainer), so the same recursive walk handles arbitrary nesting
// depth -- a Sub-Process inside a Sub-Process just works. diagram-js shapes always use absolute
// canvas coordinates regardless of nesting (verified against CreateShapeHandler.js -- a shape's
// `parent` is a logical/z-order relationship only, never a coordinate-space transform), so a
// nested child's x/y sits in the exact same space as its container's, no translation needed
// anywhere in this file. BPMNDI itself never nests (a flat list of BPMNShape/BPMNEdge entries
// each pointing at a bpmnElement id, at any depth) -- only the semantic flowElements do.
const AUTO_LAYOUT_Y = 120;
const AUTO_LAYOUT_GAP = 80;

export async function importXml(moddle, elementFactory, canvas, xml) {
  const { rootElement: definitions } = await moddle.fromXML(xml);
  const process = definitions.rootElements.find((el) => el.$type === 'bpmn:Process');
  if (!process) throw new Error('No bpmn:Process found in this diagram');

  const diagram = (definitions.diagrams || [])[0];
  const planeElements = (diagram && diagram.plane && diagram.plane.planeElement) || [];
  const diByElementId = new Map(planeElements.map((di) => [di.bpmnElement && di.bpmnElement.id, di]));

  const shapesById = new Map();

  // Pass 1: create every shape at every nesting level, registering each in shapesById, before
  // pass 2 creates any sequence flow -- a flow anywhere in the document might reference a shape
  // defined at any level, so every shape must exist first.
  function createShapes(container, parentShape) {
    const flowNodes = (container.flowElements || []).filter((el) => el.$type !== 'bpmn:SequenceFlow');

    // Auto-layout fallback (no BPMNDI) is scoped per container: top-level starts at the
    // established (60, 120) origin; inside a Sub-Process it starts just under that shape's own
    // top-left label instead, in the same absolute coordinate space (see header note).
    let autoLayoutX = parentShape ? parentShape.x + 30 : 60;
    const autoLayoutY = parentShape ? parentShape.y + 70 : AUTO_LAYOUT_Y;

    for (const el of flowNodes) {
      const di = diByElementId.get(el.id);
      const bounds = di && di.bounds;
      const isSubProcessEl = el.$type === SUB_PROCESS;
      const fallbackSize = isSubProcessEl
        ? EXPANDED_SUB_PROCESS_SIZE
        : (DEFAULT_SIZE[el.$type] || { width: 100, height: 80 });

      let x, y;
      if (bounds) {
        ({ x, y } = bounds);
      } else {
        x = autoLayoutX;
        y = autoLayoutY - fallbackSize.height / 2;
        autoLayoutX += fallbackSize.width + AUTO_LAYOUT_GAP;
      }

      const shape = elementFactory.createShape({
        id: el.id,
        type: el.$type,
        businessObject: el,
        // isExpanded is BPMNDI, not semantic (see bpmn-elements.js's isExpandedSubProcess) --
        // mapped onto diagram-js's own `collapsed` property (inverse polarity: collapsed = NOT
        // expanded), the same one ModelingModule's toggleCollapse command reads/writes directly.
        // Default to expanded when a Sub-Process has DI but no explicit attribute (matches the
        // BPMNDI spec's own default), and expanded for a brand-new DI-less one with real children
        // too -- a childless collapsed Sub-Process is the only case that should ever default
        // collapsed.
        collapsed: isSubProcessEl
          ? (di ? di.isExpanded === false : !(el.flowElements || []).some((child) => child.$type !== 'bpmn:SequenceFlow'))
          : undefined,
        // GraphicsFactory skips drawing anything hidden (verified directly against
        // GraphicsFactory.js), which is what actually keeps a collapsed Sub-Process's contents
        // off the canvas -- ToggleShapeCollapseHandler sets this on live toggles, but nothing
        // does it for freshly-imported ones unless it's set here too. Inherits down the parent
        // chain (parentShape.hidden) so an already-hidden grandparent's contents stay hidden
        // through an intermediate expanded Sub-Process, not just a directly-collapsed parent.
        hidden: parentShape ? (parentShape.hidden || parentShape.collapsed === true) : false,
        x,
        y,
        width: bounds ? bounds.width : fallbackSize.width,
        height: bounds ? bounds.height : fallbackSize.height,
      });
      canvas.addShape(shape, parentShape);
      shapesById.set(el.id, shape);

      if (isSubProcessEl) {
        createShapes(el, shape);
      }
    }
  }

  createShapes(process, undefined);

  // Pass 2: create every sequence flow, at every level, now that every shape it could possibly
  // reference already exists.
  function createSequenceFlows(container) {
    const sequenceFlows = (container.flowElements || []).filter((el) => el.$type === 'bpmn:SequenceFlow');

    for (const el of sequenceFlows) {
      const source = shapesById.get(el.sourceRef && el.sourceRef.id);
      const target = shapesById.get(el.targetRef && el.targetRef.id);
      if (!source || !target) continue;

      const di = diByElementId.get(el.id);
      // No BPMNDI to fall back on: since the auto-layout above always arranges nodes strictly
      // left-to-right, connect right-edge-center to left-edge-center rather than center-to-center
      // -- that's not just an approximation here, it's the correct docking point for this layout,
      // and (unlike center-to-center) it doesn't draw straight through each shape's own label.
      const waypoints = di && di.waypoint && di.waypoint.length
          ? di.waypoint.map((wp) => ({ x: wp.x, y: wp.y }))
          : [
            { x: source.x + source.width, y: source.y + source.height / 2 },
            { x: target.x, y: target.y + target.height / 2 },
          ];

      const connection = elementFactory.createConnection({
        id: el.id,
        type: el.$type,
        businessObject: el,
        source,
        target,
        waypoints,
        // Same reasoning as the shape hidden flag above -- a flow entirely inside a collapsed
        // Sub-Process needs to be hidden too, or it'd render floating on its own once its source/
        // target shapes are hidden but it isn't. source.parent is the flow's real container.
        hidden: source.parent ? (source.parent.hidden || source.parent.collapsed === true) : false,
      });
      // Explicit parent (Canvas.addConnection defaults to root otherwise -- verified against
      // Canvas.js's _addElement) -- source.parent is the container this flow's own XML element
      // actually lives inside, which BpmnRuleProvider's connection.create rule guarantees equals
      // target.parent for anything drawn live; source.parent is still the right call even for a
      // hand-edited import that violated that constraint, since it's this flow's real owner.
      canvas.addConnection(connection, source.parent);
    }

    for (const el of (container.flowElements || [])) {
      if (el.$type === SUB_PROCESS) createSequenceFlows(el);
    }
  }

  createSequenceFlows(process);

  return process;
}

// Rebuilds a full bpmn:Definitions (semantic process + BPMNDI layout) from whatever's currently
// on the canvas -- not a patch against the original XML, a fresh export every time. Every shape/
// connection already carries its real businessObject (attached at creation time, either during
// import or by the palette/rule provider), so this just re-collects them plus their current
// visual position into the two BPMNDI structures BPMN expects.
export async function exportXml(moddle, elementRegistry, canvas, processId, processName, processDescription, processVariables, processRunAsUser) {
  const allElements = elementRegistry.getAll().filter((el) => el.businessObject);

  // Partition into per-parent buckets up front, keyed by the parent shape object itself (a
  // top-level element's `.parent` already IS the canvas root element -- Canvas._addElement
  // defaults an omitted parent to getRootElement(), verified directly against Canvas.js -- so
  // grouping by reference naturally separates "top-level" from "inside some Sub-Process" with no
  // special-casing for the root bucket).
  const shapesByParent = new Map();
  const connectionsByParent = new Map();
  for (const el of allElements) {
    const bucket = el.waypoints ? connectionsByParent : shapesByParent;
    if (!bucket.has(el.parent)) bucket.set(el.parent, []);
    bucket.get(el.parent).push(el);
  }

  // Recursively builds a container's flowElements array (works for both bpmn:Process, called
  // once at the root, and any bpmn:SubProcess found along the way) -- for every Sub-Process
  // among a level's shapes, recurses to populate that Sub-Process's *own* flowElements as a side
  // effect on its businessObject before this level's array is returned, so arbitrary nesting
  // depth falls out for free.
  function buildFlowElements(parentElement) {
    const shapes = shapesByParent.get(parentElement) || [];
    const connections = connectionsByParent.get(parentElement) || [];

    for (const shape of shapes) {
      if (shape.type === SUB_PROCESS) {
        shape.businessObject.flowElements = buildFlowElements(shape);
      }
    }

    return [...shapes, ...connections].map((el) => el.businessObject);
  }

  const flowElements = buildFlowElements(canvas.getRootElement());

  const { ioSpecification, itemDefinitions } = buildIoSpecification(moddle, processVariables);

  const process = moddle.create('bpmn:Process', {
    id: processId,
    name: processName || undefined,
    isExecutable: true,
    flowElements,
    // Same documentation-array shape every element's own Description field already uses
    // (setElementDocumentation, ntrloc-process-editor.js) -- inlined here rather than imported
    // since that helper lives in the component module that itself imports this one.
    documentation: processDescription && processDescription.trim()
      ? [moddle.create('bpmn:Documentation', { text: processDescription.trim() })]
      : undefined,
    ioSpecification,
  });

  // Flowable-namespace, not core BPMN -- see ntrloc-process-editor.js's own note on where this
  // gets read back on import. $attrs on a freshly moddle.create()-ed element starts as {} (same
  // "always initialized, only mutable in place" guarantee as an imported one), so this is safe to
  // assign into directly right after creation rather than needing to pass it through create()'s
  // own properties object.
  if (processRunAsUser && processRunAsUser.trim()) {
    process.$attrs['flowable:runAsUser'] = processRunAsUser.trim();
  }

  // BPMNDI stays a single flat list regardless of nesting depth (BPMNDI never nests -- see
  // header note), just also writing isExpanded onto a Sub-Process's own BPMNShape now.
  const planeElements = [];
  for (const shape of allElements.filter((el) => !el.waypoints)) {
    const shapeAttrs = {
      bpmnElement: shape.businessObject,
      bounds: moddle.create('dc:Bounds', {
        x: shape.x, y: shape.y, width: shape.width, height: shape.height,
      }),
    };
    if (shape.type === SUB_PROCESS) {
      shapeAttrs.isExpanded = !shape.collapsed;
    }
    planeElements.push(moddle.create('bpmndi:BPMNShape', shapeAttrs));
  }
  for (const connection of allElements.filter((el) => el.waypoints)) {
    planeElements.push(moddle.create('bpmndi:BPMNEdge', {
      bpmnElement: connection.businessObject,
      waypoint: connection.waypoints.map((wp) => moddle.create('dc:Point', { x: wp.x, y: wp.y })),
    }));
  }

  const plane = moddle.create('bpmndi:BPMNPlane', { bpmnElement: process, planeElement: planeElements });
  const diagram = moddle.create('bpmndi:BPMNDiagram', { plane });

  const definitions = moddle.create('bpmn:Definitions', {
    targetNamespace: 'org.ntrloc.workflow',
    // itemDefinitions are siblings of the process, not nested inside it -- ItemDefinition's
    // superClass is RootElement (see buildIoSpecification's header comment), same level Process
    // itself lives at.
    rootElements: [process, ...itemDefinitions],
    diagrams: [diagram],
    // Declares the Flowable extension namespace unconditionally: elements this editor doesn't
    // author itself (e.g. our own hello-world process's serviceTask, which carries a
    // flowable:delegateExpression attribute bpmn-moddle preserves in businessObject.$attrs since
    // it's outside the core BPMN schema) round-trip correctly only if this namespace is declared
    // -- without it, moddle.toXML() silently drops any flowable:* attribute rather than erroring.
    'xmlns:flowable': 'http://flowable.org/bpmn',
  });

  const { xml } = await moddle.toXML(definitions, { format: true });
  return xml;
}

export { isFlowNode };
