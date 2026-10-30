import { diagramBounds } from '../../../../controls/diagram/diagramBounds'
import { estimateTextWidth, wrapText } from '../../../../controls/diagram/diagramText'
import { routeEdges } from '../../../../controls/diagram/orthogonalRoutes'

export const STATE_FONT_SIZE = 14
export const STATE_LINE_HEIGHT = 17
// Each side of a state's name keeps room for a button (delete on the left, add a transition on the
// right), whether or not it's showing, so a box never changes size on being selected.
export const STATE_BUTTON_INSET = 15
const STATE_PADDING_X = 30
const STATE_MIN_WIDTH = 140
const STATE_MAX_WIDTH = 240
const STATE_MIN_HEIGHT = 44
const PSEUDO_SIZE = 28
const DEFAULT_GAP = 140

const isPseudo = (state) => state.kind === 'START' || state.kind === 'END'

// A state's box: sized to its name, wrapped onto more lines past STATE_MAX_WIDTH, with room for its
// buttons either side. Start and end are small circles.
export function stateBox(state) {
  if (isPseudo(state)) return { width: PSEUDO_SIZE, height: PSEUDO_SIZE, lines: [] }
  const width = Math.min(STATE_MAX_WIDTH, Math.max(STATE_MIN_WIDTH, estimateTextWidth(state.name, STATE_FONT_SIZE) + 2 * STATE_PADDING_X))
  const lines = wrapText(state.name, width - 2 * STATE_PADDING_X, STATE_FONT_SIZE)
  return { width, height: Math.max(STATE_MIN_HEIGHT, lines.length * STATE_LINE_HEIGHT + 22), lines }
}

// Where states go before anyone has placed them: one row, start first and end last, the rest in the
// order the transitions reach them from start.
function defaultOrder(states) {
  const byId = new Map(states.map((s) => [s.id, s]))
  const start = states.find((s) => s.kind === 'START')
  const seen = new Set()
  const order = []
  const queue = start ? [start] : []
  while (queue.length > 0) {
    const state = queue.shift()
    if (seen.has(state.id)) continue
    seen.add(state.id)
    if (state.kind !== 'END') order.push(state)
    for (const t of state.transitions) {
      const next = byId.get(t.toStateId)
      if (next && !seen.has(next.id)) queue.push(next)
    }
  }
  const rest = states.filter((s) => !seen.has(s.id) && s.kind !== 'END')
  const ends = states.filter((s) => s.kind === 'END')
  return [...order, ...rest, ...ends]
}

function defaultPositions(states, boxes) {
  const positions = new Map()
  let x = 0
  for (const state of defaultOrder(states)) {
    const { width, height } = boxes.get(state.id)
    positions.set(state.id, { x, y: -height / 2 })
    x += width + DEFAULT_GAP
  }
  return positions
}

// A machine's saved layout (see layoutToSave) as layoutStateMachine takes it.
export function savedLayout(machine) {
  return {
    positions: new Map(Object.entries(machine.layout?.positions ?? {})),
    edgeLayouts: new Map(Object.entries(machine.layout?.transitions ?? {})),
  }
}

// What's saved of a machine's drawing: where every state is, so the diagram stays put as states are
// added; and the layout of each transition someone has shaped -- the rest are routed afresh.
export function layoutToSave(machine, rects, edgeLayouts) {
  const transitionIds = new Set(machine.states.flatMap((s) => s.transitions.map((t) => t.id)))
  return {
    positions: Object.fromEntries(machine.states.map((s) => [s.id, { x: rects.get(s.id).x, y: rects.get(s.id).y }])),
    transitions: Object.fromEntries([...edgeLayouts].filter(([id]) => transitionIds.has(id))),
  }
}

// The whole drawing of a machine: each state's box (rects), each transition's route, and the bounds
// of it all. positions: Map<state id, { x, y }> (a box's top-left corner) for states that have been
// placed; edgeLayouts: Map<transition id, line layout> (see orthogonalRoutes) for shaped lines.
// endpoints: Map<transition id, { from?, to? }>, transitions drawn between other states than the
// machine says -- moved in the editor, or one end loose on one of extraRects (boxes drawn by no one,
// e.g. a point under the pointer).
export function layoutStateMachine(machine, positions = new Map(), edgeLayouts = new Map(), { endpoints = new Map(), extraRects = new Map() } = {}) {
  const boxes = new Map(machine.states.map((s) => [s.id, stateBox(s)]))
  const fallback = defaultPositions(machine.states, boxes)
  const rects = new Map(
    machine.states.map((s) => {
      const { x, y } = positions.get(s.id) ?? fallback.get(s.id)
      const { width, height } = boxes.get(s.id)
      return [s.id, { x, y, width, height }]
    }),
  )
  for (const [id, rect] of extraRects) rects.set(id, rect)
  const edges = machine.states
    .flatMap((s) =>
      s.transitions.map((t) => ({
        key: t.id,
        fromId: endpoints.get(t.id)?.from ?? s.id,
        toId: endpoints.get(t.id)?.to ?? t.toStateId,
        label: t.name,
      })),
    )
    .filter((e) => rects.has(e.fromId) && rects.has(e.toId))
  const routes = routeEdges(edges, rects, edgeLayouts)
  const bounds = diagramBounds(rects, routes, new Map(edges.map((e) => [e.key, e.label])))
  return { boxes, rects, edges, routes, bounds }
}
