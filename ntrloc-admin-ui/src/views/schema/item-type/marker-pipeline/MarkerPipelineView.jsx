import { usePipelineForItemType } from '../../../../global/model/PipelineModel'
import { useMarkerPipelineViewModel } from './MarkerPipelineViewModel'
import { STRIP_WIDTH, bodyMidY, chipLayout, selectRowLayout, signalRowLayout, signalConditionLayout, filterPortLayout, PSEUDO_LABEL_GAP } from './markerPipelineLayout'
import Button from '../../../../controls/button/Button'
import './MarkerPipelineView.css'

const PIN_RING_R = 7
const PIN_FILL_R = 3.5
// A carrier input's own arrowhead outline, and the smaller filled one every carrier edge's own
// terminus draws to nest inside it -- see Pin's own `shape="arrow"` and PipelineEdge below. Both
// always point the same way (+x, rightward) and never need to rotate: every edge this diagram draws
// enters horizontally by construction (see orthogonalPath), the same invariant that makes a fixed
// carrier-input pin shape meaningful in the first place. Symmetric (same half-width ahead of and
// behind cx,cy), not just anchored at the same point -- a triangle's own visual weight sits toward
// its wider back edge, not its tip, so an asymmetric one (tip further from cx than back) wouldn't
// actually look centered on the pin even while technically sharing its anchor; this does.
const PIN_ARROW_HALF_WIDTH = 7
const PIN_ARROW_HALF_HEIGHT = 8
const EDGE_ARROW_HALF_WIDTH = 4
const EDGE_ARROW_HALF_HEIGHT = 5
// Nudges the terminator left of the exact pin center it'd otherwise sit concentric on -- a deliberate
// final offset on top of that centering, not a correction to it.
const EDGE_ARROW_OFFSET_X = 1

function arrowPoints(cx, cy, halfWidth, halfHeight) {
  return `${cx + halfWidth},${cy} ${cx - halfWidth},${cy - halfHeight} ${cx - halfWidth},${cy + halfHeight}`
}

// A pin: ring + dot, the same bullseye motif as End, scaled down -- see MarkerPipelineView.css.
// variant: 'signal' recolors it amber->yellow for a Condition/ConditionGroup's own pins, the same
// domain split the carrier/signal edge colors already make. shape: 'arrow' replaces the bullseye
// with a hollow right-pointing triangle instead -- a carrier block's own input port only (never an
// output, never a signal pin), giving carrier flow an explicit direction beyond "left is in, right
// is out" alone. See PipelineEdge for the smaller filled arrow every carrier edge draws to nest
// inside this one.
function Pin({ cx, cy, variant, shape }) {
  if (shape === 'arrow') {
    return (
      <polygon
        className={variant ? `mp-pin-arrow mp-pin-arrow-${variant}` : 'mp-pin-arrow'}
        points={arrowPoints(cx, cy, PIN_ARROW_HALF_WIDTH, PIN_ARROW_HALF_HEIGHT)}
      />
    )
  }
  return (
    <g className={variant ? `mp-pin mp-pin-${variant}` : 'mp-pin'}>
      <circle className="mp-pin-ring" cx={cx} cy={cy} r={PIN_RING_R} />
      <circle className="mp-pin-fill" cx={cx} cy={cy} r={PIN_FILL_R} />
    </g>
  )
}

// A subject ('linked item: X', 'property: X'), predicate ('= NATURE', a <select> row's own branch
// caption), or test ('= APPROVED', 'any match' -- a predicate box's own comparison) chip -- see
// textChip in markerPipelineLayout.js. kind drives which pill style it renders as (see
// MarkerPipelineView.css): a filled green rounded rect for a subject, an unfilled light-blue one for
// predicate/test alike -- the same content (a leaf predicate's own comparison text) read in two
// different places, reached via two different CSS classes only because they're built by two
// different layout call sites.
const CHIP_KINDS = new Set(['subject', 'test', 'predicate'])

function TextChip({ chip }) {
  const kind = CHIP_KINDS.has(chip.kind) ? chip.kind : 'predicate'
  return (
    <g>
      <rect className={`mp-${kind}-chip-rect`} x={chip.x} y={chip.y} width={chip.width} height={chip.height} />
      <text className={`mp-${kind}-chip-text`} x={chip.x + chip.width / 2} y={chip.y + chip.height / 2 + 4}>
        {chip.text}
      </text>
    </g>
  )
}

// One predicate's own box (see markerPipelineLayout.placePredicateBox and
// pipelineConditionText.describePredicateBox) -- a bordered rect labeled with the predicate's family
// ('count', 'totality', 'value'), its own subject chip (if any), each child predicate's own box
// (recursively -- a CountedPredicate/TotalityPredicate's own sub-predicate(s), nested arbitrarily
// deep), then its own test chip (if any) last.
function PredicateBox({ box }) {
  if (!box) return null
  return (
    <g>
      <rect className="mp-predicate-box-rect" x={box.rect.x} y={box.rect.y} width={box.rect.width} height={box.rect.height} />
      <text className="mp-predicate-box-label" x={box.rect.x + box.rect.width / 2} y={box.labelY}>
        {box.label}
      </text>
      {box.subjectChip && <TextChip chip={box.subjectChip} />}
      {box.children.map((child) => (
        <PredicateBox key={`${child.rect.x},${child.rect.y}`} box={child} />
      ))}
      {box.testChip && <TextChip chip={box.testChip} />}
    </g>
  )
}

// START = a plain filled dot (the standard UML initial-pseudostate convention); END = a ring around
// a filled dot. Same shape family as a pin, just bigger -- pin < Start < End. Start previously had a
// notch bitten out toward its outgoing edge, but a notch is a fixed direction while an edge's actual
// angle depends on where the connected block ends up (rank/sibling layout, or a saved position later)
// -- the two didn't reliably line up, which read as a stray triangle rather than an opening. Plain
// and unambiguous beats a decoration that can't stay aligned with what it's decorating. Its own
// "start"/"end" label sits outside the shape, on whichever side carrier flow isn't pointing --
// before Start (nothing flows in from further left), after End (nothing flows out further right) --
// so it never has to compete with an edge landing on the shape itself.
// editable + the three pointer handlers: present only while the diagram is in edit mode (see
// MarkerPipelineDiagram), letting Start/End be dragged around like any other node -- PipelineLayout
// itself makes no distinction between a pseudostate's position and a carrier block's.
function PseudoStateNode({ rect, kind, editable, onPointerDown, onPointerMove, onPointerUp }) {
  const cx = rect.x + rect.width / 2
  const cy = rect.y + rect.height / 2
  const r = rect.width / 2
  const isStart = kind !== 'END'
  const labelX = isStart ? rect.x - PSEUDO_LABEL_GAP : rect.x + rect.width + PSEUDO_LABEL_GAP

  return (
    <g
      className={editable ? 'mp-node mp-node-editable' : 'mp-node'}
      onPointerDown={onPointerDown}
      onPointerMove={onPointerMove}
      onPointerUp={onPointerUp}
    >
      {kind === 'END' && <circle className="mp-pseudo-ring" cx={cx} cy={cy} r={r} />}
      <circle className="mp-pseudo-fill" cx={cx} cy={cy} r={kind === 'END' ? r * 0.55 : r} />
      <text className="mp-pseudo-label" x={labelX} y={cy + 4} textAnchor={isStart ? 'end' : 'start'}>
        {isStart ? 'start' : 'end'}
      </text>
    </g>
  )
}

// A carrier block or signal node's own edit/delete icons (see markerPipelineLayout.headerIcons for
// their position -- unconditionally reserved in the node's own width, drawn only while editing) --
// same stroke-icon convention as ChevronIcon (viewBox 0 0 24 24, stroke=currentColor), just nested as
// an SVG-in-SVG nested viewport instead of a standalone <svg> element, since these live inside a
// node's own shape, not floating HTML chrome. onEdit/onDelete both currently just reopen the same
// context menu a right-click on the node already opens (see MarkerPipelineDiagram's own "shell for
// now" comment on that menu) -- a second, more discoverable entry point into the one place real
// edit/delete actions will eventually live, not yet wired to any real behavior of their own.
function HeaderIcons({ icons, onEdit, onDelete }) {
  if (!icons) return null
  return (
    <g className="mp-header-icons">
      <svg
        className="mp-header-icon mp-header-icon-edit"
        x={icons.edit.x}
        y={icons.edit.y}
        width={icons.edit.size}
        height={icons.edit.size}
        viewBox="0 0 24 24"
        fill="none"
        stroke="currentColor"
        strokeWidth="2"
        strokeLinecap="round"
        strokeLinejoin="round"
        onPointerDown={(event) => event.stopPropagation()}
        onClick={onEdit}
      >
        <title>Edit</title>
        <path d="M17 3a2.828 2.828 0 1 1 4 4L7.5 20.5 2 22l1.5-5.5L17 3z" />
      </svg>
      <svg
        className="mp-header-icon mp-header-icon-delete"
        x={icons.delete.x}
        y={icons.delete.y}
        width={icons.delete.size}
        height={icons.delete.size}
        viewBox="0 0 24 24"
        fill="none"
        stroke="currentColor"
        strokeWidth="2"
        strokeLinecap="round"
        strokeLinejoin="round"
        onPointerDown={(event) => event.stopPropagation()}
        onClick={onDelete}
      >
        <title>Delete</title>
        <polyline points="3 6 5 6 21 6" />
        <path d="M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6m3 0V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2" />
        <line x1="10" y1="11" x2="10" y2="17" />
        <line x1="14" y1="11" x2="14" y2="17" />
      </svg>
    </g>
  )
}

// A block is a full-width header band (its "<add>"-style title, divided from the rest by a
// full-width rule) over a body band. The body's single output port always gets its own divided strip
// on the right, unlabeled -- there's only ever one. The input side is the same, symmetric, on the
// left, for a block with exactly one input port (<add>/<set>/<remove>). A block with several
// distinct input ports -- just <filter>, carrier "input" plus its "if" gate reference -- gets its
// own FilterNode below instead, with each port its own labeled row.
function CarrierBlockNode({ rect, label, editable, onContextMenu, onPointerDown, onPointerMove, onPointerUp }) {
  const rightStripX = rect.x + rect.width - STRIP_WIDTH
  const midY = bodyMidY(rect)
  const { centerX, titleY, headerBottom, chips, icons } = chipLayout(rect, label.markerNames, editable)

  return (
    <g
      className={editable ? 'mp-node mp-node-editable' : 'mp-node'}
      onContextMenu={onContextMenu}
      onPointerDown={onPointerDown}
      onPointerMove={onPointerMove}
      onPointerUp={onPointerUp}
    >
      <rect className="mp-node-rect" x={rect.x} y={rect.y} width={rect.width} height={rect.height} />
      <line className="mp-node-divider" x1={rect.x} y1={headerBottom} x2={rect.x + rect.width} y2={headerBottom} />
      <line className="mp-node-divider" x1={rect.x + STRIP_WIDTH} y1={headerBottom} x2={rect.x + STRIP_WIDTH} y2={rect.y + rect.height} />
      <line className="mp-node-divider" x1={rightStripX} y1={headerBottom} x2={rightStripX} y2={rect.y + rect.height} />
      <text className="mp-node-title" x={centerX} y={titleY}>
        {label.title}
      </text>
      <HeaderIcons icons={icons} onEdit={onContextMenu} onDelete={onContextMenu} />
      {chips.map((chip) => (
        <g key={chip.name}>
          <rect className="mp-marker-chip-rect" x={chip.x} y={chip.y} width={chip.width} height={chip.height} />
          <text className="mp-marker-chip-text" x={chip.x + chip.width / 2} y={chip.y + chip.height / 2 + 4}>
            {chip.name}
          </text>
        </g>
      ))}
      <Pin cx={rect.x + STRIP_WIDTH / 2} cy={midY} shape="arrow" />
      <Pin cx={rightStripX + STRIP_WIDTH / 2} cy={midY} />
    </g>
  )
}

// <filter> is 3 sections: a full-width title band on top, then exactly 2 below it, divided by one
// vertical rule -- the lower-left section holds both input ports (carrier "input", then "if") with
// their own labels, the lower-right section holds nothing but the single output pin, the same shape
// every other carrier block's own right-side strip has. No separate strip carved out around the left
// pins themselves -- that would read as a third, empty-looking middle area, which is exactly the
// "central area" this shape deliberately has none of. The "if" pin is drawn in the signal domain's
// own color, since it's literally where a dashed signal-reference edge lands (see
// SignalReferenceEdge).
function FilterNode({ rect, label, editable, onContextMenu, onPointerDown, onPointerMove, onPointerUp }) {
  const rightStripX = rect.x + rect.width - STRIP_WIDTH
  const midY = bodyMidY(rect)
  const { centerX, titleY, headerBottom, input, ifPort, icons } = filterPortLayout(rect, editable)

  return (
    <g
      className={editable ? 'mp-node mp-node-editable' : 'mp-node'}
      onContextMenu={onContextMenu}
      onPointerDown={onPointerDown}
      onPointerMove={onPointerMove}
      onPointerUp={onPointerUp}
    >
      <rect className="mp-node-rect" x={rect.x} y={rect.y} width={rect.width} height={rect.height} />
      <line className="mp-node-divider" x1={rect.x} y1={headerBottom} x2={rect.x + rect.width} y2={headerBottom} />
      <line className="mp-node-divider" x1={rightStripX} y1={headerBottom} x2={rightStripX} y2={rect.y + rect.height} />
      <line className="mp-node-divider" x1={rect.x} y1={ifPort.top} x2={rightStripX} y2={ifPort.top} />
      <text className="mp-node-title" x={centerX} y={titleY}>
        {label.title}
      </text>
      <HeaderIcons icons={icons} onEdit={onContextMenu} onDelete={onContextMenu} />
      <text className="mp-port-label" x={input.labelX} y={input.labelY}>
        input
      </text>
      <text className="mp-port-label mp-port-label-signal" x={ifPort.labelX} y={ifPort.labelY}>
        if
      </text>
      <Pin cx={input.x} cy={input.y} shape="arrow" />
      <Pin cx={ifPort.x} cy={ifPort.y} variant="signal" />
      <Pin cx={rightStripX + STRIP_WIDTH / 2} cy={midY} />
    </g>
  )
}

// <select>'s own shape: one shared input pin (centered on the whole body, same as any other block --
// it broadcasts internally to every row, see Select.java's own doc comment), then a stack of
// independently-gated rows sharing one open content area -- no divider between them, the same as the
// stacked chips in an ordinary CarrierBlockNode -- each still with its own output pin at its own
// row's y even though nothing visually marks where one row ends and the next begins. The last row is
// always the synthetic Otherwise branch -- no chips, just the "(Otherwise)" text (see
// MarkerPipelineViewModel's blockLabel) -- a plain routing pin, not a marker-adding action, so it's
// rendered distinctly from a real row's dashed marker chips.
function SelectNode({ rect, label, editable, onContextMenu, onPointerDown, onPointerMove, onPointerUp }) {
  const rightStripX = rect.x + rect.width - STRIP_WIDTH
  const midY = bodyMidY(rect)
  const { centerX, titleY, headerBottom, propertyChip, rows, icons } = selectRowLayout(rect, label.rows, label.propertyLabel, editable)

  return (
    <g
      className={editable ? 'mp-node mp-node-editable' : 'mp-node'}
      onContextMenu={onContextMenu}
      onPointerDown={onPointerDown}
      onPointerMove={onPointerMove}
      onPointerUp={onPointerUp}
    >
      <rect className="mp-node-rect" x={rect.x} y={rect.y} width={rect.width} height={rect.height} />
      <line className="mp-node-divider" x1={rect.x} y1={headerBottom} x2={rect.x + rect.width} y2={headerBottom} />
      <line className="mp-node-divider" x1={rect.x + STRIP_WIDTH} y1={headerBottom} x2={rect.x + STRIP_WIDTH} y2={rect.y + rect.height} />
      <line className="mp-node-divider" x1={rightStripX} y1={headerBottom} x2={rightStripX} y2={rect.y + rect.height} />
      <text className="mp-node-title" x={centerX} y={titleY}>
        {label.title}
      </text>
      <HeaderIcons icons={icons} onEdit={onContextMenu} onDelete={onContextMenu} />
      {propertyChip && <TextChip chip={propertyChip} />}
      {rows.map((row) =>
        row.otherwise ? (
          <text key={row.id} className="mp-select-otherwise-text" x={centerX} y={row.labelY}>
            (Otherwise)
          </text>
        ) : (
          <g key={row.id}>
            {row.conditionChip && <TextChip chip={row.conditionChip} />}
            {row.chips.map((chip) => (
              <g key={chip.name}>
                <rect className="mp-marker-chip-rect" x={chip.x} y={chip.y} width={chip.width} height={chip.height} />
                <text className="mp-marker-chip-text" x={chip.x + chip.width / 2} y={chip.y + chip.height / 2 + 4}>
                  {chip.name}
                </text>
              </g>
            ))}
          </g>
        )
      )}
      <Pin cx={rect.x + STRIP_WIDTH / 2} cy={midY} shape="arrow" />
      {rows.map((row) => (
        <Pin key={row.id} cx={rightStripX + STRIP_WIDTH / 2} cy={row.midY} />
      ))}
    </g>
  )
}

// A top-level Condition/ConditionGroup -- read, not routed, so it gets no left input strip at all
// (see signalNodeDimensions' own comment). Two different internal shapes, matching
// signalNodeDimensions/signalRowLayout/signalConditionLayout: a ConditionGroup (more than one pin)
// is a title over a stacked pin-row per member Condition, each with its own short label; a bare
// Condition (always exactly one pin) is a title over its own root predicate box (see describeCondition
// and PredicateBox), with one pin centered on the whole body.
function SignalNode({ rect, node, description, editable, onContextMenu, onPointerDown, onPointerMove, onPointerUp }) {
  const bodyRight = rect.x + rect.width - STRIP_WIDTH
  const isGroup = node.pinIds.length > 1

  const groupLayout = isGroup ? signalRowLayout(rect, node.pinIds, description, editable) : null
  const conditionLayout = isGroup ? null : signalConditionLayout(rect, description, editable)
  const { centerX, titleY, headerBottom, icons } = groupLayout ?? conditionLayout

  return (
    <g
      className={editable ? 'mp-node mp-node-editable' : 'mp-node'}
      onContextMenu={onContextMenu}
      onPointerDown={onPointerDown}
      onPointerMove={onPointerMove}
      onPointerUp={onPointerUp}
    >
      <rect className="mp-signal-node-rect" x={rect.x} y={rect.y} width={rect.width} height={rect.height} />
      <line className="mp-signal-node-divider" x1={rect.x} y1={headerBottom} x2={rect.x + rect.width} y2={headerBottom} />
      <line className="mp-signal-node-divider" x1={bodyRight} y1={headerBottom} x2={bodyRight} y2={rect.y + rect.height} />
      <text className="mp-signal-node-title" x={centerX} y={titleY}>
        {node.title}
      </text>
      <HeaderIcons icons={icons} onEdit={onContextMenu} onDelete={onContextMenu} />
      {isGroup ? (
        <>
          {groupLayout.propertyChip && <TextChip chip={groupLayout.propertyChip} />}
          {groupLayout.rows.slice(1).map((row) => (
            <line key={`div-${row.id}`} className="mp-signal-node-divider" x1={rect.x} y1={row.top} x2={bodyRight} y2={row.top} />
          ))}
          {groupLayout.rows.map((row) => (
            <g key={row.id}>
              {row.labelChip && <TextChip chip={row.labelChip} />}
              <Pin cx={bodyRight + STRIP_WIDTH / 2} cy={row.midY} variant="signal" />
            </g>
          ))}
        </>
      ) : (
        <>
          <PredicateBox box={conditionLayout.box} />
          <Pin cx={bodyRight + STRIP_WIDTH / 2} cy={conditionLayout.pinY} variant="signal" />
        </>
      )}
    </g>
  )
}

// How sharply an edge's own interior bends soften into a curve instead of a sharp right angle --
// one named constant, easy to tune later, rather than a magic number buried in edgePathD below.
const EDGE_CORNER_RADIUS = 16

// Every edge is a polyline (see orthogonalPath) that always connects horizontally at both ends --
// turned into an SVG path string, M/L straight into and out of each endpoint, with every *interior*
// bend (never the first or last point -- those are real pin terminals, not corners) rounded off by
// EDGE_CORNER_RADIUS instead of left as a sharp right angle: each straight run is trimmed short by
// the corner's own radius on both sides of it, joined by a quadratic curve through the original
// corner point. The radius is clamped to half of whichever of that corner's own two segments is
// shorter, so a short stub (e.g. a tight detour) never produces a curve bigger than the straight run
// it has to fit on -- without that, two adjacent corners on a short segment could overlap. Shared by
// both edge kinds below so the two can never draw their own points differently.
function edgePathD(points) {
  if (points.length < 3) {
    return points.map((p, i) => `${i === 0 ? 'M' : 'L'} ${p.x} ${p.y}`).join(' ')
  }
  let d = `M ${points[0].x} ${points[0].y} `
  for (let i = 1; i < points.length - 1; i++) {
    const prev = points[i - 1]
    const curr = points[i]
    const next = points[i + 1]
    const inLen = Math.hypot(curr.x - prev.x, curr.y - prev.y)
    const outLen = Math.hypot(next.x - curr.x, next.y - curr.y)
    if (inLen === 0 || outLen === 0) {
      // A degenerate corner (e.g. a dead-straight edge, whose "bend" points coincide -- see
      // orthogonalPath's own forward-path formula when from.y === to.y) -- nothing to round.
      d += `L ${curr.x} ${curr.y} `
      continue
    }
    const r = Math.min(EDGE_CORNER_RADIUS, inLen / 2, outLen / 2)
    const before = { x: prev.x + ((curr.x - prev.x) * (inLen - r)) / inLen, y: prev.y + ((curr.y - prev.y) * (inLen - r)) / inLen }
    const after = { x: curr.x + ((next.x - curr.x) * r) / outLen, y: curr.y + ((next.y - curr.y) * r) / outLen }
    d += `L ${before.x} ${before.y} Q ${curr.x} ${curr.y} ${after.x} ${after.y} `
  }
  const last = points[points.length - 1]
  d += `L ${last.x} ${last.y}`
  return d
}

// A "pulled" reference from a Condition's own pin to whatever gate reads it -- dashed and yellow
// (see MarkerPipelineView.css), distinct from a solid amber carrier-flow edge, since nothing is
// actually pushed along it: see rankByLongestPath's own comment on why signal references carry no
// Route at all. isDragging: true for the one edge whose own bend handle is actively being dragged
// (see MarkerPipelineViewModel's draggingEdgeKey) -- drawn white instead of its own domain color so
// it's unambiguous which edge is actually moving under the cursor, even where it crosses close by
// another one.
function SignalReferenceEdge({ edge, isDragging }) {
  const className = isDragging ? 'mp-signal-edge-path mp-edge-path-dragging' : 'mp-signal-edge-path'
  return <path className={className} d={edgePathD(edge.points)} />
}

// Carrier-flow edges -- end in their own small filled arrowhead, always pointing +x (every edge
// enters horizontally by construction -- see orthogonalPath), sized to nest inside the hollow one
// its own carrier input pin draws at the exact same point (see Pin's own shape="arrow"). A signal
// edge draws no such arrow (see SignalReferenceEdge) -- it's pulled, not pushed, so there's nothing
// here for a "this is the direction it flows" cue to be about. isDragging: see SignalReferenceEdge's
// own comment.
function PipelineEdge({ edge, isDragging }) {
  const className = isDragging ? 'mp-edge-path mp-edge-path-dragging' : 'mp-edge-path'
  const arrowClassName = isDragging ? 'mp-edge-arrowhead mp-edge-arrowhead-dragging' : 'mp-edge-arrowhead'
  const end = edge.points[edge.points.length - 1]
  return (
    <g>
      <path className={className} d={edgePathD(edge.points)} />
      <polygon className={arrowClassName} points={arrowPoints(end.x - EDGE_ARROW_OFFSET_X, end.y, EDGE_ARROW_HALF_WIDTH, EDGE_ARROW_HALF_HEIGHT)} />
    </g>
  )
}

// An edge's own single adjustable bend (see orthogonalPath) -- only rendered in edit mode, same as a
// node becoming draggable. The cursor names which axis it's actually constrained to, since the
// handle itself gives no other visual hint: ew-resize for a forward edge's vertical run (only its
// own x ever moves), ns-resize for a backward edge's detour lane (only its own y ever moves).
function EdgeHandle({ handle, onPointerDown, onPointerMove, onPointerUp }) {
  return (
    <circle
      className={`mp-edge-handle mp-edge-handle-${handle.axis}`}
      cx={handle.x}
      cy={handle.y}
      r={5}
      onPointerDown={onPointerDown}
      onPointerMove={onPointerMove}
      onPointerUp={onPointerUp}
    />
  )
}

// Diagram for the item type's one pipeline -- condition/predicate text is the one place this diagram
// does surface gate detail (see pipelineConditionText.js), since a Condition has no admin-authored
// name of its own the way a marker or a block's title does; everywhere else stays the same shape-only
// restraint StateMachineDiagramView's own comment describes. Editable only in the sense of
// repositioning nodes (see the Edit/Save/Cancel bar below and MarkerPipelineViewModel's drag
// handlers) -- no click-to-select, no editing the pipeline's own structure yet. No name/title shown
// -- a pipeline has none; it's identified by its item type, which is already this whole tab's own
// heading.
function MarkerPipelineDiagram({ pipelineView, itemTypeId, saveLayout, initialView, onViewChange }) {
  const {
    layout,
    blocks,
    signalDescriptions,
    start,
    end,
    containerRef,
    view,
    onPointerDown,
    onPointerMove,
    onPointerUp,
    contextMenu,
    onBlockContextMenu,
    closeContextMenu,
    isEditing,
    isSaving,
    saveError,
    startEditing,
    saveEditing,
    cancelEditing,
    onNodePointerDown,
    onNodePointerMove,
    onNodePointerUp,
    onEdgeHandlePointerDown,
    onEdgeHandlePointerMove,
    onEdgeHandlePointerUp,
    draggingEdgeKey,
  } = useMarkerPipelineViewModel(pipelineView, itemTypeId, saveLayout, initialView, onViewChange)

  // An SVG viewBox is an infinite-canvas window into the diagram's fixed coordinate space: (x, y)
  // is its top-left corner, (width/zoom, height/zoom) is how much of the diagram it shows. Panning
  // moves (x, y); zooming shrinks/grows the window. The SVG itself always stays at a fixed 100%/100%
  // of the container (see the stylesheet) -- nothing about its own size ever changes, only what
  // portion of the diagram it's looking at.
  const viewBox = view ? `${view.x} ${view.y} ${view.width / view.zoom} ${view.height / view.zoom}` : undefined

  // At most one of these is ever set -- draggingEdgeKey names exactly one edge, which is either a
  // carrier edge or a signal one, never both -- used to draw that one edge last (see below).
  const draggingCarrierEdge = layout.edges.find((edge) => edge.key === draggingEdgeKey)
  const draggingSignalEdge = layout.signalEdges.find((edge) => edge.key === draggingEdgeKey)

  return (
    <div
      ref={containerRef}
      className="marker-pipeline-diagram-scroll"
      onPointerDown={onPointerDown}
      onPointerMove={onPointerMove}
      onPointerUp={onPointerUp}
    >
      {viewBox && (
        <svg className="marker-pipeline-diagram-svg" viewBox={viewBox} role="img" aria-label="Marker decision pipeline">
          {/* Nodes first, edges next, each edge's own bend handle last (edit mode only): a connector
              is always fully visible crossing over a block or a pseudostate's own shape, never the
              other way around, and a handle is always reachable for dragging even where an edge
              crosses close by it. */}
          <g>
            <PseudoStateNode
              rect={layout.rects.get(start.id)}
              kind="START"
              editable={isEditing}
              onPointerDown={(event) => onNodePointerDown(event, start.id)}
              onPointerMove={onNodePointerMove}
              onPointerUp={onNodePointerUp}
            />
            {pipelineView.pipeline.carrierBlocks.map((block) => {
              const label = blocks.get(block.id)
              const Node = nodeComponentFor(label)
              return (
                <Node
                  key={block.id}
                  rect={layout.rects.get(block.id)}
                  label={label}
                  editable={isEditing}
                  onContextMenu={(event) => onBlockContextMenu(event, block.id)}
                  onPointerDown={(event) => onNodePointerDown(event, block.id)}
                  onPointerMove={onNodePointerMove}
                  onPointerUp={onNodePointerUp}
                />
              )
            })}
            <PseudoStateNode
              rect={layout.rects.get(end.id)}
              kind="END"
              editable={isEditing}
              onPointerDown={(event) => onNodePointerDown(event, end.id)}
              onPointerMove={onNodePointerMove}
              onPointerUp={onNodePointerUp}
            />
            {layout.signalNodes.map((node) => (
              <SignalNode
                key={node.id}
                rect={layout.rects.get(node.id)}
                node={node}
                description={signalDescriptions.get(node.id)}
                editable={isEditing}
                onContextMenu={(event) => onBlockContextMenu(event, node.id)}
                onPointerDown={(event) => onNodePointerDown(event, node.id)}
                onPointerMove={onNodePointerMove}
                onPointerUp={onNodePointerUp}
              />
            ))}
          </g>
          <g>
            {/* The one edge actually being dragged is drawn last, after every other edge of either
                kind -- SVG has no z-index, only draw order, so without this an edge that happened to
                come earlier in the array could render *under* one it visually crosses, hiding the
                very path an admin is trying to watch move. */}
            {layout.edges.map((edge) =>
              edge.key === draggingEdgeKey ? null : <PipelineEdge key={edge.key} edge={edge} />,
            )}
            {layout.signalEdges.map((edge) =>
              edge.key === draggingEdgeKey ? null : <SignalReferenceEdge key={edge.key} edge={edge} />,
            )}
            {draggingCarrierEdge && <PipelineEdge key={draggingCarrierEdge.key} edge={draggingCarrierEdge} isDragging />}
            {draggingSignalEdge && <SignalReferenceEdge key={draggingSignalEdge.key} edge={draggingSignalEdge} isDragging />}
          </g>
          {isEditing && (
            <g>
              {[...layout.edges, ...layout.signalEdges].map((edge) => (
                <EdgeHandle
                  key={edge.key}
                  handle={edge.handle}
                  onPointerDown={(event) => onEdgeHandlePointerDown(event, edge.key, edge.handle)}
                  onPointerMove={onEdgeHandlePointerMove}
                  onPointerUp={onEdgeHandlePointerUp}
                />
              ))}
            </g>
          )}
        </svg>
      )}
      {/* Plain HTML, not SVG -- rendered outside the viewBox entirely, so it's never subject to the
          canvas's own zoom. A shell for now: no real actions yet (this diagram is still read-only),
          just proof that a block's own right-click can open something positioned and sized
          normally, independent of however zoomed-in/out the diagram happens to be. */}
      {contextMenu && (
        <>
          {/* Dismisses on pointerdown, not click -- the container underneath has its own
              onPointerDown (pan-start) attached, and without stopPropagation here that still fires
              on every click-away, calling setPointerCapture before this element's own click has a
              chance to resolve. Pointer capture can make click-event routing inconsistent across
              browsers once that's happened, so this intercepts a layer earlier instead of relying on
              click order being reliable. */}
          <div
            className="mp-context-menu-overlay"
            onPointerDown={(event) => { event.stopPropagation(); closeContextMenu() }}
            onContextMenu={(event) => { event.preventDefault(); event.stopPropagation(); closeContextMenu() }}
          />
          {/* Same stopPropagation reasoning as the overlay above -- without it, clicking an item in
              here bubbles down to the diagram container's own onPointerDown just as much as clicking
              the overlay would. */}
          <div
            className="mp-context-menu"
            style={{ left: contextMenu.x, top: contextMenu.y }}
            onPointerDown={(event) => event.stopPropagation()}
          >
            <button type="button" className="mp-context-menu-item" onClick={closeContextMenu}>
              Edit…
            </button>
            <button type="button" className="mp-context-menu-item" onClick={closeContextMenu}>
              Duplicate
            </button>
            <button type="button" className="mp-context-menu-item mp-context-menu-item-destructive" onClick={closeContextMenu}>
              Delete
            </button>
          </div>
        </>
      )}
      {/* Plain HTML, same reasoning as the context menu above: floats over the canvas, fixed to the
          container rather than the diagram, so panning/zooming never moves or scales it.
          stopPropagation on pointerdown for the same reason as the context menu above: without it,
          the container's own onPointerDown (pan-start) fires first on the way up, calling
          preventDefault()/setPointerCapture() before this button's click is ever synthesized -- which
          suppresses the click in some browsers/input paths even though hit-testing alone would have
          landed on the button correctly. */}
      <div className="mp-edit-bar" onPointerDown={(event) => event.stopPropagation()}>
        {saveError && <span className="mp-edit-error">{saveError}</span>}
        {isEditing ? (
          <>
            <Button variant="secondary" onClick={cancelEditing} disabled={isSaving}>Cancel</Button>
            <Button variant="primary" onClick={saveEditing} disabled={isSaving}>
              {isSaving ? 'Saving…' : 'Save'}
            </Button>
          </>
        ) : (
          <Button variant="primary" onClick={startEditing}>Edit</Button>
        )}
      </div>
    </div>
  )
}

function nodeComponentFor(label) {
  if (label.isFilter) return FilterNode
  if (label.rows) return SelectNode
  return CarrierBlockNode
}

function MarkerPipelineView({ itemTypeId, initialView, onViewChange }) {
  const { status, pipeline, saveLayout } = usePipelineForItemType(itemTypeId)

  if (status === 'loading') return null
  if (!pipeline) {
    return <p className="status">No marker decision pipeline defined.</p>
  }

  return (
    <marker-pipeline-view>
      <MarkerPipelineDiagram
        pipelineView={pipeline}
        itemTypeId={itemTypeId}
        saveLayout={saveLayout}
        initialView={initialView}
        onViewChange={onViewChange}
      />
    </marker-pipeline-view>
  )
}

export default MarkerPipelineView
