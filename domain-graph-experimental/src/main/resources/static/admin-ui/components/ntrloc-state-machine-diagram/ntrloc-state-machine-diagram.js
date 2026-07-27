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
       of an invalid rule being dropped silently. ntrloc-state-machine-editor.js's own diagram pane
       uses this same centered-on-both-axes rule, not an override -- see that file's own comment. */
    display: flex;
    justify-content: center;
    justify-content: safe center;
    align-items: center;
    align-items: safe center;
    /* Drag-to-pan affordance (see _wireDragToPan) -- matches ntrloc-process-editor.js's own
       diagram-js-provided MoveCanvas cursor convention. */
    cursor: grab;
  }
  .state-machine-diagram-scroll:active {
    cursor: grabbing;
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
     a thin path is fiddly, especially for a short self-loop segment. */
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

// Vertical orientation is always used inside a narrow, fixed-width side panel
// (ntrloc-state-machine-editor.js's own layout) -- there's no comparable width constraint on
// horizontal orientation's usage (a full-width item-detail panel). So vertical gets its own,
// narrower node width (state names wrap across lines to compensate -- see wrapText/buildNode) and
// taller node height, spending some of the vertical space that orientation has to spare (found
// live: a vertical diagram was only using about half its available height) on more room for
// self-loop/rail attachment points along the node's left/right edges, which is exactly the edge
// NODE_HEIGHT governs the length of in vertical orientation (see axisHelpers/rankAxisNodeSize).
const NODE_WIDTH_HORIZONTAL = 160;
const NODE_HEIGHT_HORIZONTAL = 56;
const NODE_WIDTH_VERTICAL = 130;
const NODE_HEIGHT_VERTICAL = 84;
function nodeDimensions(vertical) {
  return vertical
    ? { width: NODE_WIDTH_VERTICAL, height: NODE_HEIGHT_VERTICAL }
    : { width: NODE_WIDTH_HORIZONTAL, height: NODE_HEIGHT_HORIZONTAL };
}
const PADDING = 24;
const SIBLING_GAP = 44;
const MIN_LABEL_CLEARANCE = 24;
// Fractions along a node's leading edge where a self-loop's two feet attach (0.3/0.7, matching
// this component's original self-loop convention). Rail stubs sharing that same edge (see
// allocateFractions) are kept outside this range so they never overlap the loop.
const SELF_LOOP_INNER_FRACTION = 0.3;
const SELF_LOOP_OUTER_FRACTION = 0.7;
// How far a self-loop bulges past its node's leading edge.
const SELF_LOOP_DEPTH = 28;
// Gap between the innermost reserved band (self-loop, if any) on a side and that side's first
// rail, and the gap between consecutive nested rail levels beyond that.
const RAIL_GAP = 16;
const RAIL_SPACING = 22;
// Small breathing room left between adjacent labels wherever this component packs multiple of them
// edge-to-edge (see packOffsets) -- both the direct-edge-pair case below and the rail-gap widening
// above use this same margin.
const LABEL_GAP = 4;
// A node label wraps (see wrapText/buildNode) instead of overflowing its box -- font size matches
// .sm-node-label's own 14px, padding is horizontal breathing room kept clear on each side, and line
// height spaces wrapped lines out enough not to visually touch at this font size.
const NODE_LABEL_FONT_SIZE = 14;
const NODE_LABEL_PADDING_X = 8;
const NODE_LABEL_LINE_HEIGHT = 17;
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
//
// Layout is a purpose-built router, not a general graph-layout library (this went through dagre,
// then ELK, before landing here -- see docs/ntrloc-diagram-rendering-principles.md for the full
// account of both). A general Sugiyama-family library optimizes for arbitrary DAGs and can't be
// told "nest connectors by rank distance, self-loop innermost, direct neighbor a plain line,
// everything else a rail split across both sides" -- the exact rule a hand-drawn reference design
// used and confirmed reads cleaner than what ELK produced for this specific family of diagrams
// (state machines: a roughly linear progression with self-loops, adjacent transitions, and the
// occasional back-edge or skip-ahead shortcut). See computeLayout for the full rule.
class NtrlocStateMachineDiagram extends HTMLElement {
  constructor() {
    super();
    // Set fresh on every construction; survives across this *instance's* own re-renders (render()
    // rebuilds innerHTML from scratch each time, but the element itself doesn't) -- that's not the
    // whole story, though: ntrloc-state-machine-editor.js's renderContent() rebuilds its entire
    // dialog body from an HTML string on every selection change, which tears down this custom
    // element and constructs a brand new *instance* to replace it (found live: zoom and scroll
    // position both silently reset back to their defaults on every click, since neither state
    // lives anywhere but this now-destroyed instance). See the viewState getter/setter below --
    // that's the actual fix, a caller-driven save/restore around whatever rebuilds this element,
    // not something this instance can protect itself from alone.
    this._zoom = 1;
    this._pendingScroll = null;
  }

  // Callers that recreate this element wholesale (see the constructor's own comment) can save this
  // before doing so and feed it back into the replacement instance to make that rebuild
  // visually seamless -- read after the old instance's last render, restored via the setter before
  // (zoom) and after (scroll, which needs the rendered DOM to exist first) the new instance's own
  // first render.
  get viewState() {
    const scrollEl = this.querySelector('.state-machine-diagram-scroll');
    return {
      zoom: this._zoom,
      scrollLeft: scrollEl ? scrollEl.scrollLeft : 0,
      scrollTop: scrollEl ? scrollEl.scrollTop : 0,
    };
  }

  set viewState(state) {
    if (!state) return;
    this._zoom = state.zoom;
    // Applied once render() has rebuilt .state-machine-diagram-scroll and can actually accept a
    // scroll position -- setting scrollLeft/Top here would just be discarded by that rebuild.
    this._pendingScroll = { left: state.scrollLeft, top: state.scrollTop };
  }

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
    const { positions, edges, selfLoopEdges, width, height, nodeSize } = computeLayout(states, orientation);
    const markerId = `sm-arrow-${++markerIdCounter}`;

    const svg = document.createElementNS(SVG_NS, 'svg');
    svg.setAttribute('class', 'state-machine-diagram-svg');
    // viewBox stays fixed at the layout's own natural size; width/height (the SVG's actual
    // rendered box) scale with the persisted zoom level instead -- see _wireWheelZoom's own
    // comment on why this, rather than a CSS transform, is what makes zooming interact correctly
    // with the scroll container's own scrollable area.
    svg.setAttribute('width', String(width * this._zoom));
    svg.setAttribute('height', String(height * this._zoom));
    svg.setAttribute('viewBox', `0 0 ${width} ${height}`);

    svg.appendChild(buildArrowheadDefs(markerId));

    const edgesLayer = document.createElementNS(SVG_NS, 'g');
    const nodesLayer = document.createElementNS(SVG_NS, 'g');

    // Edges drawn before nodes, in document order, so each opaque node box paints over the stub
    // of every line running into it -- lines only need to be geometrically correct up to the
    // node's boundary (already true by construction here), not carefully clipped in code.
    for (const geometry of [...selfLoopEdges, ...edges]) {
      const selected = geometry.transition.id != null && geometry.transition.id === this._selectedTransitionId;
      const edgeEl = buildPolylineEdge(geometry, markerId, selected);
      if (this._onSelectTransition) {
        edgeEl.classList.add('is-clickable');
        edgeEl.addEventListener('click', () => this._onSelectTransition(geometry.transition));
      }
      edgesLayer.appendChild(edgeEl);
    }

    for (const state of states) {
      const selected = state.id === this._selectedStateId;
      const node = buildNode(rectFor(positions.get(state.id), nodeSize.width, nodeSize.height), state, selected);
      if (this._onSelectState) {
        node.classList.add('is-clickable');
        node.addEventListener('click', () => this._onSelectState(state));
      }
      nodesLayer.appendChild(node);
    }

    svg.appendChild(edgesLayer);
    svg.appendChild(nodesLayer);

    this.innerHTML = '<div class="state-machine-diagram-scroll"></div>';
    const scrollEl = this.querySelector('.state-machine-diagram-scroll');
    scrollEl.appendChild(svg);

    // Same interaction model as ntrloc-process-editor.js's diagram-js canvas (MoveCanvas +
    // ZoomScroll modules) -- drag-to-pan and ctrl/cmd+wheel-to-zoom -- reimplemented directly here
    // since this component is a plain hand-built SVG, not a diagram-js Canvas those modules could
    // attach to. Plain wheel-scroll needs no code at all: .state-machine-diagram-scroll's own
    // native overflow:auto already handles it exactly like it always has.
    wireDragToPan(scrollEl);
    wireWheelZoom(this, scrollEl, svg, width, height);

    if (this._pendingScroll) {
      scrollEl.scrollLeft = this._pendingScroll.left;
      scrollEl.scrollTop = this._pendingScroll.top;
      this._pendingScroll = null;
    }
  }
}

// Mousedown-and-drag-to-scroll, matching diagram-js's own MoveCanvas module: a mousedown alone
// doesn't do anything (so a plain click still reaches the node/edge it landed on) -- only once the
// mouse has actually moved past DRAG_THRESHOLD does this engage, adjusting scrollLeft/scrollTop by
// the drag delta and installing a one-shot capture-phase click suppressor (installClickTrap) so
// the drag's own mouseup doesn't also fire a spurious click-to-select on whatever ended up under
// the cursor. Listens on `document`, not just the scroll element, for mousemove/mouseup so a drag
// that leaves the element (fast mouse movement) keeps tracking correctly, same as MoveCanvas does.
const DRAG_THRESHOLD = 5;
function wireDragToPan(scrollEl) {
  scrollEl.addEventListener('mousedown', (event) => {
    if (event.button !== 0) return; // left button only, same as MoveCanvas
    const startX = event.clientX;
    const startY = event.clientY;
    const startScrollLeft = scrollEl.scrollLeft;
    const startScrollTop = scrollEl.scrollTop;
    let dragging = false;

    function onMove(moveEvent) {
      const dx = moveEvent.clientX - startX;
      const dy = moveEvent.clientY - startY;
      if (!dragging && Math.hypot(dx, dy) > DRAG_THRESHOLD) {
        dragging = true;
        installClickTrap(scrollEl);
      }
      if (dragging) {
        scrollEl.scrollLeft = startScrollLeft - dx;
        scrollEl.scrollTop = startScrollTop - dy;
      }
    }
    function onUp() {
      document.removeEventListener('mousemove', onMove);
      document.removeEventListener('mouseup', onUp);
    }
    document.addEventListener('mousemove', onMove);
    document.addEventListener('mouseup', onUp);
  });
}

function installClickTrap(root) {
  const trap = (event) => {
    event.stopPropagation();
    root.removeEventListener('click', trap, true);
  };
  root.addEventListener('click', trap, true);
}

// Ctrl/Cmd+wheel-to-zoom, matching diagram-js's own ZoomScroll module's convention exactly: plain
// wheel keeps scrolling (native overflow:auto, untouched by this), ctrl/cmd+wheel (which is also
// how browsers report a trackpad pinch gesture) zooms instead, anchored so whatever point was
// under the cursor stays under it. Implemented by scaling the SVG's own width/height attributes
// (its rendered box size) rather than a CSS transform, with viewBox left fixed at the layout's
// natural size -- a transform only affects paint, not layout, so the scroll container's scrollable
// area wouldn't actually grow to match a zoomed-in diagram; changing width/height directly does,
// for free, since that's real layout size the browser already accounts for.
function wireWheelZoom(component, scrollEl, svg, baseWidth, baseHeight) {
  const MIN_ZOOM = 0.3;
  const MAX_ZOOM = 3;
  scrollEl.addEventListener('wheel', (event) => {
    if (!(event.ctrlKey || event.metaKey)) return; // plain wheel: let native scrolling handle it
    event.preventDefault();

    const rect = scrollEl.getBoundingClientRect();
    const offsetX = event.clientX - rect.left;
    const offsetY = event.clientY - rect.top;
    const contentX = scrollEl.scrollLeft + offsetX;
    const contentY = scrollEl.scrollTop + offsetY;

    const factor = Math.exp(-event.deltaY * 0.002);
    const oldZoom = component._zoom;
    const newZoom = Math.min(MAX_ZOOM, Math.max(MIN_ZOOM, oldZoom * factor));
    const ratio = newZoom / oldZoom;
    component._zoom = newZoom;

    svg.setAttribute('width', String(baseWidth * newZoom));
    svg.setAttribute('height', String(baseHeight * newZoom));

    scrollEl.scrollLeft = contentX * ratio - offsetX;
    scrollEl.scrollTop = contentY * ratio - offsetY;
  }, { passive: false });
}

function rectFor(position, width, height) {
  return { x: position.x, y: position.y, width, height };
}

// Classifies every transition via a DFS from the initial state(s): a self-loop (source === target,
// no ranking signal to extract -- see computeLayout's own comment), a forward edge (safe to use
// for longest-path rank assignment), or a back edge (its target is a DFS ancestor -- i.e. this
// transition closes a cycle, the same 2-cycle shape a reciprocal pair always is). Any node not
// reachable from an initial state gets its own DFS root afterward, so nothing is silently dropped.
function classifyEdges(states) {
  const byId = new Map(states.map((s) => [s.id, s]));
  const color = new Map(states.map((s) => [s.id, 'white']));
  const forwardEdges = [];
  const backEdges = [];
  const selfLoops = [];

  function dfs(id) {
    color.set(id, 'gray');
    for (const t of byId.get(id).transitions ?? []) {
      if (t.toStateId === id) {
        selfLoops.push({ stateId: id, transition: t });
        continue;
      }
      if (!byId.has(t.toStateId)) continue;
      const c = color.get(t.toStateId);
      if (c === 'white') {
        forwardEdges.push({ fromId: id, toId: t.toStateId, transition: t });
        dfs(t.toStateId);
      } else if (c === 'gray') {
        backEdges.push({ fromId: id, toId: t.toStateId, transition: t });
      } else {
        forwardEdges.push({ fromId: id, toId: t.toStateId, transition: t });
      }
    }
    color.set(id, 'black');
  }

  for (const s of states) if (s.isInitial && color.get(s.id) === 'white') dfs(s.id);
  for (const s of states) if (color.get(s.id) === 'white') dfs(s.id);
  return { forwardEdges, backEdges, selfLoops };
}

// Longest-path rank: rank(v) = one more than the longest chain of forward edges reaching it from
// an initial state. This (not shortest-path/BFS) is what makes a node with both a direct edge and
// a multi-hop path to it (Work in Progress -> Recently Finalized directly, and via Pending ISBN
// Finalization) land at the *later* of the two ranks -- matching the reference design, where that
// edge reads as a distance-2 skip, not a same-rank shortcut. forwardEdges is already acyclic (back
// edges were excluded by classifyEdges), so relaxing at most |states| times is guaranteed to reach
// a fixed point (standard Bellman-Ford-over-a-DAG argument).
function computeRanks(states, forwardEdges) {
  const rank = new Map(states.map((s) => [s.id, 0]));
  for (let i = 0; i < states.length; i++) {
    let changed = false;
    for (const e of forwardEdges) {
      const candidate = rank.get(e.fromId) + 1;
      if (candidate > rank.get(e.toId)) {
        rank.set(e.toId, candidate);
        changed = true;
      }
    }
    if (!changed) break;
  }
  return rank;
}

// Evenly spreads `count` attachment points (as fractions of a node's leading-edge length) across
// that edge. When `avoidCenter` is set (this node has a self-loop, which always occupies the
// 0.3-0.7 band of its own leading edge -- see SELF_LOOP_INNER/OUTER_FRACTION), points are placed
// outside that band instead, alternating sides moving further out as more are needed.
function allocateFractions(count, avoidCenter) {
  if (count === 0) return [];
  if (!avoidCenter) {
    if (count === 1) return [0.5];
    const step = 0.6 / (count - 1);
    return Array.from({ length: count }, (_, i) => 0.2 + i * step);
  }
  const right = [0.85, 0.95, 0.98];
  const left = [0.15, 0.05, 0.02];
  return Array.from({ length: count }, (_, i) => (i % 2 === 0 ? right[i / 2] : left[(i - 1) / 2]));
}

// Packs `count` items edge-to-edge (each pair separated by LABEL_GAP), sized by `widths`, and
// centers the whole stack on 0 -- used to offset a group of direct edges sharing the same node
// pair (a reciprocal pair, most commonly) so their *labels* don't overlap. Which dimension `widths`
// should measure depends on orientation: a direct edge in horizontal orientation is offset
// vertically, where every label has the same fixed 16px height regardless of text length; one in
// vertical orientation is offset horizontally, where it's the label's (very much
// text-length-dependent) *width* that has to clear its neighbor -- see the two call sites.
function packOffsets(widths) {
  if (widths.length === 1) return [0];
  const positions = [0];
  for (let i = 1; i < widths.length; i++) {
    positions.push(positions[i - 1] + widths[i - 1] / 2 + widths[i] / 2 + LABEL_GAP);
  }
  const center = positions[positions.length - 1] / 2;
  return positions.map((p) => p - center);
}

// The whole layout, start to finish: rank assignment, distance/kind classification for every
// transition, rail side + nesting assignment, node positions (with sibling-axis margins reserved
// for self-loops and nested rails), and finally concrete point geometry for every edge. Orientation
// is handled by treating "rank axis" (the direction flow advances -- x for horizontal, y for
// vertical) and "sibling axis" (perpendicular -- where same-rank siblings stack, and where
// self-loops/rails live, on the *leading* or *trailing* side of it) generically, converting to real
// x/y only at the end via toXY -- the same swap-the-two-axes approach this component has always
// used for orientation support, just with an extra axis-pair (leading/trailing sibling-axis sides)
// added for rail placement.
function computeLayout(states, orientation = 'horizontal') {
  const vertical = orientation === 'vertical';
  const { width: NODE_WIDTH, height: NODE_HEIGHT } = nodeDimensions(vertical);
  const byId = new Map(states.map((s) => [s.id, s]));
  const { forwardEdges, selfLoops } = classifyEdges(states);
  const rank = computeRanks(states, forwardEdges);

  const selfLoopsByNode = new Map();
  for (const sl of selfLoops) {
    if (!selfLoopsByNode.has(sl.stateId)) selfLoopsByNode.set(sl.stateId, []);
    selfLoopsByNode.get(sl.stateId).push(sl.transition);
  }

  const edgeDescriptors = [];
  for (const s of states) {
    for (const t of s.transitions ?? []) {
      if (t.toStateId === s.id || !byId.has(t.toStateId)) continue;
      const distance = Math.abs(rank.get(t.toStateId) - rank.get(s.id));
      edgeDescriptors.push({ fromId: s.id, toId: t.toStateId, transition: t, distance, kind: distance <= 1 ? 'direct' : 'rail' });
    }
  }

  // Rail side selection: the leading side already hosts every self-loop (a fixed rule, confirmed
  // across every orientation in the reference design), so whether rails may *also* use it depends
  // on how much physical room that edge actually has. A node's leading edge runs along the rank
  // axis, so its length is the rank-axis node size (NODE_WIDTH for horizontal, NODE_HEIGHT for
  // vertical -- see nodeDimensions). Confirmed against two reference examples of the same topology:
  // horizontal split its two skip-edges across both sides; vertical stacked both of its skip-edges
  // on the trailing side alone, leaving the (self-loop-bearing) leading side untouched. Kept as a
  // fixed per-orientation choice (`canSplitRails`) rather than a live comparison of the two actual
  // constants -- revisit if NODE_HEIGHT_VERTICAL is ever grown enough that vertical's leading edge
  // is no longer meaningfully tighter than horizontal's.
  //
  // That preference is only a *default*, though -- it has to yield to a harder constraint: two
  // rails sharing a side can only avoid crossing each other if their rank-spans are disjoint or one
  // properly contains the other. Two spans that merely overlap (neither containing the other --
  // e.g. [0,2] and [1,3]) are *interleaved*, and no choice of nesting depth for two rails on the
  // same side can avoid a crossing between them (found live: Work in Progress -> Recently Finalized
  // and Pending ISBN Finalization -> End did exactly this). Side assignment below is therefore
  // conflict-aware: an edge only uses its preferred side if nothing already placed there would
  // interleave with it; otherwise it spills to the other side even if that side is normally
  // disfavored for this orientation, since an actual crossing is a worse defect than a rail
  // attaching to a tighter edge.
  const canSplitRails = !vertical;
  const railEdges = edgeDescriptors.filter((e) => e.kind === 'rail').sort((a, b) => a.distance - b.distance);
  for (const e of railEdges) {
    e.span = [Math.min(rank.get(e.fromId), rank.get(e.toId)), Math.max(rank.get(e.fromId), rank.get(e.toId))];
  }
  function spansInterleave(a, b) {
    const overlap = a.span[0] < b.span[1] && b.span[0] < a.span[1];
    if (!overlap) return false;
    const aContainsB = a.span[0] <= b.span[0] && b.span[1] <= a.span[1];
    const bContainsA = b.span[0] <= a.span[0] && a.span[1] <= b.span[1];
    return !aContainsB && !bContainsA;
  }
  {
    const placed = { leading: [], trailing: [] };
    const conflicts = (edge, side) => placed[side].some((other) => spansInterleave(edge, other));
    for (const e of railEdges) {
      let side;
      if (canSplitRails) {
        const leadingOk = !conflicts(e, 'leading');
        const trailingOk = !conflicts(e, 'trailing');
        side = leadingOk && trailingOk ? (placed.leading.length <= placed.trailing.length ? 'leading' : 'trailing')
          : leadingOk ? 'leading'
          : trailingOk ? 'trailing'
          // Both sides conflict -- three or more mutually-interleaving edges, not seen in any
          // reference example. No side avoids a crossing here; fall back to balancing, same as the
          // no-conflict case, rather than leaving the edge unplaced.
          : (placed.leading.length <= placed.trailing.length ? 'leading' : 'trailing');
      } else {
        side = !conflicts(e, 'trailing') ? 'trailing' : !conflicts(e, 'leading') ? 'leading' : 'trailing';
      }
      e.side = side;
      placed[side].push(e);
    }
    for (const side of ['leading', 'trailing']) {
      railEdges.filter((e) => e.side === side).sort((a, b) => a.distance - b.distance)
        .forEach((e, i) => { e.nestIndex = i; });
    }
  }
  const leadingNestCount = railEdges.filter((e) => e.side === 'leading').length ? Math.max(...railEdges.filter((e) => e.side === 'leading').map((e) => e.nestIndex)) + 1 : 0;
  const trailingNestCount = railEdges.filter((e) => e.side === 'trailing').length ? Math.max(...railEdges.filter((e) => e.side === 'trailing').map((e) => e.nestIndex)) + 1 : 0;
  const hasAnySelfLoop = selfLoopsByNode.size > 0;

  // A self-loop's own label sits centered at the tip of its bulge (SELF_LOOP_DEPTH out from the
  // node edge) and reaches further out still by half its own extent -- height/2 (a fixed 8px)
  // along the sibling axis for horizontal orientation, but *width*/2 (text-length-dependent, same
  // reasoning as railGap and outermostLabelHalfWidth above) for vertical, since that's the axis a
  // self-loop's label sits along there. Reserved once as a single combined depth so
  // leadingReserve/the rail-level formula below don't need to separately track "the bulge" and
  // "the label past the bulge."
  const selfLoopOuterHalfExtent = hasAnySelfLoop
    ? (vertical
        ? Math.max(8, ...[...selfLoopsByNode.values()].flat().map((t) => (t.name ? estimateLabelWidth(t.name) / 2 : 0)))
        : 8)
    : 0;
  const selfLoopReserve = hasAnySelfLoop ? SELF_LOOP_DEPTH + selfLoopOuterHalfExtent : 0;

  // A rail's label reaches back toward the node column by half its width (see railGap's own
  // comment) -- but the *outermost* rail on a side reaches the same amount past the *other*
  // direction too, out toward the page's own outer edge, where only the flat PADDING constant
  // would otherwise be reserved (found live: the outermost label overshot past x=0 entirely,
  // since 24px of page margin doesn't come close to fitting half of a 97px-wide label). Only
  // matters for vertical orientation, same as railGap -- horizontal's labels are a fixed 16px
  // regardless of which nest level they're at.
  function outermostLabelHalfWidth(side) {
    const onSide = railEdges.filter((e) => e.side === side);
    if (onSide.length === 0) return 0;
    const outermost = onSide.reduce((a, b) => (a.nestIndex > b.nestIndex ? a : b));
    return outermost.transition.name ? estimateLabelWidth(outermost.transition.name) / 2 : 0;
  }
  const outerLeadingMargin = vertical ? Math.max(PADDING, outermostLabelHalfWidth('leading') + 8) : PADDING;
  const outerTrailingMargin = vertical ? Math.max(PADDING, outermostLabelHalfWidth('trailing') + 8) : PADDING;

  // Sibling grouping (same-rank nodes) and centering -- reused as-is from this component's
  // original layout for the multi-sibling case (branching), which the nesting-by-distance rule
  // above hasn't been extended to yet (see docs/ntrloc-diagram-rendering-principles.md); a single
  // state per rank (every example so far) reduces this to one row, unaffected by that gap.
  const byRank = new Map();
  for (const s of states) {
    const r = rank.get(s.id);
    if (!byRank.has(r)) byRank.set(r, []);
    byRank.get(r).push(s);
  }
  const siblingAxisNodeSize = vertical ? NODE_WIDTH : NODE_HEIGHT;
  const rankAxisNodeSize = vertical ? NODE_HEIGHT : NODE_WIDTH;
  function spanFor(count) {
    return count * siblingAxisNodeSize + (count - 1) * SIBLING_GAP;
  }
  const maxRankCount = Math.max(...[...byRank.values()].map((l) => l.length));
  const maxRankSpan = spanFor(maxRankCount);

  // A rail's label sits centered on the rail line itself, so half its width reaches back *toward*
  // the node column -- for horizontal orientation that's a fixed 16px (label height) regardless of
  // text length, already covered by RAIL_GAP, but for vertical orientation the rail line runs along
  // the sibling axis (x) and it's the label's *width* reaching back, which RAIL_GAP alone doesn't
  // account for (found live: "Return_to_Requestor"-length labels spilled back over the node column
  // by a wide margin). Widened here, once, using the widest rail label in the whole diagram --
  // simpler and safe, if occasionally more generous than the exact minimum a shorter label needed.
  const railGap = vertical
    ? Math.max(RAIL_GAP, ...railEdges.map((e) => (e.transition.name ? estimateLabelWidth(e.transition.name) / 2 + 8 : 0)))
    : RAIL_GAP;

  // Sibling-axis margins reserved ahead of (leading) and behind (trailing) the row(s) of nodes,
  // for self-loops and however many nested rails ended up on each side.
  const leadingReserve = selfLoopReserve + (leadingNestCount > 0 ? railGap + leadingNestCount * RAIL_SPACING : 0);
  const trailingReserve = trailingNestCount > 0 ? railGap + trailingNestCount * RAIL_SPACING : 0;

  // Rank gap widened for the longest *direct* edge's label, same reasoning (and only relevant for
  // horizontal orientation) as this component has always used -- a direct edge's label sits in the
  // gap between ranks, so that gap has to fit the widest one anywhere, not just the nearest.
  let rankGap = vertical ? 70 : 130;
  if (!vertical) {
    for (const e of edgeDescriptors) {
      if (e.kind === 'direct' && e.transition.name) {
        rankGap = Math.max(rankGap, estimateLabelWidth(e.transition.name) + 2 * MIN_LABEL_CLEARANCE);
      }
    }
  }

  const positions = new Map();
  for (const [r, list] of byRank.entries()) {
    const centerOffset = (maxRankSpan - spanFor(list.length)) / 2;
    list.forEach((s, idx) => {
      const rankCoord = PADDING + r * (rankAxisNodeSize + rankGap);
      const siblingCoord = outerLeadingMargin + leadingReserve + centerOffset + idx * (siblingAxisNodeSize + SIBLING_GAP);
      positions.set(s.id, toXY(rankCoord, siblingCoord, vertical));
    });
  }

  const maxRank = Math.max(...[...rank.values()]);
  const rankAxisTotal = PADDING + maxRank * (rankAxisNodeSize + rankGap) + rankAxisNodeSize + PADDING;
  const siblingAxisTotal = outerLeadingMargin + leadingReserve + maxRankSpan + trailingReserve + outerTrailingMargin;

  // Per-(node, side) attachment point allocation -- every rail edge needs one point on each of its
  // two endpoints, on whichever side (leading/trailing) that edge was assigned to; a self-loop
  // (leading side only) always reserves the 0.3-0.7 band on its own node first (see
  // allocateFractions), and rail stubs at that node+side share whatever's left.
  const slotRequests = new Map(); // key `${nodeId}|${side}` -> array of {edge, endpoint: 'from'|'to'}
  function addSlotRequest(nodeId, side, edge, endpoint) {
    const key = `${nodeId}|${side}`;
    if (!slotRequests.has(key)) slotRequests.set(key, []);
    slotRequests.get(key).push({ edge, endpoint });
  }
  for (const e of railEdges) {
    addSlotRequest(e.fromId, e.side, e, 'from');
    addSlotRequest(e.toId, e.side, e, 'to');
  }
  const fractionByRequest = new Map(); // edge -> { from: fraction, to: fraction }
  for (const [key, requests] of slotRequests.entries()) {
    const [nodeId, side] = key.split('|');
    const avoidCenter = side === 'leading' && selfLoopsByNode.has(nodeId);
    const fractions = allocateFractions(requests.length, avoidCenter);
    requests.forEach((req, i) => {
      if (!fractionByRequest.has(req.edge)) fractionByRequest.set(req.edge, {});
      fractionByRequest.get(req.edge)[req.endpoint] = fractions[i];
    });
  }

  // Direct (distance-1) edges grouped by their unordered node pair -- a reciprocal pair (both
  // directions present) is exactly two edges sharing a group, offset into parallel lines rather
  // than drawn on top of each other; a solitary direct edge is a group of one, centered.
  const directGroups = new Map();
  for (const e of edgeDescriptors) {
    if (e.kind !== 'direct') continue;
    const key = [e.fromId, e.toId].sort().join('|');
    if (!directGroups.has(key)) directGroups.set(key, []);
    directGroups.get(key).push(e);
  }

  const edgeAxisHelpers = axisHelpers(vertical);
  const globalLeadingEdge = outerLeadingMargin + leadingReserve;
  const globalTrailingEdge = outerLeadingMargin + leadingReserve + maxRankSpan;

  const edges = [];
  for (const [, group] of directGroups) {
    group.sort((a, b) => (a.transition.name ?? '').localeCompare(b.transition.name ?? ''));
    // See packOffsets' own comment: horizontal offsets vertically (fixed 16px label height),
    // vertical offsets horizontally (actual, text-length-dependent label width).
    const widths = group.map((e) => (vertical ? (e.transition.name ? estimateLabelWidth(e.transition.name) : 24) : 16));
    const offsets = packOffsets(widths);
    group.forEach((e, i) => {
      // The lower-rank endpoint always attaches via its trailing (forward-facing) edge and the
      // higher-rank endpoint via its leading (backward-facing) edge, regardless of which one this
      // particular transition's arrow points *from* -- a reciprocal pair's two transitions attach
      // to the exact same two points, just with arrowheads pointing opposite ways along the line.
      const loId = rank.get(e.fromId) <= rank.get(e.toId) ? e.fromId : e.toId;
      const loRect = rectFor(positions.get(loId), NODE_WIDTH, NODE_HEIGHT);
      const siblingCoord = edgeAxisHelpers.siblingCenter(loRect) + offsets[i];
      const fromRect = rectFor(positions.get(e.fromId), NODE_WIDTH, NODE_HEIGHT);
      const toRect = rectFor(positions.get(e.toId), NODE_WIDTH, NODE_HEIGHT);
      const start = toXY(edgeAxisHelpers.rankFacing(fromRect, e.fromId === loId ? 'trailing' : 'leading'), siblingCoord, vertical);
      const end = toXY(edgeAxisHelpers.rankFacing(toRect, e.toId === loId ? 'trailing' : 'leading'), siblingCoord, vertical);
      edges.push({
        transition: e.transition,
        points: [start, end],
        labelX: (start.x + end.x) / 2,
        labelY: (start.y + end.y) / 2,
      });
    });
  }

  for (const e of railEdges) {
    const fromRect = rectFor(positions.get(e.fromId), NODE_WIDTH, NODE_HEIGHT);
    const toRect = rectFor(positions.get(e.toId), NODE_WIDTH, NODE_HEIGHT);
    const fractions = fractionByRequest.get(e);
    const fromRank = edgeAxisHelpers.rankBack(fromRect) + fractions.from * rankAxisNodeSize;
    const toRank = edgeAxisHelpers.rankBack(toRect) + fractions.to * rankAxisNodeSize;
    const railLevel = e.side === 'leading'
      ? globalLeadingEdge - (selfLoopReserve + railGap + e.nestIndex * RAIL_SPACING)
      : globalTrailingEdge + railGap + e.nestIndex * RAIL_SPACING;
    const fromEdgeCoord = e.side === 'leading' ? edgeAxisHelpers.siblingLeading(fromRect) : edgeAxisHelpers.siblingTrailing(fromRect);
    const toEdgeCoord = e.side === 'leading' ? edgeAxisHelpers.siblingLeading(toRect) : edgeAxisHelpers.siblingTrailing(toRect);
    const p0 = toXY(fromRank, fromEdgeCoord, vertical);
    const p1 = toXY(fromRank, railLevel, vertical);
    const p2 = toXY(toRank, railLevel, vertical);
    const p3 = toXY(toRank, toEdgeCoord, vertical);
    edges.push({
      transition: e.transition,
      points: [p0, p1, p2, p3],
      labelX: (p1.x + p2.x) / 2,
      labelY: (p1.y + p2.y) / 2,
    });
  }

  const selfLoopEdges = [];
  for (const [nodeId, transitions] of selfLoopsByNode) {
    const rect = rectFor(positions.get(nodeId), NODE_WIDTH, NODE_HEIGHT);
    const edgeCoord = edgeAxisHelpers.siblingLeading(rect);
    const outerCoord = edgeCoord - SELF_LOOP_DEPTH;
    const footOuter = edgeAxisHelpers.rankBack(rect) + SELF_LOOP_OUTER_FRACTION * rankAxisNodeSize;
    const footInner = edgeAxisHelpers.rankBack(rect) + SELF_LOOP_INNER_FRACTION * rankAxisNodeSize;
    for (const transition of transitions) {
      const p0 = toXY(footOuter, edgeCoord, vertical);
      const p1 = toXY(footOuter, outerCoord, vertical);
      const p2 = toXY(footInner, outerCoord, vertical);
      const p3 = toXY(footInner, edgeCoord, vertical);
      selfLoopEdges.push({
        transition,
        points: [p0, p1, p2, p3],
        labelX: (p1.x + p2.x) / 2,
        labelY: (p1.y + p2.y) / 2,
      });
    }
  }

  return {
    positions,
    edges,
    selfLoopEdges,
    nodeSize: { width: NODE_WIDTH, height: NODE_HEIGHT },
    width: vertical ? siblingAxisTotal : rankAxisTotal,
    height: vertical ? rankAxisTotal : siblingAxisTotal,
  };
}

function toXY(rankCoord, siblingCoord, vertical) {
  return vertical ? { x: siblingCoord, y: rankCoord } : { x: rankCoord, y: siblingCoord };
}

// Orientation-agnostic accessors for "the edge of this node facing a given direction," so
// computeLayout's geometry code can be written once instead of duplicated per orientation. Leading
// = toward smaller coordinates (up for horizontal, left for vertical, matching this component's
// top-left-origin convention); trailing = the opposite, forward-facing side.
function axisHelpers(vertical) {
  return {
    rankBack: (rect) => (vertical ? rect.y : rect.x),
    rankFacing: (rect, side) => {
      if (vertical) return side === 'trailing' ? rect.y + rect.height : rect.y;
      return side === 'trailing' ? rect.x + rect.width : rect.x;
    },
    siblingLeading: (rect) => (vertical ? rect.x : rect.y),
    siblingTrailing: (rect) => (vertical ? rect.x + rect.width : rect.y + rect.height),
    siblingCenter: (rect) => (vertical ? rect.x + rect.width / 2 : rect.y + rect.height / 2),
  };
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

  // Wrapped across lines (rather than left to overflow the box, SVG text's default behavior) so a
  // long name can share the box with a narrower NODE_WIDTH -- see nodeDimensions' own comment on
  // why vertical orientation specifically wants this trade (narrower box, taller box, wrapped text).
  const lines = wrapText(state.name, rect.width - 2 * NODE_LABEL_PADDING_X, NODE_LABEL_FONT_SIZE);
  const label = document.createElementNS(SVG_NS, 'text');
  label.setAttribute('class', 'sm-node-label');
  const centerX = rect.x + rect.width / 2;
  label.setAttribute('x', String(centerX));
  label.setAttribute('y', String(rect.y + rect.height / 2 - ((lines.length - 1) * NODE_LABEL_LINE_HEIGHT) / 2));
  lines.forEach((line, i) => {
    const tspan = document.createElementNS(SVG_NS, 'tspan');
    tspan.setAttribute('x', String(centerX));
    if (i > 0) tspan.setAttribute('dy', String(NODE_LABEL_LINE_HEIGHT));
    tspan.textContent = line;
    label.appendChild(tspan);
  });
  g.appendChild(label);

  return g;
}

// Renders any edge (direct, rail, or self-loop) from its precomputed point list as a
// straight-segment polyline -- every point list computeLayout produces is already orthogonal
// (each consecutive pair shares an x or a y), so there's no curve-fitting or collision logic left
// to do here.
function buildPolylineEdge(geometry, markerId, selected = false) {
  const g = document.createElementNS(SVG_NS, 'g');
  g.setAttribute('class', 'sm-edge');

  const d = geometry.points.map((p, i) => `${i === 0 ? 'M' : 'L'} ${p.x} ${p.y}`).join(' ');

  const hitArea = document.createElementNS(SVG_NS, 'path');
  hitArea.setAttribute('class', 'sm-edge-hit');
  hitArea.setAttribute('d', d);
  g.appendChild(hitArea);

  const path = document.createElementNS(SVG_NS, 'path');
  path.setAttribute('class', `sm-edge-path${selected ? ' is-selected' : ''}`);
  path.setAttribute('d', d);
  path.setAttribute('marker-end', `url(#${markerId})`);
  g.appendChild(path);

  if (geometry.transition.name) {
    g.appendChild(buildEdgeLabel(geometry.transition.name, geometry.labelX, geometry.labelY, selected));
  }
  return g;
}

// Background rect sized off the string length rather than a real measured bbox -- this app has no
// canvas/DOM measurement helper in scope here, and a monospace-ish estimate is more than good
// enough for a short transition name against a plain background. Shared with computeLayout, which
// uses the same estimate to widen the rank gap around the widest direct-edge label. Per-char
// factor tuned for .sm-edge-label's 12px font-size -- revisit if that font-size ever changes.
function estimateLabelWidth(text) {
  return Math.max(24, text.length * 6.8 + 9);
}

// Wraps text into lines that each fit within maxWidth, using the same per-character width estimate
// as estimateLabelWidth (6.8px at a 12px font, scaled linearly for other sizes) -- splits on
// whitespace first, and falls back to splitting an individual overlong "word" on underscores, since
// this domain's own naming convention (Request_ISBN, Return_to_Requestor) means a token with no
// spaces in it can still be far too wide to fit on one line otherwise.
function wrapText(text, maxWidth, fontSize) {
  const charWidth = (fontSize * 6.8) / 12;
  function pack(tokens, glue) {
    const lines = [];
    let current = '';
    for (const token of tokens) {
      const candidate = current ? `${current}${glue}${token}` : token;
      if (current && candidate.length * charWidth > maxWidth) {
        lines.push(current);
        current = token;
      } else {
        current = candidate;
      }
    }
    if (current) lines.push(current);
    return lines;
  }
  return pack(text.split(' '), ' ')
    .flatMap((line) => (line.length * charWidth > maxWidth && line.includes('_') ? pack(line.split('_'), '_') : [line]));
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
