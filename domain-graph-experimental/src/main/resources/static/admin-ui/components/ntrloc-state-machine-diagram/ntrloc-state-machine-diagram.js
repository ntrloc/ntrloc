injectStyles('ntrloc-state-machine-diagram-styles', `
  ntrloc-state-machine-diagram {
    display: block;
  }
  .state-machine-diagram-scroll {
    overflow: auto;
    border: 1px solid var(--border);
    border-radius: 6px;
    background: var(--bg);
    /* Centers the diagram (both axes) when it's smaller than the panel. Plain "center" was tried
       first and found live to be actively wrong once content overflows: flexbox centers an
       oversized child by splitting the overflow evenly on BOTH sides, but a scroll container's
       scrollLeft/Top can never go negative -- so the leading half of that overflow (confirmed via
       scrollLeft sitting at its own minimum, 0, with content still clipped) is centered into space
       that's permanently unreachable, not just "off by default." "safe center" is the standard
       fix: center when it fits, fall back to start-alignment the moment centering would push
       anything further than the scrollable range can reach. Declared twice, plain "center" first,
       so a browser that doesn't understand the "safe" keyword still gets a valid fallback instead
       of an invalid rule being dropped silently.
       ntrloc-state-machine-editor.js overrides align-items back to flex-start for its own
       vertical-orientation usage, which wants top alignment instead (see that file's own comment
       on why) -- same safe-centering reasoning applies there too. */
    display: flex;
    justify-content: center;
    justify-content: safe center;
    align-items: center;
    align-items: safe center;
  }
  .state-machine-diagram-svg {
    /* Without this, flex's default shrink-to-fit would compress the SVG below its own
       width/height attributes whenever the diagram is wider than the panel -- exactly backwards
       from what overflow:auto scrolling is there to handle. */
    flex-shrink: 0;
  }
  .state-machine-diagram-empty {
    color: var(--muted);
    font-style: italic;
    font-size: 13px;
    padding: 8px 0;
  }
  .sm-node-rect {
    fill: var(--panel-bg);
    stroke: var(--border);
    stroke-width: 1.5;
    rx: 8;
  }
  .sm-node-rect.is-initial {
    stroke: var(--accent);
    stroke-width: 2;
  }
  .sm-node-rect.is-selected {
    stroke: #f0883e;
    stroke-width: 2.5;
  }
  .sm-node.is-clickable {
    cursor: pointer;
  }
  .sm-edge-path.is-selected {
    stroke: #f0883e;
    stroke-width: 2.5;
  }
  .sm-edge.is-clickable {
    cursor: pointer;
  }
  /* Fattens the invisible click target well past the 1.5px visible line -- clicking precisely on
     a thin path is fiddly, especially for a short self-loop arc. */
  .sm-edge-hit {
    fill: none;
    stroke: transparent;
    stroke-width: 14;
  }
  .sm-node-label {
    fill: var(--text);
    font-size: 14px;
    text-anchor: middle;
    dominant-baseline: middle;
  }
  .sm-edge-path {
    fill: none;
    stroke: var(--muted);
    stroke-width: 1.5;
  }
  .sm-edge-label {
    fill: var(--muted);
    /* Floor is 12px per explicit legibility direction -- kept a point under the node label's 14px
       since it's secondary information, not because smaller is fine here too. */
    font-size: 12px;
    text-anchor: middle;
    dominant-baseline: middle;
  }
  .sm-edge-label.is-selected {
    fill: #f0883e;
  }
  .sm-edge-label-bg {
    fill: var(--bg);
  }
`);

const NODE_WIDTH = 160;
const NODE_HEIGHT = 56;
// Floor for the rank gap (horizontal orientation: column gap; vertical: row gap) when no label
// needs more -- see computeLayout's per-diagram widening below, which is what actually keeps long
// labels (e.g. "Request approval") clear of the boxes on either side. Only meaningful for
// horizontal orientation -- see the vertical-specific constant below for why.
const BASE_RANK_GAP = 130;
// Vertical orientation's edges run top-to-bottom, so a label's *width* (what the horizontal gap
// above has to accommodate) doesn't consume the gap between ranks the same way -- only its fixed
// ~16px height does. A small constant gap is enough; no per-label widening needed the way
// horizontal's BASE_RANK_GAP requires.
const BASE_RANK_GAP_VERTICAL = 70;
// Minimum visible arrow stub -- the gap between a label's background and the box edge it's next
// to -- kept on both sides of every edge label. Below this, a long label reads as filling the
// whole gap, and it's hard to tell there's a directional arrow there at all, let alone which way
// it points (found live: "Request approval" left almost no line visible on either side of itself).
const MIN_LABEL_CLEARANCE = 24;
// A self-loop (buildSelfLoop) bulges this far past its node's edge -- any node could have one, so
// the gap between same-rank siblings always reserves room for it rather than only when one's
// actually present. Was 32, found live to be too small: a self-loop bulges SELF_LOOP_HEIGHT (34)
// past its node, which is *more* than a 32px sibling gap, so a self-loop on any non-edge sibling
// would overlap its neighbor -- widened past 34 with a small margin.
const NODE_GAP = 44;
const PADDING = 24;
// A self-loop bulges this far past its node's leading edge (above it when horizontal, left of it
// when vertical -- see buildSelfLoop). Any node could have one, so the leading edge of every rank
// always reserves room for it rather than only when one's actually present (found live: without
// this, a self-loop on any node in a rank's first position -- e.g. an initial state, the single
// most common case for one -- drew its arc past the viewBox's own edge, clipped).
const SELF_LOOP_HEIGHT = 34;
const PADDING_LEADING = PADDING + SELF_LOOP_HEIGHT;
const SVG_NS = 'http://www.w3.org/2000/svg';

// Module-level, not per-instance -- guarantees a distinct arrowhead marker id per rendered
// diagram for the lifetime of the page, the same reasoning arrowhead-marker.js's own
// nextArrowheadMarkerId documents for the BPMN/DMN editors (SVG marker ids resolve document-wide,
// not scoped per <svg> root, so a fixed id would collide the moment two of these are ever open at
// once -- e.g. two item-detail tabs on state-machine-bearing item types). Not reusing that shared
// helper directly since it's an ES module and every other component in this admin-ui (this one
// included) is a plain global-scope script -- replicating the one-line counter locally is cheaper
// than restructuring the load order for the whole app.
let markerIdCounter = 0;

// Auto-laid-out visualization of an item type's state machine -- states as boxes, transitions as
// labeled arrows, shape only. Deliberately shows nothing else (no guard conditions, no
// entry/exit/transition process detail) -- per the explicit design direction this followed: "I
// don't think it's necessary to display anything other than the overall 'shape' of the states. If
// you want more information, you open this up in the editor." See
// docs/ntrloc-state-machine-summary.md Section 11.
//
// Read-only by default (ntrloc-item-detail.js's usage: just `{ states }`). Passing
// onSelectState/onSelectTransition turns on click-to-select -- selected*Id highlights the
// corresponding node/edge -- for ntrloc-state-machine-editor.js's diagram-left pane. Either way
// this component only renders/reports clicks, it never mutates schemaViewModel itself.
class NtrlocStateMachineDiagram extends HTMLElement {
  set data({ states, selectedStateId = null, selectedTransitionId = null, onSelectState = null, onSelectTransition = null, orientation = 'horizontal' }) {
    this._states = states || [];
    this._selectedStateId = selectedStateId;
    this._selectedTransitionId = selectedTransitionId;
    this._onSelectState = onSelectState;
    this._onSelectTransition = onSelectTransition;
    this._orientation = orientation;
    this.render();
  }

  get data() {
    return this._states;
  }

  connectedCallback() {
    this.render();
  }

  render() {
    const states = this._states || [];
    if (states.length === 0) {
      this.innerHTML = '<p class="state-machine-diagram-empty">No states defined.</p>';
      return;
    }

    const orientation = this._orientation || 'horizontal';
    const { positions, width, height } = computeLayout(states, orientation);
    const markerId = `sm-arrow-${++markerIdCounter}`;

    const svg = document.createElementNS(SVG_NS, 'svg');
    svg.setAttribute('class', 'state-machine-diagram-svg');
    svg.setAttribute('width', String(width));
    svg.setAttribute('height', String(height));
    svg.setAttribute('viewBox', `0 0 ${width} ${height}`);

    svg.appendChild(buildArrowheadDefs(markerId));

    const byId = new Map(states.map((s) => [s.id, s]));
    const edgesLayer = document.createElementNS(SVG_NS, 'g');
    const nodesLayer = document.createElementNS(SVG_NS, 'g');

    // Edges drawn before nodes, in document order, so each opaque node box paints over the stub
    // of every line running into it -- lines only need to be geometrically correct up to the
    // node's boundary (computed below), not carefully clipped in code.
    for (const state of states) {
      const sourceRect = rectFor(positions.get(state.id));
      for (const transition of state.transitions ?? []) {
        const target = byId.get(transition.toStateId);
        if (!target) continue; // shouldn't happen (FK-backed), but never let a bad row crash the diagram
        const selected = transition.id != null && transition.id === this._selectedTransitionId;
        const edge = transition.toStateId === state.id
          ? buildSelfLoop(sourceRect, transition.name, markerId, selected, orientation)
          : buildEdge(sourceRect, rectFor(positions.get(target.id)), transition.name, markerId, selected);
        if (this._onSelectTransition) {
          edge.classList.add('is-clickable');
          edge.addEventListener('click', () => this._onSelectTransition(transition));
        }
        edgesLayer.appendChild(edge);
      }
    }

    for (const state of states) {
      const selected = state.id === this._selectedStateId;
      const node = buildNode(rectFor(positions.get(state.id)), state, selected);
      if (this._onSelectState) {
        node.classList.add('is-clickable');
        node.addEventListener('click', () => this._onSelectState(state));
      }
      nodesLayer.appendChild(node);
    }

    svg.appendChild(edgesLayer);
    svg.appendChild(nodesLayer);

    this.innerHTML = '<div class="state-machine-diagram-scroll"></div>';
    this.querySelector('.state-machine-diagram-scroll').appendChild(svg);
  }
}

function rectFor(position) {
  return { x: position.x, y: position.y, width: NODE_WIDTH, height: NODE_HEIGHT };
}

// BFS rank (column when horizontal, row when vertical) from whichever state(s) are marked
// initial -- rank 0 is the initial front, rank N is N transitions away by shortest path. A
// self-loop never advances rank (it's excluded from the walk entirely). Anything unreachable from
// any initial state (a schema in a transient, not-yet-fully-wired state, most likely) is bucketed
// one rank past the furthest reachable one rather than dropped, so it's still visible instead of
// silently missing.
function computeLayout(states, orientation = 'horizontal') {
  const byId = new Map(states.map((s) => [s.id, s]));
  const rank = new Map();
  const queue = [];
  for (const s of states) {
    if (s.isInitial) {
      rank.set(s.id, 0);
      queue.push(s.id);
    }
  }
  let i = 0;
  while (i < queue.length) {
    const id = queue[i++];
    const r = rank.get(id);
    const state = byId.get(id);
    for (const t of state.transitions ?? []) {
      if (t.toStateId === id || !byId.has(t.toStateId) || rank.has(t.toStateId)) continue;
      rank.set(t.toStateId, r + 1);
      queue.push(t.toStateId);
    }
  }
  const maxRank = rank.size > 0 ? Math.max(...rank.values()) : -1;
  for (const s of states) {
    if (!rank.has(s.id)) rank.set(s.id, maxRank + 1);
  }

  const byRank = new Map();
  for (const s of states) {
    const r = rank.get(s.id);
    if (!byRank.has(r)) byRank.set(r, []);
    byRank.get(r).push(s);
  }

  // The rank gap is shared across the whole diagram (every rank lines up on the same grid), so it
  // has to satisfy the widest label anywhere, not just the one it's nearest to -- widen once here
  // rather than per-edge. Only worth doing for horizontal orientation -- see
  // BASE_RANK_GAP_VERTICAL's own comment for why a label's width doesn't matter the same way once
  // edges run top-to-bottom instead of left-to-right.
  let rankGap = orientation === 'vertical' ? BASE_RANK_GAP_VERTICAL : BASE_RANK_GAP;
  if (orientation !== 'vertical') {
    for (const s of states) {
      for (const t of s.transitions ?? []) {
        if (t.toStateId === s.id || !t.name) continue; // self-loops sit outside the rank gap, not in it
        rankGap = Math.max(rankGap, estimateLabelWidth(t.name) + 2 * MIN_LABEL_CLEARANCE);
      }
    }
  }

  // Horizontal: rank advances rightward (x), siblings stack downward (y) -- self-loops bulge up,
  // so PADDING_LEADING reserves headroom on the leading (top) edge. Vertical: rank advances
  // downward (y), siblings spread rightward (x) -- self-loops bulge left instead (see
  // buildSelfLoop), so the same PADDING_LEADING reserves room on the leading (left) edge here.
  // Structurally the same formula either way, just with the two axes swapped.
  const siblingSize = orientation === 'vertical' ? NODE_WIDTH : NODE_HEIGHT;
  // Per schema-editor.png (measured directly against it): a rank with fewer nodes than the
  // widest one is centered against that widest rank's own span, not top/left-anchored to a shared
  // baseline -- "Created"/"Pending approval" (one node each) sit at the vertical midpoint of
  // "Rejected"/"Approved" (two nodes), not aligned with "Rejected" alone. spanFor(n) is the same
  // "n boxes plus (n-1) gaps" formula used for the whole diagram's own bounding box below, just
  // evaluated per rank first so each one's offset can be measured against the widest.
  function spanFor(count) {
    return count * siblingSize + (count - 1) * NODE_GAP;
  }
  const maxRankCount = Math.max(...[...byRank.values()].map((list) => list.length));
  const maxRankSpan = spanFor(maxRankCount);

  const positions = new Map();
  for (const [r, list] of byRank.entries()) {
    // Widest rank gets centerOffset 0 (it defines the reference span); every shorter rank is
    // pushed forward by half the slack, centering it within that same span. This only ever adds
    // *more* clearance ahead of a rank's own idx=0 node than PADDING_LEADING alone would (the
    // widest rank -- the one that actually needs exactly PADDING_LEADING for its own self-loop
    // headroom -- always has centerOffset 0), so it can't reintroduce the clipping
    // PADDING_LEADING exists to prevent.
    const centerOffset = (maxRankSpan - spanFor(list.length)) / 2;
    list.forEach((s, idx) => {
      const rankSize = orientation === 'vertical' ? NODE_HEIGHT : NODE_WIDTH;
      const rankOffset = PADDING + r * (rankSize + rankGap);
      const siblingOffset = PADDING_LEADING + centerOffset + idx * (siblingSize + NODE_GAP);
      positions.set(s.id, orientation === 'vertical'
        ? { x: siblingOffset, y: rankOffset }
        : { x: rankOffset, y: siblingOffset });
    });
  }

  const allPositions = [...positions.values()];
  const width = Math.max(...allPositions.map((p) => p.x)) + NODE_WIDTH + PADDING;
  const height = Math.max(...allPositions.map((p) => p.y)) + NODE_HEIGHT + PADDING;
  return { positions, width, height };
}

function buildArrowheadDefs(markerId) {
  const defs = document.createElementNS(SVG_NS, 'defs');
  const marker = document.createElementNS(SVG_NS, 'marker');
  marker.setAttribute('id', markerId);
  marker.setAttribute('viewBox', '0 0 10 10');
  marker.setAttribute('refX', '9');
  marker.setAttribute('refY', '5');
  marker.setAttribute('markerWidth', '6');
  marker.setAttribute('markerHeight', '6');
  marker.setAttribute('orient', 'auto-start-reverse');
  const arrow = document.createElementNS(SVG_NS, 'path');
  arrow.setAttribute('d', 'M 0 0 L 10 5 L 0 10 z');
  arrow.setAttribute('fill', 'var(--muted)');
  marker.appendChild(arrow);
  defs.appendChild(marker);
  return defs;
}

function buildNode(rect, state, selected = false) {
  const g = document.createElementNS(SVG_NS, 'g');
  g.setAttribute('class', 'sm-node');

  const box = document.createElementNS(SVG_NS, 'rect');
  box.setAttribute('class', `sm-node-rect${state.isInitial ? ' is-initial' : ''}${selected ? ' is-selected' : ''}`);
  box.setAttribute('x', String(rect.x));
  box.setAttribute('y', String(rect.y));
  box.setAttribute('width', String(rect.width));
  box.setAttribute('height', String(rect.height));
  g.appendChild(box);

  const label = document.createElementNS(SVG_NS, 'text');
  label.setAttribute('class', 'sm-node-label');
  label.setAttribute('x', String(rect.x + rect.width / 2));
  label.setAttribute('y', String(rect.y + rect.height / 2));
  label.textContent = state.name;
  g.appendChild(label);

  return g;
}

// Clips a line from rect's center toward (towardX, towardY) to the point where it crosses rect's
// own boundary -- the standard "ray from box center to box edge" trick, applied at both ends of
// an edge so the drawn line (and, importantly, the arrowhead) sits right at each box's edge
// rather than centered on/hidden under it.
function pointOnRectBoundary(rect, towardX, towardY) {
  const cx = rect.x + rect.width / 2;
  const cy = rect.y + rect.height / 2;
  const dx = towardX - cx;
  const dy = towardY - cy;
  if (dx === 0 && dy === 0) return { x: cx, y: cy };
  const scaleX = dx !== 0 ? (rect.width / 2) / Math.abs(dx) : Infinity;
  const scaleY = dy !== 0 ? (rect.height / 2) / Math.abs(dy) : Infinity;
  const scale = Math.min(scaleX, scaleY);
  return { x: cx + dx * scale, y: cy + dy * scale };
}

function buildEdge(sourceRect, targetRect, label, markerId, selected = false) {
  const targetCenter = { x: targetRect.x + targetRect.width / 2, y: targetRect.y + targetRect.height / 2 };
  const sourceCenter = { x: sourceRect.x + sourceRect.width / 2, y: sourceRect.y + sourceRect.height / 2 };
  const start = pointOnRectBoundary(sourceRect, targetCenter.x, targetCenter.y);
  const end = pointOnRectBoundary(targetRect, sourceCenter.x, sourceCenter.y);
  const d = `M ${start.x} ${start.y} L ${end.x} ${end.y}`;

  const g = document.createElementNS(SVG_NS, 'g');
  g.setAttribute('class', 'sm-edge');

  const hitArea = document.createElementNS(SVG_NS, 'path');
  hitArea.setAttribute('class', 'sm-edge-hit');
  hitArea.setAttribute('d', d);
  g.appendChild(hitArea);

  const path = document.createElementNS(SVG_NS, 'path');
  path.setAttribute('class', `sm-edge-path${selected ? ' is-selected' : ''}`);
  path.setAttribute('d', d);
  path.setAttribute('marker-end', `url(#${markerId})`);
  g.appendChild(path);

  if (label) {
    g.appendChild(buildEdgeLabel(label, (start.x + end.x) / 2, (start.y + end.y) / 2, selected));
  }
  return g;
}

// A small loop past the box's leading edge for a self-transition (Section 11's own worked example
// -- GuardDemo's "Recheck" transition, Open -> Open -- is exactly this case): above the node when
// horizontal (out of the way of same-rank siblings stacked below it), left of the node when
// vertical (out of the way of same-rank siblings spread to its right) -- either way, bulging
// toward the rank's own leading edge, which PADDING_LEADING already reserves room against in
// computeLayout. Multiple self-loops on the same state will visually overlap; acceptable for a
// first pass, revisit if it comes up.
function buildSelfLoop(rect, label, markerId, selected = false, orientation = 'horizontal') {
  let d, labelX, labelY;
  if (orientation === 'vertical') {
    const startY = rect.y + rect.height * 0.7;
    const endY = rect.y + rect.height * 0.3;
    const leftX = rect.x;
    const loopX = rect.x - SELF_LOOP_HEIGHT;
    d = `M ${leftX} ${startY} C ${loopX} ${startY}, ${loopX} ${endY}, ${leftX} ${endY}`;
    labelX = loopX;
    labelY = rect.y + rect.height / 2;
  } else {
    const startX = rect.x + rect.width * 0.7;
    const endX = rect.x + rect.width * 0.3;
    const topY = rect.y;
    const loopY = rect.y - SELF_LOOP_HEIGHT;
    d = `M ${startX} ${topY} C ${startX} ${loopY}, ${endX} ${loopY}, ${endX} ${topY}`;
    labelX = rect.x + rect.width / 2;
    labelY = loopY;
  }

  const g = document.createElementNS(SVG_NS, 'g');
  g.setAttribute('class', 'sm-edge');

  const hitArea = document.createElementNS(SVG_NS, 'path');
  hitArea.setAttribute('class', 'sm-edge-hit');
  hitArea.setAttribute('d', d);
  g.appendChild(hitArea);

  const path = document.createElementNS(SVG_NS, 'path');
  path.setAttribute('class', `sm-edge-path${selected ? ' is-selected' : ''}`);
  path.setAttribute('d', d);
  path.setAttribute('marker-end', `url(#${markerId})`);
  g.appendChild(path);

  if (label) {
    g.appendChild(buildEdgeLabel(label, labelX, labelY, selected));
  }
  return g;
}

// Background rect sized off the string length rather than a real measured bbox -- this app has no
// canvas/DOM measurement helper in scope here, and a monospace-ish estimate is more than good
// enough for a short transition name against a plain background. Shared with computeLayout, which
// needs the same estimate up front to size the column gap around the widest label. Per-char factor
// tuned for .sm-edge-label's 12px font-size -- revisit if that font-size ever changes.
function estimateLabelWidth(text) {
  return Math.max(24, text.length * 6.8 + 9);
}

function buildEdgeLabel(text, x, y, selected = false) {
  const g = document.createElementNS(SVG_NS, 'g');
  const width = estimateLabelWidth(text);
  const bg = document.createElementNS(SVG_NS, 'rect');
  bg.setAttribute('class', 'sm-edge-label-bg');
  bg.setAttribute('x', String(x - width / 2));
  bg.setAttribute('y', String(y - 8));
  bg.setAttribute('width', String(width));
  bg.setAttribute('height', '16');
  g.appendChild(bg);

  const label = document.createElementNS(SVG_NS, 'text');
  label.setAttribute('class', `sm-edge-label${selected ? ' is-selected' : ''}`);
  label.setAttribute('x', String(x));
  label.setAttribute('y', String(y));
  label.textContent = text;
  g.appendChild(label);

  return g;
}

customElements.define('ntrloc-state-machine-diagram', NtrlocStateMachineDiagram);
