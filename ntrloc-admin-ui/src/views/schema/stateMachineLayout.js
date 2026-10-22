// Pure layout math for a state machine diagram -- states/transitions in, concrete node rects and
// edge point-lists out. No React, no DOM, no mutable state: kept separate from both the Model
// (schema shaping) and ViewModel (view-only state like zoom/pan) layers so it stays independently
// testable and so nothing above it needs to know *how* rank/rail placement works, only that it does.
//
// Ported from the legacy admin UI's ntrloc-state-machine-diagram.js, which itself went through
// dagre, then ELK, before landing on this purpose-built router -- see
// docs/ntrloc-diagram-rendering-principles.md for the full account of why neither general-purpose
// layered-graph library could be told a domain-specific rule like "nest connectors by rank
// distance, self-loop innermost, direct neighbor a plain line, everything else a rail split across
// both sides by capacity." That rule, and every principle listed in that doc, is what the code below
// implements -- read it first if a future change here seems to be fighting the layout instead of
// extending it.

// Vertical orientation is always used inside a narrow, fixed-width side panel (a future state
// machine editor's own layout), while horizontal gets a full-width tab panel -- there's no reason
// both should use the same node width. Vertical gets a narrower width (state names wrap across
// lines via wrapText/NODE_LABEL_* to compensate) and a taller height, spending vertical
// orientation's own slack space on more room for self-loop/rail attachment points along the node's
// left/right edges -- exactly the edge NODE_HEIGHT governs the length of in vertical orientation.
const NODE_WIDTH_HORIZONTAL = 160
const NODE_HEIGHT_HORIZONTAL = 56
const NODE_WIDTH_VERTICAL = 130
const NODE_HEIGHT_VERTICAL = 84
function nodeDimensions(vertical) {
  return vertical
    ? { width: NODE_WIDTH_VERTICAL, height: NODE_HEIGHT_VERTICAL }
    : { width: NODE_WIDTH_HORIZONTAL, height: NODE_HEIGHT_HORIZONTAL }
}
const PADDING = 24
const SIBLING_GAP = 44
const MIN_LABEL_CLEARANCE = 24
// Fractions along a node's leading edge where a self-loop's two feet attach (0.3/0.7, matching this
// component's original self-loop convention). Rail stubs sharing that same edge (see
// allocateFractions) are kept outside this range so they never overlap the loop.
const SELF_LOOP_INNER_FRACTION = 0.3
const SELF_LOOP_OUTER_FRACTION = 0.7
// How far a self-loop bulges past its node's leading edge.
const SELF_LOOP_DEPTH = 28
// Gap between the innermost reserved band (self-loop, if any) on a side and that side's first rail,
// and the gap between consecutive nested rail levels beyond that.
const RAIL_GAP = 16
const RAIL_SPACING = 22
// Small breathing room left between adjacent labels wherever this module packs multiple of them
// edge-to-edge (see packOffsets) -- both the direct-edge-pair case and the rail-gap widening use
// this same margin.
const LABEL_GAP = 4
// A node label wraps (see wrapText, used by the View) instead of overflowing its box -- padding is
// horizontal breathing room kept clear on each side, line height spaces wrapped lines out enough
// not to visually touch at this font size.
export const NODE_LABEL_FONT_SIZE = 14
export const NODE_LABEL_PADDING_X = 8
export const NODE_LABEL_LINE_HEIGHT = 17

// Classifies every transition via a DFS from the initial state(s): a self-loop (source === target,
// no ranking signal to extract), a forward edge (safe to use for longest-path rank assignment), or
// a back edge (its target is a DFS ancestor -- i.e. this transition closes a cycle, the same 2-cycle
// shape a reciprocal pair always is). Any node not reachable from an initial state gets its own DFS
// root afterward, so nothing is silently dropped.
function classifyEdges(states) {
  const byId = new Map(states.map((s) => [s.id, s]))
  const color = new Map(states.map((s) => [s.id, 'white']))
  const forwardEdges = []
  const backEdges = []
  const selfLoops = []

  function dfs(id) {
    color.set(id, 'gray')
    for (const t of byId.get(id).transitions ?? []) {
      if (t.toStateId === id) {
        selfLoops.push({ stateId: id, transition: t })
        continue
      }
      if (!byId.has(t.toStateId)) continue
      const c = color.get(t.toStateId)
      if (c === 'white') {
        forwardEdges.push({ fromId: id, toId: t.toStateId, transition: t })
        dfs(t.toStateId)
      } else if (c === 'gray') {
        backEdges.push({ fromId: id, toId: t.toStateId, transition: t })
      } else {
        forwardEdges.push({ fromId: id, toId: t.toStateId, transition: t })
      }
    }
    color.set(id, 'black')
  }

  for (const s of states) if (s.kind === 'START' && color.get(s.id) === 'white') dfs(s.id)
  for (const s of states) if (color.get(s.id) === 'white') dfs(s.id)
  return { forwardEdges, backEdges, selfLoops }
}

// Longest-path rank: rank(v) = one more than the longest chain of forward edges reaching it from an
// initial state. This (not shortest-path/BFS) is what makes a node with both a direct edge and a
// multi-hop path to it land at the *later* of the two ranks -- a direct-but-longer-distance edge
// reads as a skip, not a same-rank shortcut. forwardEdges is already acyclic (back edges were
// excluded by classifyEdges), so relaxing at most |states| times is guaranteed to reach a fixed
// point (standard Bellman-Ford-over-a-DAG argument).
function computeRanks(states, forwardEdges) {
  const rank = new Map(states.map((s) => [s.id, 0]))
  for (let i = 0; i < states.length; i++) {
    let changed = false
    for (const e of forwardEdges) {
      const candidate = rank.get(e.fromId) + 1
      if (candidate > rank.get(e.toId)) {
        rank.set(e.toId, candidate)
        changed = true
      }
    }
    if (!changed) break
  }

  // Pin the pseudostates: START is always the origin rank, END always sits one rank past every real
  // state -- even when END has no incoming transition yet, it belongs at the far end of the flow,
  // never floating alongside START at rank 0.
  const startId = states.find((s) => s.kind === 'START')?.id
  const endId = states.find((s) => s.kind === 'END')?.id
  if (startId != null) rank.set(startId, 0)
  if (endId != null) {
    let maxOther = 0
    for (const s of states) if (s.id !== endId) maxOther = Math.max(maxOther, rank.get(s.id))
    rank.set(endId, maxOther + 1)
  }
  return rank
}

// Evenly spreads `count` attachment points (as fractions of a node's leading-edge length) across
// that edge. When `avoidCenter` is set (this node has a self-loop, which always occupies the
// 0.3-0.7 band of its own leading edge), points are placed outside that band instead, alternating
// sides moving further out as more are needed.
function allocateFractions(count, avoidCenter) {
  if (count === 0) return []
  if (!avoidCenter) {
    if (count === 1) return [0.5]
    const step = 0.6 / (count - 1)
    return Array.from({ length: count }, (_, i) => 0.2 + i * step)
  }
  const right = [0.85, 0.95, 0.98]
  const left = [0.15, 0.05, 0.02]
  return Array.from({ length: count }, (_, i) => (i % 2 === 0 ? right[i / 2] : left[(i - 1) / 2]))
}

// Packs `count` items edge-to-edge (each pair separated by LABEL_GAP), sized by `widths`, and
// centers the whole stack on 0 -- used to offset a group of direct edges sharing the same node pair
// (a reciprocal pair, most commonly) so their *labels* don't overlap. Which dimension `widths`
// should measure depends on orientation: a direct edge in horizontal orientation is offset
// vertically, where every label has the same fixed 16px height regardless of text length; one in
// vertical orientation is offset horizontally, where it's the label's (very much
// text-length-dependent) *width* that has to clear its neighbor.
function packOffsets(widths) {
  if (widths.length === 1) return [0]
  const positions = [0]
  for (let i = 1; i < widths.length; i++) {
    positions.push(positions[i - 1] + widths[i - 1] / 2 + widths[i] / 2 + LABEL_GAP)
  }
  const center = positions[positions.length - 1] / 2
  return positions.map((p) => p - center)
}

function rectFor(position, width, height) {
  return { x: position.x, y: position.y, width, height }
}

function toXY(rankCoord, siblingCoord, vertical) {
  return vertical ? { x: siblingCoord, y: rankCoord } : { x: rankCoord, y: siblingCoord }
}

// Orientation-agnostic accessors for "the edge of this node facing a given direction," so
// computeLayout's geometry code can be written once instead of duplicated per orientation. Leading
// = toward smaller coordinates (up for horizontal, left for vertical, matching this module's
// top-left-origin convention); trailing = the opposite, forward-facing side.
function axisHelpers(vertical) {
  return {
    rankBack: (rect) => (vertical ? rect.y : rect.x),
    rankFacing: (rect, side) => {
      if (vertical) return side === 'trailing' ? rect.y + rect.height : rect.y
      return side === 'trailing' ? rect.x + rect.width : rect.x
    },
    siblingLeading: (rect) => (vertical ? rect.x : rect.y),
    siblingTrailing: (rect) => (vertical ? rect.x + rect.width : rect.y + rect.height),
    siblingCenter: (rect) => (vertical ? rect.x + rect.width / 2 : rect.y + rect.height / 2),
  }
}

// Background rect/label sizing is estimated off the string length rather than a real measured bbox
// -- there's no canvas/DOM measurement helper needed in scope here, and a monospace-ish estimate is
// more than good enough for a short transition name against a plain background. Per-char factor
// tuned for the edge label's 12px font-size -- revisit if that font-size ever changes.
export function estimateLabelWidth(text) {
  return Math.max(24, text.length * 6.8 + 9)
}

// Wraps text into lines that each fit within maxWidth, using the same per-character width estimate
// as estimateLabelWidth (6.8px at a 12px font, scaled linearly for other sizes) -- splits on
// whitespace first, and falls back to splitting an individual overlong "word" on underscores, since
// this domain's own naming convention (Request_ISBN, Return_to_Requestor) means a token with no
// spaces in it can still be far too wide to fit on one line otherwise.
export function wrapText(text, maxWidth, fontSize) {
  const charWidth = (fontSize * 6.8) / 12
  function pack(tokens, glue) {
    const lines = []
    let current = ''
    for (const token of tokens) {
      const candidate = current ? `${current}${glue}${token}` : token
      if (current && candidate.length * charWidth > maxWidth) {
        lines.push(current)
        current = token
      } else {
        current = candidate
      }
    }
    if (current) lines.push(current)
    return lines
  }
  return pack(text.split(' '), ' ').flatMap((line) =>
    line.length * charWidth > maxWidth && line.includes('_') ? pack(line.split('_'), '_') : [line],
  )
}

// The whole layout, start to finish: rank assignment, distance/kind classification for every
// transition, rail side + nesting assignment, node positions (with sibling-axis margins reserved
// for self-loops and nested rails), and finally concrete point geometry for every edge. Orientation
// is handled by treating "rank axis" (the direction flow advances -- x for horizontal, y for
// vertical) and "sibling axis" (perpendicular -- where same-rank siblings stack, and where
// self-loops/rails live, on the *leading* or *trailing* side of it) generically, converting to real
// x/y only at the end via toXY.
export function computeLayout(states, orientation = 'horizontal') {
  const vertical = orientation === 'vertical'
  const { width: NODE_WIDTH, height: NODE_HEIGHT } = nodeDimensions(vertical)
  const byId = new Map(states.map((s) => [s.id, s]))
  const { forwardEdges, selfLoops } = classifyEdges(states)
  const rank = computeRanks(states, forwardEdges)
  const selfLoopsByNode = groupSelfLoopsByNode(selfLoops)
  const edgeDescriptors = buildEdgeDescriptors(states, byId, rank)

  const { railEdges, leadingNestCount, trailingNestCount } = assignRailSides(edgeDescriptors, rank, vertical)
  const selfLoopReserve = computeSelfLoopReserve(selfLoopsByNode, vertical)
  const { outerLeadingMargin, outerTrailingMargin } = computeOuterMargins(railEdges, vertical)

  const { byRank, siblingAxisNodeSize, rankAxisNodeSize, spanFor, maxRankSpan } = groupStatesByRank(
    states,
    rank,
    vertical,
    NODE_WIDTH,
    NODE_HEIGHT,
  )

  const { railGap, leadingReserve, trailingReserve, rankGap } = computeAxisGaps(
    vertical,
    railEdges,
    selfLoopReserve,
    leadingNestCount,
    trailingNestCount,
    edgeDescriptors,
  )

  const { PSEUDO_SIZE, rankAxisSlot, rankCoordByRank } = computeRankCoordinates(
    vertical,
    NODE_WIDTH,
    NODE_HEIGHT,
    rankAxisNodeSize,
    byRank,
    edgeDescriptors,
    rank,
    rankGap,
  )

  const { rects, rankAxisSizeOf, rankAxisTotal, siblingAxisTotal } = computeNodeRects(
    states,
    byRank,
    rank,
    vertical,
    NODE_WIDTH,
    NODE_HEIGHT,
    maxRankSpan,
    spanFor,
    siblingAxisNodeSize,
    outerLeadingMargin,
    leadingReserve,
    rankCoordByRank,
    rankAxisSlot,
    PSEUDO_SIZE,
    trailingReserve,
    outerTrailingMargin,
  )

  const fractionByRequest = allocateRailFractions(railEdges, selfLoopsByNode)
  const directGroups = groupDirectEdges(edgeDescriptors)

  const edgeAxisHelpers = axisHelpers(vertical)
  const globalLeadingEdge = outerLeadingMargin + leadingReserve
  const globalTrailingEdge = outerLeadingMargin + leadingReserve + maxRankSpan

  const edges = [
    ...buildDirectEdges(directGroups, rank, rects, vertical, edgeAxisHelpers),
    ...buildRailEdgeSegments(
      railEdges,
      rects,
      fractionByRequest,
      rankAxisSizeOf,
      edgeAxisHelpers,
      vertical,
      globalLeadingEdge,
      globalTrailingEdge,
      selfLoopReserve,
      railGap,
    ),
  ]
  const selfLoopEdges = buildSelfLoopEdgeSegments(selfLoopsByNode, rects, edgeAxisHelpers, rankAxisNodeSize, vertical)

  return {
    rects,
    edges,
    selfLoopEdges,
    width: vertical ? siblingAxisTotal : rankAxisTotal,
    height: vertical ? rankAxisTotal : siblingAxisTotal,
  }
}

function groupSelfLoopsByNode(selfLoops) {
  const selfLoopsByNode = new Map()
  for (const sl of selfLoops) {
    if (!selfLoopsByNode.has(sl.stateId)) selfLoopsByNode.set(sl.stateId, [])
    selfLoopsByNode.get(sl.stateId).push(sl.transition)
  }
  return selfLoopsByNode
}

function buildEdgeDescriptors(states, byId, rank) {
  const edgeDescriptors = []
  for (const s of states) {
    for (const t of s.transitions ?? []) {
      if (t.toStateId === s.id || !byId.has(t.toStateId)) continue
      const distance = Math.abs(rank.get(t.toStateId) - rank.get(s.id))
      edgeDescriptors.push({ fromId: s.id, toId: t.toStateId, transition: t, distance, kind: distance <= 1 ? 'direct' : 'rail' })
    }
  }
  return edgeDescriptors
}

// Rail side selection: the leading side already hosts every self-loop (a fixed rule), so whether
// rails may *also* use it depends on how much physical room that edge actually has. A node's
// leading edge runs along the rank axis, so its length is the rank-axis node size (NODE_WIDTH for
// horizontal, NODE_HEIGHT for vertical). Kept as a fixed per-orientation choice rather than a live
// comparison of the two actual constants -- revisit if NODE_HEIGHT_VERTICAL is ever grown enough
// that vertical's leading edge is no longer meaningfully tighter than horizontal's.
//
// That preference is only a *default*, though -- it has to yield to a harder constraint: two rails
// sharing a side can only avoid crossing each other if their rank-spans are disjoint or one
// properly contains the other. Two spans that merely overlap (neither containing the other) are
// *interleaved*, and no choice of nesting depth for two rails on the same side can avoid a
// crossing between them. Side assignment below is therefore conflict-aware: an edge only uses its
// preferred side if nothing already placed there would interleave with it; otherwise it spills to
// the other side even if that side is normally disfavored for this orientation, since an actual
// crossing is a worse defect than a rail attaching to a tighter edge.
function assignRailSides(edgeDescriptors, rank, vertical) {
  const canSplitRails = !vertical
  const railEdges = edgeDescriptors.filter((e) => e.kind === 'rail').sort((a, b) => a.distance - b.distance)
  for (const e of railEdges) {
    e.span = [Math.min(rank.get(e.fromId), rank.get(e.toId)), Math.max(rank.get(e.fromId), rank.get(e.toId))]
  }
  function spansInterleave(a, b) {
    const overlap = a.span[0] < b.span[1] && b.span[0] < a.span[1]
    if (!overlap) return false
    const aContainsB = a.span[0] <= b.span[0] && b.span[1] <= a.span[1]
    const bContainsA = b.span[0] <= a.span[0] && a.span[1] <= b.span[1]
    return !aContainsB && !bContainsA
  }
  const placed = { leading: [], trailing: [] }
  const conflicts = (edge, side) => placed[side].some((other) => spansInterleave(edge, other))
  for (const e of railEdges) {
    let side
    if (canSplitRails) {
      const leadingOk = !conflicts(e, 'leading')
      const trailingOk = !conflicts(e, 'trailing')
      side = leadingOk && trailingOk
        ? placed.leading.length <= placed.trailing.length
          ? 'leading'
          : 'trailing'
        : leadingOk
          ? 'leading'
          : trailingOk
            ? 'trailing'
            // Both sides conflict -- three or more mutually-interleaving edges, not seen in any
            // reference example. No side avoids a crossing here; fall back to balancing, same as
            // the no-conflict case, rather than leaving the edge unplaced.
            : placed.leading.length <= placed.trailing.length
              ? 'leading'
              : 'trailing'
    } else {
      side = !conflicts(e, 'trailing') ? 'trailing' : !conflicts(e, 'leading') ? 'leading' : 'trailing'
    }
    e.side = side
    placed[side].push(e)
  }
  for (const side of ['leading', 'trailing']) {
    railEdges
      .filter((e) => e.side === side)
      .sort((a, b) => a.distance - b.distance)
      .forEach((e, i) => {
        e.nestIndex = i
      })
  }
  const leadingNestCount = railEdges.filter((e) => e.side === 'leading').length
    ? Math.max(...railEdges.filter((e) => e.side === 'leading').map((e) => e.nestIndex)) + 1
    : 0
  const trailingNestCount = railEdges.filter((e) => e.side === 'trailing').length
    ? Math.max(...railEdges.filter((e) => e.side === 'trailing').map((e) => e.nestIndex)) + 1
    : 0
  return { railEdges, leadingNestCount, trailingNestCount }
}

// A self-loop's own label sits centered at the tip of its bulge (SELF_LOOP_DEPTH out from the
// node edge) and reaches further out still by half its own extent -- height/2 (a fixed 8px) along
// the sibling axis for horizontal orientation, but *width*/2 (text-length-dependent, same
// reasoning as railGap and outermostLabelHalfWidth below) for vertical, since that's the axis a
// self-loop's label sits along there. Reserved once as a single combined depth so
// leadingReserve/the rail-level formula below don't need to separately track "the bulge" and "the
// label past the bulge."
function computeSelfLoopReserve(selfLoopsByNode, vertical) {
  const hasAnySelfLoop = selfLoopsByNode.size > 0
  const selfLoopOuterHalfExtent = hasAnySelfLoop
    ? vertical
      ? Math.max(8, ...[...selfLoopsByNode.values()].flat().map((t) => (t.name ? estimateLabelWidth(t.name) / 2 : 0)))
      : 8
    : 0
  return hasAnySelfLoop ? SELF_LOOP_DEPTH + selfLoopOuterHalfExtent : 0
}

// A rail's label reaches back toward the node column by half its width (see railGap's own
// comment) -- but the *outermost* rail on a side reaches the same amount past the *other*
// direction too, out toward the page's own outer edge, where only the flat PADDING constant would
// otherwise be reserved. Only matters for vertical orientation, same as railGap -- horizontal's
// labels are a fixed 16px regardless of which nest level they're at.
function computeOuterMargins(railEdges, vertical) {
  function outermostLabelHalfWidth(side) {
    const onSide = railEdges.filter((e) => e.side === side)
    if (onSide.length === 0) return 0
    const outermost = onSide.reduce((a, b) => (a.nestIndex > b.nestIndex ? a : b), onSide[0])
    return outermost.transition.name ? estimateLabelWidth(outermost.transition.name) / 2 : 0
  }
  const outerLeadingMargin = vertical ? Math.max(PADDING, outermostLabelHalfWidth('leading') + 8) : PADDING
  const outerTrailingMargin = vertical ? Math.max(PADDING, outermostLabelHalfWidth('trailing') + 8) : PADDING
  return { outerLeadingMargin, outerTrailingMargin }
}

// Sibling grouping (same-rank nodes) and centering -- a single state per rank (every reference
// example so far) reduces this to one row, unaffected by the multi-sibling case below; branching
// reuses this centered-stacking logic so it won't crash or look pathological, but the
// rail-nesting rule hasn't been designed against that case at all (see
// docs/ntrloc-diagram-rendering-principles.md's own "Open questions" section).
function groupStatesByRank(states, rank, vertical, NODE_WIDTH, NODE_HEIGHT) {
  const byRank = new Map()
  for (const s of states) {
    const r = rank.get(s.id)
    if (!byRank.has(r)) byRank.set(r, [])
    byRank.get(r).push(s)
  }
  const siblingAxisNodeSize = vertical ? NODE_WIDTH : NODE_HEIGHT
  const rankAxisNodeSize = vertical ? NODE_HEIGHT : NODE_WIDTH
  function spanFor(count) {
    return count * siblingAxisNodeSize + (count - 1) * SIBLING_GAP
  }
  const maxRankCount = Math.max(...[...byRank.values()].map((l) => l.length))
  const maxRankSpan = spanFor(maxRankCount)
  return { byRank, siblingAxisNodeSize, rankAxisNodeSize, spanFor, maxRankSpan }
}

function computeAxisGaps(vertical, railEdges, selfLoopReserve, leadingNestCount, trailingNestCount, edgeDescriptors) {
  // A rail's label sits centered on the rail line itself, so half its width reaches back *toward*
  // the node column -- for horizontal orientation that's a fixed 16px (label height) regardless of
  // text length, already covered by RAIL_GAP, but for vertical orientation the rail line runs along
  // the sibling axis (x) and it's the label's *width* reaching back, which RAIL_GAP alone doesn't
  // account for. Widened here, once, using the widest rail label in the whole diagram -- simpler
  // and safe, if occasionally more generous than the exact minimum a shorter label needed.
  const railGap = vertical
    ? Math.max(RAIL_GAP, ...railEdges.map((e) => (e.transition.name ? estimateLabelWidth(e.transition.name) / 2 + 8 : 0)))
    : RAIL_GAP

  // Sibling-axis margins reserved ahead of (leading) and behind (trailing) the row(s) of nodes, for
  // self-loops and however many nested rails ended up on each side.
  const leadingReserve = selfLoopReserve + (leadingNestCount > 0 ? railGap + leadingNestCount * RAIL_SPACING : 0)
  const trailingReserve = trailingNestCount > 0 ? railGap + trailingNestCount * RAIL_SPACING : 0

  // Rank gap widened for the longest *direct* edge's label, same reasoning (and only relevant for
  // horizontal orientation) this module has always used -- a direct edge's label sits in the gap
  // between ranks, so that gap has to fit the widest one anywhere, not just the nearest.
  let rankGap = vertical ? 70 : 130
  if (!vertical) {
    for (const e of edgeDescriptors) {
      if (e.kind === 'direct' && e.transition.name) {
        rankGap = Math.max(rankGap, estimateLabelWidth(e.transition.name) + 2 * MIN_LABEL_CLEARANCE)
      }
    }
  }
  return { railGap, leadingReserve, trailingReserve, rankGap }
}

// A START/END pseudostate draws as a small dot -- half a normal node's short side.
//
// Rank-axis placement: ranks sit at accumulated offsets rather than a single uniform stride, so a
// pseudostate-only rank (START at rank 0, END at the last rank) can occupy just the dot's width
// and sit a tight gap from its neighbour. Otherwise rankGap -- driven by the widest transition
// label anywhere in the diagram -- leaves a large void beside the tiny dot. Horizontal only;
// vertical already reads well and keeps its uniform stride and full-size slots.
function computeRankCoordinates(vertical, NODE_WIDTH, NODE_HEIGHT, rankAxisNodeSize, byRank, edgeDescriptors, rank, rankGap) {
  const PSEUDO_SIZE = Math.round(Math.min(NODE_WIDTH, NODE_HEIGHT) * 0.5)
  const tightenPseudo = !vertical
  const rankIsPseudo = (r) => (byRank.get(r) ?? []).every((s) => s.kind === 'START' || s.kind === 'END')
  const pseudoAdjacentLabelWidths = tightenPseudo
    ? edgeDescriptors
        .filter((e) => rankIsPseudo(rank.get(e.fromId)) || rankIsPseudo(rank.get(e.toId)))
        .map((e) => (e.transition.name ? estimateLabelWidth(e.transition.name) : 0))
    : []
  const pseudoRankGap = Math.max(SIBLING_GAP, ...pseudoAdjacentLabelWidths.map((w) => w + 2 * MIN_LABEL_CLEARANCE), 0)
  const rankAxisSlot = (r) => (tightenPseudo && rankIsPseudo(r) ? PSEUDO_SIZE : rankAxisNodeSize)
  const gapAfterRank = (r, next) => (tightenPseudo && (rankIsPseudo(r) || rankIsPseudo(next)) ? pseudoRankGap : rankGap)

  const sortedRanks = [...byRank.keys()].sort((a, b) => a - b)
  const rankCoordByRank = new Map()
  let cursor = PADDING
  sortedRanks.forEach((r, i) => {
    rankCoordByRank.set(r, cursor)
    if (i < sortedRanks.length - 1) cursor += rankAxisSlot(r) + gapAfterRank(r, sortedRanks[i + 1])
  })
  return { PSEUDO_SIZE, rankAxisSlot, rankCoordByRank }
}

// The concrete drawn/attached-to rect per node. A pseudostate dot is centered on the sibling axis
// of its slot; on the rank axis it hugs the slot's near edge when that rank was tightened to
// PSEUDO_SIZE (so the small slot == the dot), else it centers there too.
function computeNodeRects(
  states,
  byRank,
  rank,
  vertical,
  NODE_WIDTH,
  NODE_HEIGHT,
  maxRankSpan,
  spanFor,
  siblingAxisNodeSize,
  outerLeadingMargin,
  leadingReserve,
  rankCoordByRank,
  rankAxisSlot,
  PSEUDO_SIZE,
  trailingReserve,
  outerTrailingMargin,
) {
  const positions = new Map()
  for (const [r, list] of byRank.entries()) {
    const centerOffset = (maxRankSpan - spanFor(list.length)) / 2
    list.forEach((s, idx) => {
      const rankCoord = rankCoordByRank.get(r)
      const siblingCoord = outerLeadingMargin + leadingReserve + centerOffset + idx * (siblingAxisNodeSize + SIBLING_GAP)
      positions.set(s.id, toXY(rankCoord, siblingCoord, vertical))
    })
  }

  const rects = new Map()
  for (const s of states) {
    const pos = positions.get(s.id)
    if (s.kind === 'START' || s.kind === 'END') {
      const r = rank.get(s.id)
      const rankAxisPad = (rankAxisSlot(r) - PSEUDO_SIZE) / 2
      const siblingAxisPad = (siblingAxisNodeSize - PSEUDO_SIZE) / 2
      rects.set(s.id, {
        x: pos.x + (vertical ? siblingAxisPad : rankAxisPad),
        y: pos.y + (vertical ? rankAxisPad : siblingAxisPad),
        width: PSEUDO_SIZE,
        height: PSEUDO_SIZE,
      })
    } else {
      rects.set(s.id, rectFor(pos, NODE_WIDTH, NODE_HEIGHT))
    }
  }
  const rankAxisSizeOf = (id) => (vertical ? rects.get(id).height : rects.get(id).width)

  const maxRank = Math.max(...[...rank.values()])
  const rankAxisTotal = rankCoordByRank.get(maxRank) + rankAxisSlot(maxRank) + PADDING
  const siblingAxisTotal = outerLeadingMargin + leadingReserve + maxRankSpan + trailingReserve + outerTrailingMargin

  return { rects, rankAxisSizeOf, rankAxisTotal, siblingAxisTotal }
}

// Per-(node, side) attachment point allocation -- every rail edge needs one point on each of its
// two endpoints, on whichever side (leading/trailing) that edge was assigned to; a self-loop
// (leading side only) always reserves the 0.3-0.7 band on its own node first, and rail stubs at
// that node+side share whatever's left.
function allocateRailFractions(railEdges, selfLoopsByNode) {
  const slotRequests = new Map() // key `${nodeId}|${side}` -> array of {edge, endpoint: 'from'|'to'}
  function addSlotRequest(nodeId, side, edge, endpoint) {
    const key = `${nodeId}|${side}`
    if (!slotRequests.has(key)) slotRequests.set(key, [])
    slotRequests.get(key).push({ edge, endpoint })
  }
  for (const e of railEdges) {
    addSlotRequest(e.fromId, e.side, e, 'from')
    addSlotRequest(e.toId, e.side, e, 'to')
  }
  const fractionByRequest = new Map() // edge -> { from: fraction, to: fraction }
  for (const [key, requests] of slotRequests.entries()) {
    const [nodeId, side] = key.split('|')
    const avoidCenter = side === 'leading' && selfLoopsByNode.has(nodeId)
    const fractions = allocateFractions(requests.length, avoidCenter)
    requests.forEach((req, i) => {
      if (!fractionByRequest.has(req.edge)) fractionByRequest.set(req.edge, {})
      fractionByRequest.get(req.edge)[req.endpoint] = fractions[i]
    })
  }
  return fractionByRequest
}

// Direct (distance-1) edges grouped by their unordered node pair -- a reciprocal pair (both
// directions present) is exactly two edges sharing a group, offset into parallel lines rather
// than drawn on top of each other; a solitary direct edge is a group of one, centered.
function groupDirectEdges(edgeDescriptors) {
  const directGroups = new Map()
  for (const e of edgeDescriptors) {
    if (e.kind !== 'direct') continue
    const key = [e.fromId, e.toId].sort((a, b) => a.localeCompare(b)).join('|')
    if (!directGroups.has(key)) directGroups.set(key, [])
    directGroups.get(key).push(e)
  }
  return directGroups
}

function buildDirectEdges(directGroups, rank, rects, vertical, edgeAxisHelpers) {
  const edges = []
  for (const [, group] of directGroups) {
    group.sort((a, b) => (a.transition.name ?? '').localeCompare(b.transition.name ?? ''))
    // See packOffsets' own comment: horizontal offsets vertically (fixed 16px label height),
    // vertical offsets horizontally (actual, text-length-dependent label width).
    const widths = group.map((e) => (vertical ? (e.transition.name ? estimateLabelWidth(e.transition.name) : 24) : 16))
    const offsets = packOffsets(widths)
    group.forEach((e, i) => {
      // The lower-rank endpoint always attaches via its trailing (forward-facing) edge and the
      // higher-rank endpoint via its leading (backward-facing) edge, regardless of which one this
      // particular transition's arrow points *from* -- a reciprocal pair's two transitions attach
      // to the exact same two points, just with arrowheads pointing opposite ways along the line.
      const loId = rank.get(e.fromId) <= rank.get(e.toId) ? e.fromId : e.toId
      const loRect = rects.get(loId)
      const siblingCoord = edgeAxisHelpers.siblingCenter(loRect) + offsets[i]
      const fromRect = rects.get(e.fromId)
      const toRect = rects.get(e.toId)
      const start = toXY(edgeAxisHelpers.rankFacing(fromRect, e.fromId === loId ? 'trailing' : 'leading'), siblingCoord, vertical)
      const end = toXY(edgeAxisHelpers.rankFacing(toRect, e.toId === loId ? 'trailing' : 'leading'), siblingCoord, vertical)
      edges.push({
        transition: e.transition,
        points: [start, end],
        labelX: (start.x + end.x) / 2,
        labelY: (start.y + end.y) / 2,
      })
    })
  }
  return edges
}

function buildRailEdgeSegments(
  railEdges,
  rects,
  fractionByRequest,
  rankAxisSizeOf,
  edgeAxisHelpers,
  vertical,
  globalLeadingEdge,
  globalTrailingEdge,
  selfLoopReserve,
  railGap,
) {
  const edges = []
  for (const e of railEdges) {
    const fromRect = rects.get(e.fromId)
    const toRect = rects.get(e.toId)
    const fractions = fractionByRequest.get(e)
    const fromRank = edgeAxisHelpers.rankBack(fromRect) + fractions.from * rankAxisSizeOf(e.fromId)
    const toRank = edgeAxisHelpers.rankBack(toRect) + fractions.to * rankAxisSizeOf(e.toId)
    const railLevel =
      e.side === 'leading'
        ? globalLeadingEdge - (selfLoopReserve + railGap + e.nestIndex * RAIL_SPACING)
        : globalTrailingEdge + railGap + e.nestIndex * RAIL_SPACING
    const fromEdgeCoord = e.side === 'leading' ? edgeAxisHelpers.siblingLeading(fromRect) : edgeAxisHelpers.siblingTrailing(fromRect)
    const toEdgeCoord = e.side === 'leading' ? edgeAxisHelpers.siblingLeading(toRect) : edgeAxisHelpers.siblingTrailing(toRect)
    const p0 = toXY(fromRank, fromEdgeCoord, vertical)
    const p1 = toXY(fromRank, railLevel, vertical)
    const p2 = toXY(toRank, railLevel, vertical)
    const p3 = toXY(toRank, toEdgeCoord, vertical)
    edges.push({
      transition: e.transition,
      points: [p0, p1, p2, p3],
      labelX: (p1.x + p2.x) / 2,
      labelY: (p1.y + p2.y) / 2,
    })
  }
  return edges
}

function buildSelfLoopEdgeSegments(selfLoopsByNode, rects, edgeAxisHelpers, rankAxisNodeSize, vertical) {
  const selfLoopEdges = []
  for (const [nodeId, transitions] of selfLoopsByNode) {
    const rect = rects.get(nodeId)
    const edgeCoord = edgeAxisHelpers.siblingLeading(rect)
    const outerCoord = edgeCoord - SELF_LOOP_DEPTH
    const footOuter = edgeAxisHelpers.rankBack(rect) + SELF_LOOP_OUTER_FRACTION * rankAxisNodeSize
    const footInner = edgeAxisHelpers.rankBack(rect) + SELF_LOOP_INNER_FRACTION * rankAxisNodeSize
    for (const transition of transitions) {
      const p0 = toXY(footOuter, edgeCoord, vertical)
      const p1 = toXY(footOuter, outerCoord, vertical)
      const p2 = toXY(footInner, outerCoord, vertical)
      const p3 = toXY(footInner, edgeCoord, vertical)
      selfLoopEdges.push({
        transition,
        points: [p0, p1, p2, p3],
        labelX: (p1.x + p2.x) / 2,
        labelY: (p1.y + p2.y) / 2,
      })
    }
  }
  return selfLoopEdges
}
