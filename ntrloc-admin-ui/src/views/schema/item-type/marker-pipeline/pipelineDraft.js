// The pipeline editor's draft -- { pipeline, positions, edgeBends, nextRef } -- and the changes made
// to it, each returning a new draft (one undo step each; see useUndoable). New blocks and conditions
// are named by refIds (new-block-N, new-condition-N) until saving creates them.

export function initialDraft(pipelineView) {
  return {
    pipeline: pipelineView.pipeline,
    positions: new Map(Object.entries(pipelineView.layout?.positions ?? {})),
    edgeBends: new Map(Object.entries(pipelineView.layout?.edgeBends ?? {})),
    nextRef: 1,
  }
}

// The draft with every node's position written down, from `rects` (where each is drawn now) -- so a
// change to the structure can't move one that was only placed where the flow put it.
export function withPositionsFixed(draft, rects) {
  const positions = new Map(draft.positions)
  for (const [id, { x, y }] of rects) if (!positions.has(id)) positions.set(id, { x, y })
  return positions.size === draft.positions.size ? draft : { ...draft, positions }
}

const gate = (quantity = 'ALL') => ({ conditionOutputPinIds: [], quantity })
const condition = (id, predicate = null) => ({ type: 'CONDITION', id, subject: null, predicate })

// What each palette item adds, given the refIds it needs: a carrier block, or a top-level signal.
export const NEW_NODES = {
  ADD_MARKERS: ([id]) => ({ block: { type: 'ADD_MARKERS', id, gate: gate(), markerIds: [] } }),
  REMOVE_MARKERS: ([id]) => ({ block: { type: 'REMOVE_MARKERS', id, gate: gate(), markerIds: [] } }),
  SET_MARKERS: ([id]) => ({ block: { type: 'SET_MARKERS', id, gate: gate(), markerIds: [] } }),
  FILTER: ([id]) => ({ block: { type: 'FILTER', id, signal: gate() } }),
  SELECT: ([id]) => ({ block: { type: 'SELECT', id, gate: gate('NONE'), adds: [] } }),
  CONDITION: ([id]) => ({ signal: condition(id) }),
  CONDITION_GROUP: ([a, b]) => ({ signal: { type: 'CONDITION_GROUP', subject: null, conditions: [condition(a), condition(b)] } }),
  COUNTED: ([id]) => ({
    signal: condition(id, { type: 'COUNTED', subject: null, subPredicate: null, countPredicate: { type: 'NUMBER', operator: 'GREATER_THAN', value: 0 } }),
  }),
  TOTALITY: ([id]) => ({ signal: condition(id, { type: 'TOTALITY', quantity: 'ALL', subPredicates: [] }) }),
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
  const isCondition = !['ADD_MARKERS', 'REMOVE_MARKERS', 'SET_MARKERS', 'FILTER', 'SELECT'].includes(kind)
  const refs = [0, 1].map((i) => `new-${isCondition ? 'condition' : 'block'}-${draft.nextRef + i}`)
  const node = NEW_NODES[kind](refs)
  const nodeId = nodeIdOf(node)
  const pipeline = node.block
    ? { ...draft.pipeline, carrierBlocks: [...draft.pipeline.carrierBlocks, node.block] }
    : { ...draft.pipeline, topLevelSignals: [...(draft.pipeline.topLevelSignals ?? []), node.signal] }
  return {
    draft: { ...draft, pipeline, positions: new Map(draft.positions).set(nodeId, position), nextRef: draft.nextRef + refs.length },
    nodeId,
  }
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
const addPin = (pin) => (g) => ({ ...g, conditionOutputPinIds: [...g.conditionOutputPinIds, pin] })

// A condition connection (a signal edge from computeLayout: { key, pinId, ownerId }) with one end
// moved: its start ('from') to another condition's pin, or its end ('to') to another gate.
export function moveSignalEnd(draft, edge, end, id) {
  const pipeline =
    end === 'from'
      ? withGate(draft.pipeline, edge.ownerId, replacePin(edge.pinId, id))
      : withGate(withGate(draft.pipeline, edge.ownerId, removePin(edge.pinId)), id, addPin(edge.pinId))
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
    return { ...draft, pipeline: { ...pipeline, carrierBlocks: pipeline.carrierBlocks.filter((b) => b !== block), routes } }
  }
  const signal = (pipeline.topLevelSignals ?? []).find((s) => nodeIdOf({ signal: s }) === nodeId)
  if (!signal) return draft
  const pins = new Set(signal.type === 'CONDITION_GROUP' ? signal.conditions.map((c) => c.id) : [signal.id])
  const carrierBlocks = pipeline.carrierBlocks.map((b) => {
    if (b.type === 'FILTER') return { ...b, signal: withoutPins(b.signal, pins) }
    if (b.type === 'SELECT') return { ...b, gate: withoutPins(b.gate, pins), adds: b.adds.map((row) => ({ ...row, gate: withoutPins(row.gate, pins) })) }
    return { ...b, gate: withoutPins(b.gate, pins) }
  })
  return {
    ...draft,
    pipeline: { ...pipeline, carrierBlocks, topLevelSignals: pipeline.topLevelSignals.filter((s) => s !== signal) },
  }
}

// What's missing from a condition's test, if anything.
function testProblem(predicate) {
  if (!predicate) return 'Choose what it tests.'
  if (predicate.type === 'COUNTED' && (!predicate.subject || !predicate.subPredicate)) return 'Choose what it counts.'
  if (predicate.type === 'TOTALITY' && (predicate.subPredicates ?? []).length < 2) return 'Add at least two tests to combine.'
  return null
}

// What each node still needs before the pipeline can be saved, by node id: a list of messages.
export function nodeProblems(pipeline) {
  const problems = new Map()
  const add = (id, message) => problems.set(id, [...(problems.get(id) ?? []), message])
  const fedIds = new Set(pipeline.routes.flatMap((r) => r.inputPinIds))
  for (const block of pipeline.carrierBlocks) {
    if (block.markerIds && block.markerIds.length === 0) add(block.id, 'Choose at least one marker.')
    if (block.type === 'FILTER' && block.signal.conditionOutputPinIds.length === 0) add(block.id, 'Connect a condition to its "if".')
    if (block.type === 'SELECT' && block.adds.length === 0) add(block.id, 'Add at least one row.')
    if (!fedIds.has(block.id)) add(block.id, 'Connect something into it.')
  }
  for (const signal of pipeline.topLevelSignals ?? []) {
    const conditions = signal.type === 'CONDITION_GROUP' ? signal.conditions : [signal]
    for (const message of new Set(conditions.map((c) => testProblem(c.predicate)).filter(Boolean))) add(nodeIdOf({ signal }), message)
  }
  return problems
}
