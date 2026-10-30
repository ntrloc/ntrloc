// Manhattan routing between boxes: every line is a run of horizontal and vertical segments, leaving
// and entering its boxes square to a side. Pure geometry -- rects in, point lists out.
//
// A line's layout is { from, to, values }:
// - from / to: where it attaches -- { side, at }, `at` being how far along that side (0 to 1, left to
//   right or top to bottom).
// - values: where each of its middle segments lies -- an x for a vertical one, a y for a horizontal
//   one, in order from the start. Segments alternate, so one number places each. A line between two
//   left/right sides (or two top/bottom ones) has an odd number of middle segments; between a
//   left/right side and a top/bottom one, an even number.
// Any of the three can be missing, and gets a default: facing sides of the two boxes, spread along
// the side where several lines meet it, and the fewest segments that leave and enter each box
// cleanly. Moving a box keeps its lines' layouts, so only their first and last segments stretch.

const STUB = 20
const PORT_SPACING = 16
const CORNER_INSET = 6
export const ALL_SIDES = ['left', 'right', 'top', 'bottom']
const OUTWARD = { left: { x: -1, y: 0 }, right: { x: 1, y: 0 }, top: { x: 0, y: -1 }, bottom: { x: 0, y: 1 } }

// The axis a line travels along as it leaves (or enters) a side.
const travelAxis = (side) => (side === 'left' || side === 'right' ? 'x' : 'y')
const other = (axis) => (axis === 'x' ? 'y' : 'x')
const center = (rect) => ({ x: rect.x + rect.width / 2, y: rect.y + rect.height / 2 })

function sideSpan(rect, side) {
  return travelAxis(side) === 'x' ? { start: rect.y, length: rect.height } : { start: rect.x, length: rect.width }
}

function portPoint(rect, { side, at }) {
  const { start, length } = sideSpan(rect, side)
  const along = start + length * at
  switch (side) {
    case 'left':
      return { x: rect.x, y: along }
    case 'right':
      return { x: rect.x + rect.width, y: along }
    case 'top':
      return { x: along, y: rect.y }
    default:
      return { x: along, y: rect.y + rect.height }
  }
}

// `at` for a point along a side, kept clear of the box's corners. A box with no size (a point) has
// just the one place.
function atFor(rect, side, coord) {
  const { start, length } = sideSpan(rect, side)
  if (length === 0) return 0.5
  const inset = Math.min(CORNER_INSET, length / 2)
  return (Math.min(start + length - inset, Math.max(start + inset, coord)) - start) / length
}

// The attachment on one of `sides` of a box nearest a point -- where a dragged line end lands.
export function nearestPort(rect, point, sides = ALL_SIDES) {
  let best = null
  for (const side of sides) {
    const at = atFor(rect, side, point[other(travelAxis(side))])
    const p = portPoint(rect, { side, at })
    const distance = Math.hypot(p.x - point.x, p.y - point.y)
    if (!best || distance < best.distance) best = { distance, port: { side, at } }
  }
  return best.port
}

// Default sides for a line between two boxes: the facing ones, across the wider gap between them.
function facingSides(from, to, sides) {
  const canHorizontal = sides.includes('left') && sides.includes('right')
  const canVertical = sides.includes('top') && sides.includes('bottom')
  const gapX = Math.max(to.x - (from.x + from.width), from.x - (to.x + to.width))
  const gapY = Math.max(to.y - (from.y + from.height), from.y - (to.y + to.height))
  const a = center(from)
  const b = center(to)
  if (canHorizontal && (!canVertical || gapX >= gapY)) return b.x >= a.x ? ['right', 'left'] : ['left', 'right']
  return b.y >= a.y ? ['bottom', 'top'] : ['top', 'bottom']
}

// Default attachments for lines with none saved: facing sides, spread along each side where several
// meet it, ordered by where their other ends are so they don't cross on the way out. A line keeps one
// place at both its ends when two boxes share several, so those run side by side. A line from a box
// to itself leaves the top and comes back into the right side.
function defaultPorts(edges, rects, layouts, sides) {
  const ports = new Map()
  const ends = []
  edges.forEach((edge, order) => {
    const saved = layouts.get(edge.key) ?? {}
    if (edge.fromId === edge.toId) {
      ports.set(`${edge.key}:from`, saved.from ?? { side: 'top', at: 0.8 })
      ports.set(`${edge.key}:to`, saved.to ?? { side: 'right', at: 0.25 })
      return
    }
    const [fromSide, toSide] = facingSides(rects.get(edge.fromId), rects.get(edge.toId), sides)
    for (const [role, nodeId, otherId, side] of [
      ['from', edge.fromId, edge.toId, fromSide],
      ['to', edge.toId, edge.fromId, toSide],
    ]) {
      if (saved[role]) ports.set(`${edge.key}:${role}`, saved[role])
      else ends.push({ portKey: `${edge.key}:${role}`, nodeId, otherId, side, order })
    }
  })

  const bySide = new Map()
  for (const end of ends) {
    const key = `${end.nodeId}:${end.side}`
    bySide.set(key, [...(bySide.get(key) ?? []), end])
  }
  for (const group of bySide.values()) {
    const { nodeId, side } = group[0]
    const rect = rects.get(nodeId)
    const along = (r) => center(r)[other(travelAxis(side))]
    group.sort((a, b) => along(rects.get(a.otherId)) - along(rects.get(b.otherId)) || a.order - b.order)
    const { length } = sideSpan(rect, side)
    const spacing = Math.min(PORT_SPACING, length / (group.length + 1))
    group.forEach((end, i) =>
      ports.set(end.portKey, { side, at: length === 0 ? 0.5 : 0.5 + ((i - (group.length - 1) / 2) * spacing) / length }),
    )
  }
  return ports
}

const valuesFit = (values, fromSide, toSide) => (values.length % 2 === 1) === (travelAxis(fromSide) === travelAxis(toSide))

// The middle segments of a line nobody has shaped: the fewest that leave p1 and enter p2 squarely,
// at least STUB out from each box.
function defaultValues(p1, fromSide, p2, toSide, fromRect, toRect) {
  const t = travelAxis(fromSide)
  const d1 = OUTWARD[fromSide][t]
  const a1 = { x: p1.x + OUTWARD[fromSide].x * STUB, y: p1.y + OUTWARD[fromSide].y * STUB }
  const a2 = { x: p2.x + OUTWARD[toSide].x * STUB, y: p2.y + OUTWARD[toSide].y * STUB }
  const leaves = (v) => (v - p1[t]) * d1 >= STUB

  if (travelAxis(toSide) !== t) {
    // An L, when the corner it turns at is clear of both boxes; otherwise out, across and in.
    const d2 = OUTWARD[toSide][other(t)]
    if (leaves(p2[t]) && (p1[other(t)] - p2[other(t)]) * d2 >= STUB) return []
    return [a1[t], a2[other(t)]]
  }

  const d2 = OUTWARD[toSide][t]
  if (d1 === d2) return [d1 > 0 ? Math.max(a1[t], a2[t]) : Math.min(a1[t], a2[t])]
  const mid = (p1[t] + p2[t]) / 2
  if (leaves(mid) && (mid - p2[t]) * d2 >= STUB) return [mid]
  // Facing away from each other: out, round the boxes (between them if there's room) and back in.
  const o = other(t)
  const fromEnd = fromRect[o] + (o === 'x' ? fromRect.width : fromRect.height)
  const toEnd = toRect[o] + (o === 'x' ? toRect.width : toRect.height)
  let lane = Math.max(fromEnd, toEnd) + STUB
  if (toRect[o] - fromEnd >= 2 * STUB) lane = (fromEnd + toRect[o]) / 2
  else if (fromRect[o] - toEnd >= 2 * STUB) lane = (toEnd + fromRect[o]) / 2
  return [a1[t], lane, a2[t]]
}

// The line's corners: from p1 along its first side's axis to the first value, then alternating,
// until it lines up with p2.
function cornerPoints(p1, fromSide, p2, values) {
  let axis = travelAxis(fromSide)
  let current = p1
  const points = [p1]
  for (const value of values) {
    current = { ...current, [axis]: value }
    points.push(current)
    axis = other(axis)
  }
  points.push({ ...current, [axis]: p2[axis] }, p2)
  return points
}

// Each segment, with the axis it moves along when dragged (sideways to itself). The first and last
// slide their ends along their boxes' sides; the ones between move freely.
function segmentsOf(points, fromSide) {
  const segments = []
  let axis = travelAxis(fromSide)
  for (let i = 0; i < points.length - 1; i++) {
    let kind = 'middle'
    if (i === 0) kind = 'from'
    else if (i === points.length - 2) kind = 'to'
    segments.push({ index: i, kind, axis: other(axis), a: points[i], b: points[i + 1] })
    axis = other(axis)
  }
  return segments
}

// edges: [{ key, fromId, toId }]; rects: Map<node id, rect>; layouts: Map<edge key, layout> (see the
// top). sides: which sides of a box lines may attach to.
// Returns Map<edge key, route>: { points, segments, label (the middle segment's midpoint, for a
// label), layout (in full, defaults filled in), fromRect, toRect, sides }.
export function routeEdges(edges, rects, layouts = new Map(), { sides = ALL_SIDES } = {}) {
  const ports = defaultPorts(edges, rects, layouts, sides)
  const routes = new Map()
  for (const edge of edges) {
    const fromRect = rects.get(edge.fromId)
    const toRect = rects.get(edge.toId)
    const from = ports.get(`${edge.key}:from`)
    const to = ports.get(`${edge.key}:to`)
    const p1 = portPoint(fromRect, from)
    const p2 = portPoint(toRect, to)
    const saved = layouts.get(edge.key)?.values
    const values = saved && valuesFit(saved, from.side, to.side) ? saved : defaultValues(p1, from.side, p2, to.side, fromRect, toRect)
    const points = cornerPoints(p1, from.side, p2, values)
    const segments = segmentsOf(points, from.side)
    const middle = segments[Math.floor(segments.length / 2)]
    routes.set(edge.key, {
      points,
      segments,
      label: { x: (middle.a.x + middle.b.x) / 2, y: (middle.a.y + middle.b.y) / 2, segment: middle },
      layout: { from, to, values },
      fromRect,
      toRect,
      sides,
    })
  }
  return routes
}

// A route's layout with one segment dragged to `coord` (along the segment's own axis).
export function moveSegment(route, segment, coord) {
  const { from, to, values } = route.layout
  if (segment.kind === 'from') return { ...route.layout, from: { side: from.side, at: atFor(route.fromRect, from.side, coord) } }
  if (segment.kind === 'to') return { ...route.layout, to: { side: to.side, at: atFor(route.toRect, to.side, coord) } }
  return { ...route.layout, values: values.map((v, i) => (i === segment.index - 1 ? coord : v)) }
}

// A route's layout with one end ('from' or 'to') dragged to `point`: attached wherever on its box is
// nearest. Moving to another side starts its segments afresh.
export function moveEnd(route, end, point) {
  const rect = end === 'from' ? route.fromRect : route.toRect
  const port = nearestPort(rect, point, route.sides)
  const sameSide = port.side === route.layout[end].side
  return { ...route.layout, [end]: port, values: sameSide ? route.layout.values : undefined }
}
