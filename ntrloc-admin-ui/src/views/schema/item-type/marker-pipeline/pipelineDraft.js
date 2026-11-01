// The pipeline editor's draft -- { pipeline, positions, edgeBends, conditionalIds, nextRef } -- and the changes made
// to it, each returning a new draft (one undo step each; see useUndoable). New blocks and conditions
// are named by refIds (new-block-N, new-condition-N) until saving creates them.

import { inlinedConditionGroupFor, inlinedConditionGroupOwner } from './markerPipelineLayout'

export function initialDraft(pipelineView) {
  return {
    pipeline: pipelineView.pipeline,
    positions: new Map(Object.entries(pipelineView.layout?.positions ?? {})),
    edgeBends: new Map(Object.entries(pipelineView.layout?.edgeBends ?? {})),
    conditionalIds: new Set(pipelineView.pipeline.carrierBlocks.filter(isConditional).map((b) => b.id)),
    nextRef: 1,
  }
}

const MARKER_BLOCK_TYPES = new Set(['ADD_MARKERS', 'REMOVE_MARKERS', 'SET_MARKERS'])
export const isMarkerBlock = (block) => MARKER_BLOCK_TYPES.has(block.type)

// An Add/Remove/Set block applies only when its conditions hold -- it has an "if" pin. Saved, that's
// one with conditions connected; while editing, also one switched on but not connected yet (see
// conditionalIds).
export function isConditional(block) {
  return isMarkerBlock(block) && (block.gate?.conditionOutputPinIds ?? []).length > 0
}

// An Add/Remove/Set block made conditional, or not -- no longer conditional, its conditions are
// disconnected from it.
export function setBlockConditional(draft, blockId, on) {
  const conditionalIds = new Set(draft.conditionalIds)
  if (on) {
    conditionalIds.add(blockId)
    return { ...draft, conditionalIds }
  }
  conditionalIds.delete(blockId)
  const pins = gatePinsOf(draft.pipeline, blockId)
  const edgeBends = new Map(draft.edgeBends)
  for (const pin of pins) edgeBends.delete(`signal:${pin}=>${blockId}`)
  const pipeline = withGate(draft.pipeline, blockId, (g) => ({ ...g, conditionOutputPinIds: [] }))
  return { ...draft, pipeline, conditionalIds, edgeBends }
}

// A new condition connection: condition pin pinId read by the gate of ownerId (see withGate).
export function addSignalConnection(draft, pinId, ownerId) {
  return { ...draft, pipeline: withGate(draft.pipeline, ownerId, addPin(pinId)) }
}

// The draft with every node's position written down, from `rects` (where each is drawn now) -- so a
// change to the structure can't move one that was only placed where the flow put it.
export function withPositionsFixed(draft, rects) {
  const positions = new Map(draft.positions)
  for (const [id, { x, y }] of rects) if (!positions.has(id)) positions.set(id, { x, y })
  return positions.size === draft.positions.size ? draft : { ...draft, positions }
}

const gate = (quantity = 'ALL', pins = []) => ({ conditionOutputPinIds: pins, quantity })
const condition = (id, predicate = null) => ({ type: 'CONDITION', id, subject: null, predicate })

// A Select's row: the markers it adds when its test (condition conditionId) holds.
const selectRow = (id, conditionId) => ({ type: 'ADD_MARKERS', id, gate: gate('ALL', [conditionId]), markerIds: [] })

// What each palette item adds: a carrier block, a top-level signal, or both -- a Select comes with the
// condition group it reads its tests from, one test to start. ref(kind) makes each refId it needs.
export const NEW_NODES = {
  ADD_MARKERS: (ref) => ({ block: { type: 'ADD_MARKERS', id: ref('block'), gate: gate(), markerIds: [] } }),
  REMOVE_MARKERS: (ref) => ({ block: { type: 'REMOVE_MARKERS', id: ref('block'), gate: gate(), markerIds: [] } }),
  SET_MARKERS: (ref) => ({ block: { type: 'SET_MARKERS', id: ref('block'), gate: gate(), markerIds: [] } }),
  FILTER: (ref) => ({ block: { type: 'FILTER', id: ref('block'), signal: gate() } }),
  SELECT: (ref) => {
    const test = condition(ref('condition'))
    return {
      block: { type: 'SELECT', id: ref('block'), gate: gate('NONE', [test.id]), adds: [selectRow(ref('block'), test.id)] },
      signal: { type: 'CONDITION_GROUP', subject: null, conditions: [test] },
    }
  },
  CONDITION: (ref) => ({ signal: condition(ref('condition')) }),
  CONDITION_GROUP: (ref) => ({ signal: { type: 'CONDITION_GROUP', subject: null, conditions: [condition(ref('condition'))] } }),
  COUNTED: (ref) => ({
    signal: condition(ref('condition'), {
      type: 'COUNTED',
      subject: null,
      subPredicate: null,
      countPredicate: { type: 'NUMBER', operator: 'GREATER_THAN', value: 0 },
    }),
  }),
  TOTALITY: (ref) => ({ signal: condition(ref('condition'), { type: 'TOTALITY', quantity: 'ALL', subPredicates: [] }) }),
}

// The id a node is drawn and placed under: a block's or condition's own, or, for a condition group,
// its first condition's (see markerPipelineLayout's signalNodeId).
export function nodeIdOf({ block, signal }) {
  if (block) return block.id
  return signal.type === 'CONDITION_GROUP' ? `group-${signal.conditions[0].id}` : signal.id
}

// A new block or condition of `kind`, with its top-left corner at `position`. Returns the draft and
// the new node's id.
export function addNode(draft, kind, position) {
  let nextRef = draft.nextRef
  const node = NEW_NODES[kind]((what) => `new-${what}-${nextRef++}`)
  const nodeId = nodeIdOf(node)
  let pipeline = draft.pipeline
  if (node.block) pipeline = { ...pipeline, carrierBlocks: [...pipeline.carrierBlocks, node.block] }
  if (node.signal) pipeline = { ...pipeline, topLevelSignals: [...(pipeline.topLevelSignals ?? []), node.signal] }
  return { draft: { ...draft, pipeline, positions: new Map(draft.positions).set(nodeId, position), nextRef }, nodeId }
}

// --- A Select's tests: its condition group's conditions, each with a row of markers ---

// The Select selectId, and the condition group it reads, changed by fn(select, group) -> [select, group].
function withSelect(draft, selectId, fn) {
  const select = draft.pipeline.carrierBlocks.find((b) => b.id === selectId)
  const group = inlinedConditionGroupFor(draft.pipeline, selectId)
  if (select?.type !== 'SELECT' || !group) return draft
  const [newSelect, newGroup] = fn(select, group)
  const carrierBlocks = draft.pipeline.carrierBlocks.map((b) => (b === select ? newSelect : b))
  const topLevelSignals = draft.pipeline.topLevelSignals.map((s) => (s === group ? newGroup : s))
  return { ...draft, pipeline: { ...draft.pipeline, carrierBlocks, topLevelSignals } }
}

// One more test, with nothing to test yet and no markers -- also counted in its Otherwise.
export function addSelectTest(draft, selectId) {
  const conditionId = `new-condition-${draft.nextRef}`
  const rowId = `new-block-${draft.nextRef + 1}`
  const changed = withSelect(draft, selectId, (select, group) => [
    { ...select, gate: addPin(conditionId)(select.gate), adds: [...select.adds, selectRow(rowId, conditionId)] },
    { ...group, conditions: [...group.conditions, condition(conditionId)] },
  ])
  return changed === draft ? draft : { ...changed, nextRef: draft.nextRef + 2 }
}

// A test out of a Select (which keeps at least one): its row, its condition, and where the row led.
export function removeSelectTest(draft, selectId, rowId) {
  const changed = withSelect(draft, selectId, (select, group) => {
    const row = select.adds.find((r) => r.id === rowId)
    if (!row || select.adds.length <= 1) return [select, group]
    const pins = new Set(row.gate?.conditionOutputPinIds ?? [])
    return [
      { ...select, gate: withoutPins(select.gate, pins), adds: select.adds.filter((r) => r !== row) },
      { ...group, conditions: group.conditions.filter((c) => !pins.has(c.id)) },
    ]
  })
  if (changed === draft) return draft
  const routes = changed.pipeline.routes.filter((r) => r.outputPinId !== rowId)
  const edgeBends = new Map([...changed.edgeBends].filter(([key]) => !key.startsWith(`${rowId}->`)))
  return { ...changed, edgeBends, pipeline: { ...changed.pipeline, routes } }
}

// The markers a Select's row (rowId) adds.
export function setSelectRowMarkers(draft, rowId, markerIds) {
  const carrierBlocks = draft.pipeline.carrierBlocks.map((b) =>
    b.type === 'SELECT' && b.adds.some((r) => r.id === rowId) ? { ...b, adds: b.adds.map((r) => (r.id === rowId ? { ...r, markerIds } : r)) } : b,
  )
  return { ...draft, pipeline: { ...draft.pipeline, carrierBlocks } }
}

// The condition group drawn as node nodeId, changed by fn (its conditions -> new conditions). A group
// is placed under its first condition's id, so a new first condition takes over the old one's place.
function withGroupConditions(draft, nodeId, fn) {
  let positions = draft.positions
  const topLevelSignals = draft.pipeline.topLevelSignals.map((s) => {
    if (nodeIdOf({ signal: s }) !== nodeId) return s
    const changed = { ...s, conditions: fn(s.conditions) }
    const newId = nodeIdOf({ signal: changed })
    if (newId !== nodeId && positions.has(nodeId)) positions = new Map(positions).set(newId, positions.get(nodeId))
    return changed
  })
  return { ...draft, positions, pipeline: { ...draft.pipeline, topLevelSignals } }
}

// One more condition in a group, with nothing to test yet.
export function addGroupCondition(draft, nodeId) {
  const id = `new-condition-${draft.nextRef}`
  return { ...withGroupConditions(draft, nodeId, (conditions) => [...conditions, condition(id)]), nextRef: draft.nextRef + 1 }
}

// A condition out of a group (which keeps at least one), and out of every gate reading it.
export function removeGroupCondition(draft, nodeId, conditionId) {
  const changed = withGroupConditions(draft, nodeId, (conditions) => (conditions.length > 1 ? conditions.filter((c) => c.id !== conditionId) : conditions))
  if (changed.pipeline === draft.pipeline) return draft
  const pins = new Set([conditionId])
  const carrierBlocks = changed.pipeline.carrierBlocks.map((b) => withoutGatePins(b, pins))
  const edgeBends = new Map([...changed.edgeBends].filter(([key]) => !key.startsWith(`signal:${conditionId}=>`)))
  return { ...changed, edgeBends, pipeline: { ...changed.pipeline, carrierBlocks } }
}

export function setBlockMarkers(draft, blockId, markerIds) {
  const carrierBlocks = draft.pipeline.carrierBlocks.map((b) => (b.id === blockId ? { ...b, markerIds } : b))
  return { ...draft, pipeline: { ...draft.pipeline, carrierBlocks } }
}

// A carrier connection (an edge from computeLayout: { key, outputPinId, targetId }) with one end
// moved: its start ('from') to another output pin, or its end ('to') to another block's input.
export function moveCarrierEnd(draft, edge, end, pinId) {
  const outputPinId = end === 'from' ? pinId : edge.outputPinId
  const targetId = end === 'to' ? pinId : edge.targetId
  const routes = draft.pipeline.routes
    .map((r) => (r.outputPinId === edge.outputPinId ? { ...r, inputPinIds: r.inputPinIds.filter((id) => id !== edge.targetId) } : r))
    .filter((r) => r.inputPinIds.length > 0)
  const existing = routes.find((r) => r.outputPinId === outputPinId)
  const moved = existing
    ? routes.map((r) => (r === existing ? { ...r, inputPinIds: [...r.inputPinIds, targetId] } : r))
    : [...routes, { outputPinId, inputPinIds: [targetId] }]
  return withoutBend({ ...draft, pipeline: { ...draft.pipeline, routes: moved } }, edge.key)
}

// A new carrier connection, from an output pin to a block's input.
export function addCarrierConnection(draft, outputPinId, targetId) {
  const { routes } = draft.pipeline
  const existing = routes.find((r) => r.outputPinId === outputPinId)
  const next = existing
    ? routes.map((r) => (r === existing ? { ...r, inputPinIds: [...r.inputPinIds, targetId] } : r))
    : [...routes, { outputPinId, inputPinIds: [targetId] }]
  return { ...draft, pipeline: { ...draft.pipeline, routes: next } }
}

// The gate a condition connects to by ownerId: a block's (a Filter's signal), a <select> row's, or a
// <select>'s own (its Otherwise), changed by fn.
function withGate(pipeline, ownerId, fn) {
  const carrierBlocks = pipeline.carrierBlocks.map((b) => {
    if (b.id === ownerId) return b.type === 'FILTER' ? { ...b, signal: fn(b.signal) } : { ...b, gate: fn(b.gate) }
    if (b.type === 'SELECT' && b.adds.some((row) => row.id === ownerId)) {
      return { ...b, adds: b.adds.map((row) => (row.id === ownerId ? { ...row, gate: fn(row.gate) } : row)) }
    }
    return b
  })
  return { ...pipeline, carrierBlocks }
}

const replacePin = (from, to) => (g) => ({ ...g, conditionOutputPinIds: g.conditionOutputPinIds.map((p) => (p === from ? to : p)) })
const removePin = (pin) => (g) => ({ ...g, conditionOutputPinIds: g.conditionOutputPinIds.filter((p) => p !== pin) })
const addPin = (pin) => (g) => ({ quantity: 'ALL', ...g, conditionOutputPinIds: [...(g?.conditionOutputPinIds ?? []), pin] })

// A condition connection (a signal edge from computeLayout: { key, pinId, ownerId }) with one end
// moved: its start ('from') to another condition's pin, or its end ('to') to another gate.
export function moveSignalEnd(draft, edge, end, id) {
  const pipeline =
    end === 'from'
      ? withGate(draft.pipeline, edge.ownerId, replacePin(edge.pinId, id))
      : withGate(withGate(draft.pipeline, edge.ownerId, removePin(edge.pinId)), id, addPin(edge.pinId))
  return withoutBend({ ...draft, pipeline }, edge.key)
}

// A connection (an edge from computeLayout) removed: a carrier one from its route, a condition one
// from the gate reading it.
export function deleteConnection(draft, edge) {
  const pipeline = edge.outputPinId
    ? {
        ...draft.pipeline,
        routes: draft.pipeline.routes
          .map((r) => (r.outputPinId === edge.outputPinId ? { ...r, inputPinIds: r.inputPinIds.filter((id) => id !== edge.targetId) } : r))
          .filter((r) => r.inputPinIds.length > 0),
      }
    : withGate(draft.pipeline, edge.ownerId, removePin(edge.pinId))
  return withoutBend({ ...draft, pipeline }, edge.key)
}

// A moved connection's bend no longer fits it.
function withoutBend(draft, key) {
  const edgeBends = new Map(draft.edgeBends)
  edgeBends.delete(key)
  return { ...draft, edgeBends }
}

// The gate pins a gate owner (see withGate) reads.
export function gatePinsOf(pipeline, ownerId) {
  for (const b of pipeline.carrierBlocks) {
    if (b.id === ownerId) return (b.type === 'FILTER' ? b.signal : b.gate)?.conditionOutputPinIds ?? []
    const row = b.type === 'SELECT' && b.adds.find((r) => r.id === ownerId)
    if (row) return row.gate?.conditionOutputPinIds ?? []
  }
  return []
}

const withoutPins = (g, pins) => g && { ...g, conditionOutputPinIds: g.conditionOutputPinIds.filter((p) => !pins.has(p)) }

// A carrier block with these condition pins dropped from every gate it has (its rows' too).
function withoutGatePins(b, pins) {
  if (b.type === 'FILTER') return { ...b, signal: withoutPins(b.signal, pins) }
  if (b.type === 'SELECT') return { ...b, gate: withoutPins(b.gate, pins), adds: b.adds.map((row) => ({ ...row, gate: withoutPins(row.gate, pins) })) }
  return { ...b, gate: withoutPins(b.gate, pins) }
}

// A block or condition (group) deleted, with what leads into or out of it: routes from and to a
// block (and its rows), and gates reading a condition.
export function deleteNode(draft, nodeId) {
  const { pipeline } = draft
  const block = pipeline.carrierBlocks.find((b) => b.id === nodeId)
  if (block) {
    const ids = new Set([block.id, ...(block.adds ?? []).map((row) => row.id)])
    const routes = pipeline.routes
      .filter((r) => !ids.has(r.outputPinId))
      .map((r) => ({ ...r, inputPinIds: r.inputPinIds.filter((id) => !ids.has(id)) }))
      .filter((r) => r.inputPinIds.length > 0)
    // A Select's tests go with it.
    const group = block.type === 'SELECT' ? inlinedConditionGroupFor(pipeline, block.id) : null
    const topLevelSignals = (pipeline.topLevelSignals ?? []).filter((s) => s !== group)
    return { ...draft, pipeline: { ...pipeline, carrierBlocks: pipeline.carrierBlocks.filter((b) => b !== block), routes, topLevelSignals } }
  }
  const signal = (pipeline.topLevelSignals ?? []).find((s) => nodeIdOf({ signal: s }) === nodeId)
  if (!signal) return draft
  const pins = new Set(signal.type === 'CONDITION_GROUP' ? signal.conditions.map((c) => c.id) : [signal.id])
  const carrierBlocks = pipeline.carrierBlocks.map((b) => withoutGatePins(b, pins))
  return {
    ...draft,
    pipeline: { ...pipeline, carrierBlocks, topLevelSignals: pipeline.topLevelSignals.filter((s) => s !== signal) },
  }
}

// --- Subjects ---

// A subject (a Context, or null for the item itself) as the steps it takes, outermost first:
// { type: 'STEP_TO_ITEM', linkPerspectiveId, each } | { type: 'STEP_TO_PROPERTY', propertyId } |
// { type: 'STEP_TO_STATE_MACHINE', stateMachineId }. Nothing past a property or a state machine.
export function subjectSteps(context) {
  const steps = []
  for (let step = context; step && step.type !== 'SELF'; step = step.next) steps.push(without(step, 'next'))
  return steps
}

// An object without one of its fields.
export function without(object, key) {
  return Object.fromEntries(Object.entries(object).filter(([k]) => k !== key))
}

// The subject those steps make, back to the item itself (Self) at the end of the chain.
export function subjectFromSteps(steps) {
  return steps.reduceRight((next, step) => (step.type === 'STEP_TO_STATE_MACHINE' ? step : { ...step, each: !!step.each, next }), {
    type: 'SELF',
  })
}

const hasEach = (context) => subjectSteps(context).some((step) => step.each)

// A predicate with a subject but no type yet is a test still being chosen (see the inspector's Reads).
const isLeaf = (predicate) => predicate && predicate.type !== 'COUNTED' && predicate.type !== 'TOTALITY'

// Where a condition (or group) keeps its subject: a group, for all its conditions; a counting
// condition, in its count; any other condition, its own. (A test can read a subject of its own too.)
export function subjectOf(signal) {
  if (signal.type === 'CONDITION' && signal.predicate?.type === 'COUNTED') return signal.predicate.subject
  return signal.subject
}

// keeps(predicate): whether a test still fits the new subject -- one that doesn't is cleared. A test
// reading a subject of its own doesn't read this one, so stays as it is.
// A test reads a subject of its own only under the item itself: given another subject, it reads that.
function withSubject(signal, subject, keeps) {
  if (signal.type === 'CONDITION' && signal.predicate?.type === 'COUNTED') return { ...signal, predicate: { ...signal.predicate, subject } }
  const itself = !subject || subject.type === 'SELF'
  const refit = (c) => {
    if (!isLeaf(c.predicate) || (itself && c.predicate.subject)) return c
    const predicate = without(c.predicate, 'subject')
    return { ...c, predicate: predicate.type && keeps(predicate) ? predicate : null }
  }
  if (signal.type === 'CONDITION_GROUP') return { ...signal, subject, conditions: signal.conditions.map(refit) }
  return refit({ ...signal, subject })
}

// The condition (or group) drawn as node nodeId, given a new subject.
export function setSubject(draft, nodeId, subject, keeps = () => true) {
  const topLevelSignals = draft.pipeline.topLevelSignals.map((s) => (nodeIdOf({ signal: s }) === nodeId ? withSubject(s, subject, keeps) : s))
  return { ...draft, pipeline: { ...draft.pipeline, topLevelSignals } }
}

// Condition conditionId -- on its own, or one of a group's -- given a new test.
export function setPredicate(draft, conditionId, predicate) {
  const withTest = (c) => (c.id === conditionId ? { ...c, predicate } : c)
  const topLevelSignals = draft.pipeline.topLevelSignals.map((s) =>
    s.type === 'CONDITION_GROUP' ? { ...s, conditions: s.conditions.map(withTest) } : withTest(s),
  )
  return { ...draft, pipeline: { ...draft.pipeline, topLevelSignals } }
}

// What's missing from a condition's test, if anything.
function testProblem(predicate) {
  if (!predicate?.type) return 'Choose what it tests.'
  if (predicate.type === 'COUNTED' && !hasEach(predicate.subject)) return 'Count over a link to several items.'
  if (predicate.type === 'COUNTED' && !predicate.subPredicate) return 'Choose what it counts.'
  if (predicate.type === 'TOTALITY' && (predicate.subPredicates ?? []).length < 2) return 'Add at least two tests to combine.'
  return null
}

// What a carrier block still needs: markers, a condition in its "if", rows, something leading in.
function blockProblems(pipeline, block, fedIds, conditionalIds) {
  const hasIf = block.type === 'FILTER' || conditionalIds.has(block.id)
  return [
    block.markerIds?.length === 0 && 'Choose at least one marker.',
    hasIf && gatePinsOf(pipeline, block.id).length === 0 && 'Connect a condition to its "if".',
    block.type === 'SELECT' && block.adds.length === 0 && 'Add at least one row.',
    block.type === 'SELECT' && block.adds.some((r) => r.markerIds.length === 0) && 'Choose markers for each test.',
    !fedIds.has(block.id) && 'Connect something into it.',
  ].filter(Boolean)
}

// What each node still needs before the pipeline can be saved, by node id: a list of messages.
// testProblemOf(signal, condition): what's wrong with a condition's own test given its subject (a
// value missing, or a test its subject can't have), if anything.
// conditionalIds: the blocks switched to conditional (see isConditional).
export function nodeProblems(pipeline, testProblemOf = () => null, conditionalIds = new Set()) {
  const problems = new Map()
  const add = (id, message) => problems.set(id, [...(problems.get(id) ?? []), message])
  const fedIds = new Set(pipeline.routes.flatMap((r) => r.inputPinIds))
  for (const block of pipeline.carrierBlocks) {
    for (const message of blockProblems(pipeline, block, fedIds, conditionalIds)) add(block.id, message)
  }
  for (const signal of pipeline.topLevelSignals ?? []) {
    const conditions = signal.type === 'CONDITION_GROUP' ? signal.conditions : [signal]
    const messages = conditions.map((c) => testProblem(c.predicate) ?? (isLeaf(c.predicate) ? testProblemOf(signal, c) : null))
    // A Select's tests are drawn in it, so their problems are its.
    const at = inlinedConditionGroupOwner(pipeline, signal) ?? nodeIdOf({ signal })
    for (const message of new Set(messages.filter(Boolean))) add(at, message)
  }
  return problems
}
