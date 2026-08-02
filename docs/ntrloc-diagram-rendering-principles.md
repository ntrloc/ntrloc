# ntrloc Diagram Rendering Principles

A running list of principles for auto-laid-out node/edge diagrams (state machine diagrams today;
potentially other auto-laid-out graphs later). Started while debugging
`ntrloc-state-machine-diagram.js` against a real branchy/looping state machine that a hand-rolled
rank+bow layout kept getting subtly wrong. Add to this as new cases come up -- it's meant to grow,
not to be a finished spec.

## Current status

`ntrloc-state-machine-diagram.js` uses a **purpose-built router**, not a general graph-layout
library -- this went through dagre, then ELK, before landing here (see "Why not a general Sugiyama
library" below for why neither stuck). The rule set comes directly from a hand-drawn reference
design (Keynote mockups of a real state machine) that read cleaner than what either general-purpose
library produced automatically:

- **Rank** is assigned by longest path from the initial state(s), with cycles broken via a DFS pass
  (a reciprocal pair is a 2-cycle) -- see `classifyEdges`/`computeRanks`.
- Every transition's **rank distance** (`|rank(target) - rank(source)|`) determines how it's drawn:
  distance 0 (self-loop) is innermost, distance 1 is a plain straight line, distance ≥2 needs a
  "rail" nested outward the larger the distance gets.
- Rails are distributed across the **leading and trailing sides** of the diagram (perpendicular to
  flow) based on how much physical room a node's leading edge actually has to also host rails
  alongside its self-loop -- see the capacity principle below.
- Reciprocal pairs render as two parallel lines with edge-to-edge-packed (not overlapping) labels.
- Node width/height are **per-orientation**, not fixed constants -- see the next principle.

## Principles learned so far

- **A usage context's actual space constraints should inform node sizing, not a single fixed size
  used everywhere.** Vertical orientation is always used inside a narrow, fixed-width side panel
  (`ntrloc-state-machine-editor.js`), while horizontal orientation gets a full-width panel -- there's
  no reason both should use the same node width. Vertical got a narrower `NODE_WIDTH_VERTICAL` (state
  names wrap across lines via `wrapText`/`buildNode` to compensate) and a taller
  `NODE_HEIGHT_VERTICAL`, spending vertical orientation's own slack space (found live: a vertical
  diagram was only using about half its panel's available height) on more room for self-loop/rail
  attachment points along the node's left/right edges -- exactly the edge `NODE_HEIGHT` governs the
  length of in vertical orientation. Net effect confirmed live: the vertical diagram no longer needs
  horizontal scrolling at all (previously always needed some), and reads more spaciously without
  taking any more of its own panel's real estate. Node-label wrapping turned out to help horizontal
  orientation too, incidentally -- a long name that used to silently overflow its (un-clipped) SVG
  box now wraps cleanly there as well.

- **A self-loop should be the innermost connection on its node, and this is one instance of a more
  general nesting-by-distance rule, not a special case.** The full rule, confirmed against multiple
  reference examples of the same topology: distance 0 (self-loop) is innermost; distance 1 is a
  plain direct line (no nesting at all); distance ≥2 needs a rail, nested outward as distance
  increases (a distance-3 edge's rail sits further out than a distance-2 edge's, when they share a
  side). **Implemented**: `computeLayout`'s rail `nestIndex` assignment.

- **Which side (leading/trailing) a rail uses depends on how much physical room that node edge
  actually has**, not a fixed convention. A node's leading edge (where its self-loop always attaches)
  runs along the *rank* axis, so its available length is the rank-axis node size: `NODE_WIDTH` (160,
  spacious) for horizontal orientation, `NODE_HEIGHT` (56, tight) for vertical. Confirmed against two
  reference examples of the identical topology: horizontal orientation split its two skip-edges
  across both leading and trailing; vertical orientation stacked both of its skip-edges on the
  trailing side alone, leaving the (self-loop-occupied) leading side untouched. **Implemented**:
  `canSplitRails` in `computeLayout`, keyed off orientation for exactly this reason.

- **Two rails can only share a side without crossing if their rank-spans are disjoint or one
  properly contains the other -- spans that merely overlap (interleave) always cross, regardless of
  nesting order.** Found live: `Work in Progress -> Recently Finalized` (span `[0,2]`) and
  `Pending ISBN Finalization -> End` (span `[1,3]`) were both assigned to the same side purely by a
  "balance the count" heuristic, and crossed each other -- no choice of nesting depth for two
  interleaved spans on the same side can avoid this, it's a structural fact about arc diagrams, not
  a tuning problem. The orientation-based side preference (previous principle) is therefore a
  *default*, not a hard rule: an edge only uses its preferred side if nothing already placed there
  interleaves with it, and spills to the other side (even a normally-disfavored one) rather than
  cross. **Implemented**: `spansInterleave` + the conflict-checking loop in `computeLayout`'s rail
  side assignment.

- **Reciprocal pairs (A->B and B->A both present) render as two parallel lines with labels packed
  edge-to-edge**, not overlapping and not drawn on top of each other. Which dimension the labels
  need separation *in* depends on orientation: horizontal orientation offsets the pair vertically,
  where every label has the same fixed 16px height regardless of text length; vertical orientation
  offsets the pair horizontally, where it's the label's actual (text-length-dependent) *width* that
  has to clear its neighbor. A fixed pixel offset works for the first case and visibly fails for the
  second (found live: a 10px offset was plenty for two 16px-tall labels but nowhere near enough for
  two ~100px-wide ones). **Implemented**: `packOffsets`, called with per-orientation-appropriate
  widths.

- **A rail's label sits centered on the rail line itself, so half its footprint reaches back toward
  the node column it just left** -- for horizontal orientation that's a fixed 16px (already covered
  by the base rail gap), but for vertical orientation it's the label's *width*, which the base gap
  didn't originally account for (found live: long labels spilled back over the adjacent node's box).
  **Implemented**: `railGap` widens itself, for vertical orientation only, using the widest rail
  label's half-width.

- **A label centered on a feature at the outer edge of the diagram needs clearance in *both*
  directions from that feature, not just back toward whatever it's escaping.** The rail-gap fix
  above only reserved room between a rail and the node column it reaches back toward; the outermost
  rail (and, separately, a self-loop's own label at the tip of its bulge) also reaches the *other*
  way, out past the diagram's own nominal edge, where only the flat page `PADDING` was reserved --
  nowhere near enough for a wide label (found live: an outermost label's half-width was more than
  the entire page margin, so it started at a negative coordinate). Fixed at both the layout's own
  outer boundary (`outerLeadingMargin`/`outerTrailingMargin`, sized from whichever rail ends up
  outermost on that side) and at the self-loop's own reserve (`selfLoopReserve`, folding the bulge
  depth and the label's own half-extent into one number instead of tracking them separately).
  **Implemented**, both vertical-orientation-only for the same reason `railGap` is (horizontal's
  labels are a fixed 16px regardless of nest depth or text length).

- **Self-loops don't carry rank information and shouldn't be handed to a longest-path/BFS rank
  calculation as edges** -- their source and target are the same node, so there's no ranking signal
  to extract. They're excluded from `classifyEdges`'s forward/back-edge sets entirely and handled as
  their own category.

- **A layout algorithm (hand-rolled or a library) has no innate notion of "the" start state**, and
  if the graph contains a cycle, cycle-breaking can end up treating an arbitrary node as the
  diagram's visual root -- reading right-to-left or starting mid-story instead of flowing forward
  from where a viewer actually starts. Both dagre and ELK hit this same failure mode in turn (dagre's
  fix was insertion-order-dependent, ELK's was an explicit per-node layer constraint); the
  hand-rolled version's fix is `classifyEdges`'s own DFS always starting from `isInitial` states
  first, so back-edges get classified relative to the *semantic* root, not an arbitrary one. Any
  future layout approach should be checked for this same failure mode from day one, not discovered
  after the fact.

- **Edge labels need real reserved space as part of the geometry computation itself, not
  collision-avoidance bolted on after the fact.** An earlier hand-rolled version that computed edge
  geometry first and then tried to nudge overlapping labels apart in a second pass kept finding new
  collision cases each iteration (label-vs-label, then label-vs-node, then label pushed into a node
  it hadn't been checked against). The current version never has this problem because every label's
  position is *derived* from the same nesting/offset math that placed its edge -- there's no separate
  "now go fix any overlaps" pass that could invalidate something already placed.

- **The rendering canvas (SVG viewBox, or equivalent) must be sized from the actual extent of
  everything drawn into it, not just node positions.** `computeLayout`'s `width`/`height` are derived
  from the same `leadingReserve`/`trailingReserve`/`rankGap` values that placed every rail and label,
  not recomputed separately from node coordinates alone -- the two categorically can't drift apart
  the way they did in the very first hand-rolled version (which clipped a label a later "fix" had
  pushed past a boundary computed before that fix existed).

## Why not a general Sugiyama library (dagre, then ELK)

Both were real improvements over the *original* hand-rolled layout (see below) -- dagre gave
structural guarantees (`rank(target) > rank(source)`, genuinely distinct multigraph paths) that
eliminated whole bug classes, and ELK went further with native orthogonal routing and native
self-loop support. But a general-purpose layered-graph library optimizes for *arbitrary* DAGs, and
can't be told a domain-specific rule like "nest connectors by rank distance, self-loop innermost,
direct neighbor a plain line, everything else a rail split across both sides by capacity" -- the
exact rule a hand-drawn reference design used and confirmed reads cleaner than what ELK produced
automatically for this specific family of diagrams. State machines are a much more constrained shape
than an arbitrary DAG (a roughly linear progression with self-loops, adjacent transitions, and the
occasional back-edge or skip-ahead shortcut), which is exactly what makes a purpose-built router
tractable here where it wouldn't be for a general diagramming tool.

## Why not more hand-rolling (the original pass, before dagre)

(Kept for the record -- this reasoning is what motivated moving off the *original* hand-rolled
layout onto dagre, and generalizes to any future "should we hand-roll this or use a real
implementation" question. The current implementation is hand-rolled too, but deliberately: see
above for why a general library couldn't express the actual rule wanted here. The distinction that
matters is "hand-rolled against a specific, confirmed rule" vs. "hand-rolled heuristics with no
correctness argument," not hand-rolled vs. not.)

The original hand-rolled version went through several rounds of live bugs, each one a correct fix
for the specific collision it targeted and blind to the next one it created (a same-rank edge routed
through a sibling's self-loop; a reciprocal pair drawing the identical line twice; a same-rank
edge's bowed label clipped by a too-small viewBox). That pattern -- fix compiles, fix passes the one
check it was written for, next screenshot reveals a new failure mode -- is the signature of
heuristics that don't have a real correctness argument behind them, not just insufficient testing.
The current implementation avoids this by deriving every position from one shared set of rules
(rank, distance, side capacity) rather than patching each collision as it's found.

## Open questions / not yet solved

- **Branching (multiple sibling states in one rank)** -- every reference example so far is a single
  linear chain (one state per rank). `computeLayout` reuses the original centered-stacking logic for
  multi-sibling ranks so it won't crash or look pathological, but the rail-nesting rule hasn't been
  designed against that case at all -- e.g. it's not defined which row's edge a rail should hug when
  the ranks it spans have different numbers of siblings.
- **More than two rail edges sharing a side** -- `allocateFractions`' avoid-center fallback
  (`[0.85, 0.95, 0.98]` / `[0.15, 0.05, 0.02]`) only comfortably handles a handful before points get
  uncomfortably close to the node's corners; untested past the 3-rail-edge case this component's own
  fixture happens to produce.
- **A rail edge that itself needs its label to clear another rail's label at a different nest depth
  on the same side** -- not yet a case that's come up (this fixture's two same-side rails have
  visibly different lengths), but nothing currently checks for it explicitly.
