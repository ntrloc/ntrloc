// Pure layout math for a marker decision pipeline diagram -- a pipeline (+ its own saved
// PipelineLayout.positions, if any, + each carrier block's marker names) in, concrete node rects and
// edge point-lists out. No React, no DOM, no mutable state -- same separation StateMachineDiagramView
// keeps from stateMachineLayout, though this module is deliberately its own (not a shared import from
// stateMachineLayout.js) -- the two diagrams are different things (see the design discussion this was
// built from) and shouldn't be coupled just because a couple of small text helpers happen to overlap.
//
// Deliberately NOT an auto-layout engine the way stateMachineLayout.js is. Layout here is
// admin-authored and persisted (see org.ntrloc.graph.access.PipelineLayout's own comment: the editor
// owns correctness, the server never recomputes it) -- this module only ever uses a saved position
// verbatim when one exists. The rank-based placement below is strictly a "nothing saved yet"
// fallback for a pipeline with an empty layout, not a general layout algorithm competing with the
// editor; once the editor exists and an admin drags a node, that saved position is what renders from
// then on, not this.

const PSEUDO_DIAMETER = 32
const RANK_GAP = 90
const SIBLING_GAP = 28
// Margin around the whole diagram's own content -- the View fits/zooms the diagram to its display
// area via the SVG's viewBox (see MarkerPipelineView.jsx), so this is the only padding that ends up
// visible around the edges once that scaling happens.
const PADDING = 30
// The standard ~10px breathing room this diagram gives every port and every piece of block content
// -- one number, reused everywhere something needs padding, rather than each spot inventing its own.
const STANDARD_PADDING = 10
const PIN_DIAMETER = 14
// A carrier block's single-port side strip width: the pin itself, plus STANDARD_PADDING on each
// side -- exported so the View draws its pins at exactly the same offset this module uses to attach
// edges, rather than two places needing to agree by coincidence. See MarkerPipelineView.jsx's own
// comment on when a port gets a side strip at all.
export const STRIP_WIDTH = PIN_DIAMETER + 2 * STANDARD_PADDING

// Vertical geometry of a carrier block, shared between this module's height calculation and the
// View's rendering -- same reasoning as STRIP_WIDTH above, one set of numbers instead of two places
// agreeing by coincidence. A block is two horizontal bands: a full-width header (the "<add>"-style
// title, its own row, divided from the rest by a full-width rule) and a body band below it (where
// the port side-strips/pins and marker chips live -- see chipLayout and CarrierBlockNode). Pins
// attach to the body band's own vertical center, not the whole block's, since the header isn't part
// of the carrier-flow path.
export const TITLE_AREA_HEIGHT = 24
export const CONTENT_TOP_GAP = 6
export const CHIP_LINE_HEIGHT = 14
export const CHIP_PADDING_Y = 8
export const CHIP_PADDING_X = STANDARD_PADDING
export const CHIP_GAP = 6
export const BOTTOM_PADDING = STANDARD_PADDING
// Breathing room between a marker chip's own edge and the body's strip dividers on either side --
// applied in both nodeDimensions (reserving the space) and chipLayout (actually inset by it), so a
// chip never renders flush against a divider.
export const BODY_SIDE_MARGIN = STANDARD_PADDING
// A block never shrinks below this even with a short marker name or a short title like "Filter" --
// keeps the shape legibly a "block" rather than collapsing to a sliver.
const MIN_BODY_WIDTH = 90

// No wrapping -- a marker chip is always exactly one line, sized to its own name; the block grows
// to fit whichever marker name is longest, rather than the other way around (see the design
// discussion this was built from).
export function estimateLabelWidth(text) {
  return Math.max(16, text.length * 6.2)
}

function isPseudo(node) {
  return node.type === 'START' || node.type === 'END'
}

// The body band's own vertical center -- everything below the header rule, where ports/pins and
// marker chips live. Exported so the View's pins and this module's edge attachment agree on exactly
// the same y, rather than two formulas that happen to match.
export function bodyMidY(rect) {
  return rect.y + TITLE_AREA_HEIGHT + (rect.height - TITLE_AREA_HEIGHT) / 2
}

// markerNames: this block's own marker names, already resolved from ids (see
// MarkerPipelineViewModel's blockLabel). Body width is driven by the widest single line among the
// title and every marker chip -- there's never more than one line to measure per chip now.
function bodyWidth(label) {
  const widest = Math.max(estimateLabelWidth(label.title), ...label.markerNames.map((n) => estimateLabelWidth(n) + 2 * CHIP_PADDING_X), 0)
  return Math.max(widest + 2 * BODY_SIDE_MARGIN, MIN_BODY_WIDTH)
}

function nodeDimensions(node, blocks) {
  if (isPseudo(node)) return { width: PSEUDO_DIAMETER, height: PSEUDO_DIAMETER }
  const label = blocks.get(node.id)
  const markerNames = label?.markerNames ?? []
  const chipHeight = CHIP_LINE_HEIGHT + CHIP_PADDING_Y
  const chipsHeight = markerNames.length === 0 ? 0 : markerNames.length * chipHeight + CHIP_GAP * (markerNames.length - 1)
  const height = TITLE_AREA_HEIGHT + CONTENT_TOP_GAP + chipsHeight + BOTTOM_PADDING
  const width = 2 * STRIP_WIDTH + bodyWidth(label ?? { title: '', markerNames: [] })
  return { width, height: Math.max(height, PSEUDO_DIAMETER) }
}

function allNodes(pipeline) {
  return [pipeline.start, ...pipeline.carrierBlocks, pipeline.end]
}

// Longest-path rank from start, via carrier Routes only (never a signal gate -- gates are pulled,
// not pushed, so they carry no carrier-flow edge; see Route's own comment). A node unreachable from
// start (shouldn't happen in a valid pipeline, but drawn rather than dropped if it ever does) gets
// rank 0, same column as start.
function rankByLongestPath(pipeline) {
  const outEdges = new Map()
  for (const route of pipeline.routes) {
    outEdges.set(route.outputPinId, [...(outEdges.get(route.outputPinId) ?? []), ...route.inputPinIds])
  }
  const rank = new Map([[pipeline.start.id, 0]])
  const queue = [pipeline.start.id]
  while (queue.length > 0) {
    const id = queue.shift()
    const r = rank.get(id)
    for (const nextId of outEdges.get(id) ?? []) {
      if (!rank.has(nextId) || rank.get(nextId) < r + 1) {
        rank.set(nextId, r + 1)
        queue.push(nextId)
      }
    }
  }
  return rank
}

// positions: Map<UUID, {x, y}> decoded from the pipeline's own PipelineLayout.positions (empty map
// if nothing saved yet, never null). blocks: Map<UUID, {title, markerNames}> from
// MarkerPipelineViewModel -- see nodeDimensions' own comment on why sizing needs this.
export function computeLayout(pipeline, positions, blocks) {
  const nodes = allNodes(pipeline)
  const rank = rankByLongestPath(pipeline)

  const byRank = new Map()
  for (const node of nodes) {
    const r = rank.get(node.id) ?? 0
    byRank.set(r, [...(byRank.get(r) ?? []), node])
  }
  const sortedRanks = [...byRank.entries()].sort((a, b) => a[0] - b[0])

  // Column X per rank is cumulative off the widest node in every prior rank -- fixed-width columns
  // would either waste space or clip a wide block, now that width is content-driven instead of a
  // constant.
  const dimsByNodeId = new Map(nodes.map((n) => [n.id, nodeDimensions(n, blocks)]))
  const rankX = new Map([[sortedRanks[0]?.[0] ?? 0, PADDING]])
  for (let i = 1; i < sortedRanks.length; i++) {
    const [prevRank, prevNodes] = sortedRanks[i - 1]
    const [r] = sortedRanks[i]
    const prevMaxWidth = Math.max(...prevNodes.map((n) => dimsByNodeId.get(n.id).width))
    rankX.set(r, rankX.get(prevRank) + prevMaxWidth + RANK_GAP)
  }

  const rects = new Map()
  const nodesById = new Map(nodes.map((n) => [n.id, n]))
  let maxX = 0
  let maxY = 0
  for (const [r, rankNodes] of sortedRanks) {
    let fallbackY = PADDING
    for (const node of rankNodes) {
      const { width, height } = dimsByNodeId.get(node.id)
      const saved = positions.get(node.id)
      const x = saved ? saved.x : rankX.get(r)
      const y = saved ? saved.y : fallbackY
      rects.set(node.id, { x, y, width, height })
      fallbackY = y + height + SIBLING_GAP
      maxX = Math.max(maxX, x + width)
      maxY = Math.max(maxY, y + height)
    }
  }

  // A pseudostate's attach point is its own center (a circle has no header/body split). A carrier
  // block's is its output/input pin center: STRIP_WIDTH/2 inset from that side's edge, vertically
  // centered in the body band below the header (see bodyMidY), exactly where CarrierBlockNode draws
  // the pin itself.
  function outputPoint(rect, node) {
    const y = isPseudo(node) ? rect.y + rect.height / 2 : bodyMidY(rect)
    return isPseudo(node) ? { x: rect.x + rect.width / 2, y } : { x: rect.x + rect.width - STRIP_WIDTH / 2, y }
  }
  function inputPoint(rect, node) {
    const y = isPseudo(node) ? rect.y + rect.height / 2 : bodyMidY(rect)
    return isPseudo(node) ? { x: rect.x + rect.width / 2, y } : { x: rect.x + STRIP_WIDTH / 2, y }
  }

  const edges = []
  for (const route of pipeline.routes) {
    const fromRect = rects.get(route.outputPinId)
    const fromNode = nodesById.get(route.outputPinId)
    if (!fromRect) continue
    for (const targetId of route.inputPinIds) {
      const toRect = rects.get(targetId)
      const toNode = nodesById.get(targetId)
      if (!toRect) continue
      edges.push({
        key: `${route.outputPinId}->${targetId}`,
        from: outputPoint(fromRect, fromNode),
        to: inputPoint(toRect, toNode),
      })
    }
  }

  return {
    rects,
    edges,
    width: maxX + PADDING,
    height: maxY + PADDING,
  }
}

// Body-relative geometry the View needs to place the title and each stacked chip inside a carrier
// block's rect -- kept here so the pixel math backing "does this fit" (nodeDimensions, above) and
// "where exactly do I draw it" (the View) can't drift apart. markerNames: this block's own marker
// names (plain strings, already resolved from ids).
export function chipLayout(rect, markerNames) {
  const bodyX = rect.x + STRIP_WIDTH
  const bodyWidthPx = rect.width - 2 * STRIP_WIDTH
  // A chip is inset from the body's own strip dividers by BODY_SIDE_MARGIN on each side -- the
  // space nodeDimensions reserved for this, actually applied here rather than left as unused slack.
  const chipX = bodyX + BODY_SIDE_MARGIN
  const chipWidth = bodyWidthPx - 2 * BODY_SIDE_MARGIN
  const headerBottom = rect.y + TITLE_AREA_HEIGHT
  const titleY = rect.y + TITLE_AREA_HEIGHT / 2 + 4
  let y = headerBottom + CONTENT_TOP_GAP
  const chipHeight = CHIP_LINE_HEIGHT + CHIP_PADDING_Y
  const chips = markerNames.map((name) => {
    const chip = { x: chipX, y, width: chipWidth, height: chipHeight, name }
    y += chipHeight + CHIP_GAP
    return chip
  })
  return { centerX: bodyX + bodyWidthPx / 2, titleY, headerBottom, chips }
}
