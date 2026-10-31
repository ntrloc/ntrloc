import { useState } from 'react'
import { useCollapsiblePanel } from '../../../../controls/collapsible-panel/useCollapsiblePanel'
import Dialog from '../../../../controls/dialog/Dialog'
import { DiagramEndHandle } from '../../../../controls/diagram/DiagramEdge'
import DiagramPalette from '../../../../controls/diagram/DiagramPalette'
import { usePipelineForItemType } from '../../../../global/model/PipelineModel'
import MarkerPipelineInspector from './MarkerPipelineInspector'
import { useMarkerPipelineViewModel } from './MarkerPipelineViewModel'
import { STRIP_WIDTH, bodyMidY, chipLayout, selectRowLayout, signalRowLayout, signalConditionLayout, filterPortLayout, conditionalPortLayout, PSEUDO_LABEL_GAP } from './markerPipelineLayout'
import Button from '../../../../controls/button/Button'
import './MarkerPipelineView.css'

const PIN_RING_R = 7
// The ring shown around an empty output pin when it's hovered, to draw a new connection from.
const NEW_CONNECTION_RADIUS = 11
// Start's circle for drawing new connections, at its middle.
const START_CONNECTOR_RADIUS = 6
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
// empty: a carrier output nothing is connected to -- just the ring.
function Pin({ cx, cy, variant, shape, empty }) {
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
      {!empty && <circle className="mp-pin-fill" cx={cx} cy={cy} r={PIN_FILL_R} />}
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
        {chip.lead && <tspan>{chip.lead} </tspan>}
        {chip.strong ? <tspan className="mp-chip-strong">{chip.text}</tspan> : chip.text}
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

// A node's class: draggable while editing, highlighted while selected, marked while incomplete.
function nodeClassName(editable, selected, invalid) {
  return ['mp-node', editable && 'mp-node-editable', selected && 'mp-node-selected', invalid && 'mp-node-invalid'].filter(Boolean).join(' ')
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
function PseudoStateNode({ rect, kind, editable, selected, invalid, onPointerDown, onPointerMove, onPointerUp }) {
  const cx = rect.x + rect.width / 2
  const cy = rect.y + rect.height / 2
  const r = rect.width / 2
  const isStart = kind !== 'END'
  const labelX = isStart ? rect.x - PSEUDO_LABEL_GAP : rect.x + rect.width + PSEUDO_LABEL_GAP

  return (
    <g
      className={nodeClassName(editable, selected, invalid)}
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
// A conditional block (see conditionalPortLayout) splits its input strip: its input over its "if".
function CarrierBlockNode({ id, rect, label, outputConnected, gateConnected, editable, selected, invalid, onDelete, onContextMenu, onPointerDown, onPointerMove, onPointerUp }) {
  const rightStripX = rect.x + rect.width - STRIP_WIDTH
  const midY = bodyMidY(rect)
  const { centerX, titleY, headerBottom, chips, icons } = chipLayout(rect, label.markerNames, editable)
  const ports = label.conditional ? conditionalPortLayout(rect) : null

  return (
    <g
      className={nodeClassName(editable, selected, invalid)}
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
      <HeaderIcons icons={icons} onEdit={onContextMenu} onDelete={onDelete ?? onContextMenu} />
      {chips.map((chip) => (
        <g key={chip.name}>
          <rect className="mp-marker-chip-rect" x={chip.x} y={chip.y} width={chip.width} height={chip.height} />
          <text className="mp-marker-chip-text" x={chip.x + chip.width / 2} y={chip.y + chip.height / 2 + 4}>
            {chip.name}
          </text>
        </g>
      ))}
      {ports ? (
        <>
          <line className="mp-node-divider" x1={rect.x} y1={ports.dividerY} x2={rect.x + STRIP_WIDTH} y2={ports.dividerY} />
          <Pin cx={ports.input.x} cy={ports.input.y} shape="arrow" />
          <Pin cx={ports.ifPort.x} cy={ports.ifPort.y} variant="signal" empty={!gateConnected(id)} />
        </>
      ) : (
        <Pin cx={rect.x + STRIP_WIDTH / 2} cy={midY} shape="arrow" />
      )}
      <Pin cx={rightStripX + STRIP_WIDTH / 2} cy={midY} empty={!outputConnected(id)} />
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
function FilterNode({ id, rect, label, outputConnected, gateConnected, editable, selected, invalid, onDelete, onContextMenu, onPointerDown, onPointerMove, onPointerUp }) {
  const rightStripX = rect.x + rect.width - STRIP_WIDTH
  const midY = bodyMidY(rect)
  const { centerX, titleY, headerBottom, input, ifPort, icons } = filterPortLayout(rect, editable)

  return (
    <g
      className={nodeClassName(editable, selected, invalid)}
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
      <HeaderIcons icons={icons} onEdit={onContextMenu} onDelete={onDelete ?? onContextMenu} />
      <text className="mp-port-label" x={input.labelX} y={input.labelY}>
        input
      </text>
      <text className="mp-port-label mp-port-label-signal" x={ifPort.labelX} y={ifPort.labelY}>
        if
      </text>
      <Pin cx={input.x} cy={input.y} shape="arrow" />
      <Pin cx={ifPort.x} cy={ifPort.y} variant="signal" empty={!gateConnected(id)} />
      <Pin cx={rightStripX + STRIP_WIDTH / 2} cy={midY} empty={!outputConnected(id)} />
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
function SelectNode({ rect, label, outputConnected, editable, selected, invalid, onDelete, onContextMenu, onPointerDown, onPointerMove, onPointerUp }) {
  const rightStripX = rect.x + rect.width - STRIP_WIDTH
  const midY = bodyMidY(rect)
  const { centerX, titleY, headerBottom, propertyChip, rows, icons } = selectRowLayout(rect, label.rows, label.subjectLabel, editable)

  return (
    <g
      className={nodeClassName(editable, selected, invalid)}
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
      <HeaderIcons icons={icons} onEdit={onContextMenu} onDelete={onDelete ?? onContextMenu} />
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
        <Pin key={row.id} cx={rightStripX + STRIP_WIDTH / 2} cy={row.midY} empty={!outputConnected(row.id)} />
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
function SignalNode({ rect, node, description, pinRead, editable, selected, invalid, onDelete, onContextMenu, onPointerDown, onPointerMove, onPointerUp }) {
  const bodyRight = rect.x + rect.width - STRIP_WIDTH
  const isGroup = node.pinIds.length > 1

  const groupLayout = isGroup ? signalRowLayout(rect, node.pinIds, description, editable) : null
  const conditionLayout = isGroup ? null : signalConditionLayout(rect, description, editable)
  const { centerX, titleY, headerBottom, icons } = groupLayout ?? conditionLayout

  return (
    <g
      className={nodeClassName(editable, selected, invalid)}
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
      <HeaderIcons icons={icons} onEdit={onContextMenu} onDelete={onDelete ?? onContextMenu} />
      {isGroup ? (
        <>
          {groupLayout.propertyChip && <TextChip chip={groupLayout.propertyChip} />}
          {groupLayout.rows.slice(1).map((row) => (
            <line key={`div-${row.id}`} className="mp-signal-node-divider" x1={rect.x} y1={row.top} x2={bodyRight} y2={row.top} />
          ))}
          {groupLayout.rows.map((row) => (
            <g key={row.id}>
              {row.labelChip && <TextChip chip={row.labelChip} />}
              <Pin cx={bodyRight + STRIP_WIDTH / 2} cy={row.midY} variant="signal" empty={!pinRead(row.id)} />
            </g>
          ))}
        </>
      ) : (
        <>
          {conditionLayout.subjectChip && <TextChip chip={conditionLayout.subjectChip} />}
          {conditionLayout.testChip && <TextChip chip={conditionLayout.testChip} />}
          <PredicateBox box={conditionLayout.box} />
          <Pin cx={bodyRight + STRIP_WIDTH / 2} cy={conditionLayout.pinY} variant="signal" empty={!pinRead(node.pinIds[0])} />
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

// A connection, carrier or condition: drawn highlighted while selected or dragged.
function Connection({ edge, signal, highlighted }) {
  const Edge = signal ? SignalReferenceEdge : PipelineEdge
  return (
    <g className="mp-connection">
      <Edge edge={edge} isDragging={highlighted} />
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
// restraint StateMachineDiagramView's own comment describes. `vm` is its view model
// (useMarkerPipelineViewModel): read-only on the Access Markers tab; in the editor dialog, editable
// in the sense of repositioning nodes and edge bends -- no editing the pipeline's own structure yet.
// No name/title shown -- a pipeline has none; it's identified by its item type.
export function MarkerPipelineDiagram({ vm }) {
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
    isNodeSelected,
    isEdgeSelected,
    onEdgePointerDown,
    onEdgeEndPointerDown,
    endDrag,
    endDragPoints,
    isOutputConnected,
    isSignalPinRead,
    isGateConnected,
    signalPinStarts,
    onSignalPinPointerDown,
    newConnectionIsSignal,
    dragEndDot,
    emptyOutputPins,
    startConnector,
    onStartConnectorPointerDown,
    onOutputPinPointerDown,
    newConnectionPoints,
    pipeline,
    problems,
    removeNode,
    onNodePointerDown,
    onNodePointerMove,
    onNodePointerUp,
    onEdgeHandlePointerDown,
    onEdgeHandlePointerMove,
    onEdgeHandlePointerUp,
    draggingEdgeKey,
  } = vm

  // An SVG viewBox is an infinite-canvas window into the diagram's fixed coordinate space: (x, y)
  // is its top-left corner, (width/zoom, height/zoom) is how much of the diagram it shows. Panning
  // moves (x, y); zooming shrinks/grows the window. The SVG itself always stays at a fixed 100%/100%
  // of the container (see the stylesheet) -- nothing about its own size ever changes, only what
  // portion of the diagram it's looking at.
  const viewBox = view ? `${view.x} ${view.y} ${view.width / view.zoom} ${view.height / view.zoom}` : undefined

  // At most one of these is ever set -- draggingEdgeKey names exactly one edge, which is either a
  // carrier edge or a signal one, never both -- used to draw that one edge last (see below).
  const selectedEdge = [...layout.edges, ...layout.signalEdges].find((edge) => isEdgeSelected(edge.key))
  const isHighlighted = (edge) => edge.key === draggingEdgeKey || isEdgeSelected(edge.key)

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
          {/* While editing, each connection's grab area: wide, invisible, along its whole length --
              drawn under the nodes, so a press on a node (Start, where its connections begin, or a
              pin, where they end) is the node's. */}
          {isEditing && (
            <g>
              {[...layout.edges, ...layout.signalEdges].map((edge) => (
                <path key={edge.key} className="mp-edge-hit" d={edgePathD(edge.points)} onPointerDown={(event) => onEdgePointerDown(event, edge.key)} />
              ))}
            </g>
          )}
          {/* Nodes first, edges next, each edge's own bend handle last (edit mode only): a connector
              is always fully visible crossing over a block or a pseudostate's own shape, never the
              other way around, and a handle is always reachable for dragging even where an edge
              crosses close by it. */}
          <g>
            <PseudoStateNode
              rect={layout.rects.get(start.id)}
              kind="START"
              editable={isEditing}
              selected={isNodeSelected(start.id)}
              onPointerDown={(event) => onNodePointerDown(event, start.id)}
              onPointerMove={onNodePointerMove}
              onPointerUp={onNodePointerUp}
            />
            {pipeline.carrierBlocks.map((block) => {
              const label = blocks.get(block.id)
              const Node = nodeComponentFor(label)
              return (
                <Node
                  key={block.id}
                  id={block.id}
                  outputConnected={isOutputConnected}
                  gateConnected={isGateConnected}
                  rect={layout.rects.get(block.id)}
                  label={label}
                  editable={isEditing}
                  selected={isNodeSelected(block.id)}
                  invalid={problems.has(block.id)}
                  onDelete={() => removeNode(block.id)}
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
              selected={isNodeSelected(end.id)}
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
                pinRead={isSignalPinRead}
                editable={isEditing}
                selected={isNodeSelected(node.id)}
                invalid={problems.has(node.id)}
                onDelete={() => removeNode(node.id)}
                onContextMenu={(event) => onBlockContextMenu(event, node.id)}
                onPointerDown={(event) => onNodePointerDown(event, node.id)}
                onPointerMove={onNodePointerMove}
                onPointerUp={onNodePointerUp}
              />
            ))}
          </g>
          <g>
            {/* The selected (or dragged) connection is drawn last -- SVG has no z-index, only draw
                order, so it's on top of any it crosses. One whose end is being dragged is drawn to
                where that end is now. */}
            {[...layout.edges.map((edge) => ({ edge, signal: false })), ...layout.signalEdges.map((edge) => ({ edge, signal: true }))]
              .sort((a, b) => Number(isHighlighted(a.edge)) - Number(isHighlighted(b.edge)))
              .map(({ edge, signal }) => (
                <Connection
                  key={edge.key}
                  edge={endDrag?.key === edge.key && endDragPoints ? { ...edge, points: endDragPoints } : edge}
                  signal={signal}
                  highlighted={isHighlighted(edge)}
                />
              ))}
          </g>
          {newConnectionPoints &&
            (newConnectionIsSignal ? (
              <SignalReferenceEdge edge={{ points: newConnectionPoints }} isDragging />
            ) : (
              <PipelineEdge edge={{ points: newConnectionPoints }} isDragging />
            ))}
          {dragEndDot && (
            <g className={dragEndDot.signal ? 'mp-pin mp-pin-signal' : 'mp-pin'}>
              <circle className="mp-pin-fill" cx={dragEndDot.x} cy={dragEndDot.y} r={PIN_FILL_R} />
            </g>
          )}
          {/* Start's circle for drawing another connection (see the view model's startConnector). */}
          {startConnector && (
            <circle
              className={startConnector.shown ? 'mp-start-connector mp-start-connector-shown' : 'mp-start-connector'}
              cx={startConnector.x}
              cy={startConnector.y}
              r={START_CONNECTOR_RADIUS}
              onPointerDown={onStartConnectorPointerDown}
            />
          )}
          {/* An empty output pin, while editing: hovered, it shows where a new connection starts. */}
          {emptyOutputPins.map(([id, point]) => (
            <circle
              key={id}
              className="mp-pin-start"
              cx={point.x}
              cy={point.y}
              r={NEW_CONNECTION_RADIUS}
              onPointerDown={(event) => onOutputPinPointerDown(event, id)}
            />
          ))}
          {/* The same for a condition's pin, connected or not. */}
          {signalPinStarts.map(([id, point]) => (
            <circle
              key={id}
              className="mp-pin-start"
              cx={point.x}
              cy={point.y}
              r={NEW_CONNECTION_RADIUS}
              onPointerDown={(event) => onSignalPinPointerDown(event, id)}
            />
          ))}
          {isEditing && selectedEdge && !endDrag && (
            <g>
              <EdgeHandle
                handle={selectedEdge.handle}
                onPointerDown={(event) => onEdgeHandlePointerDown(event, selectedEdge.key, selectedEdge.handle)}
                onPointerMove={onEdgeHandlePointerMove}
                onPointerUp={onEdgeHandlePointerUp}
              />
              <DiagramEndHandle
                x={selectedEdge.points[0].x}
                y={selectedEdge.points[0].y}
                onPointerDown={(event) => onEdgeEndPointerDown(event, selectedEdge, 'from')}
              />
              <DiagramEndHandle
                x={selectedEdge.points[selectedEdge.points.length - 1].x}
                y={selectedEdge.points[selectedEdge.points.length - 1].y}
                onPointerDown={(event) => onEdgeEndPointerDown(event, selectedEdge, 'to')}
              />
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
    </div>
  )
}

function nodeComponentFor(label) {
  if (label.isFilter) return FilterNode
  if (label.rows) return SelectNode
  return CarrierBlockNode
}

// Palette icons: a carrier block, and a condition.
const swatch = (className) => (
  <svg aria-hidden="true" viewBox="0 0 16 16" width="16" height="16" className={className}>
    <rect x="2" y="3" width="12" height="10" rx="1" />
  </svg>
)
const ACTION_ICON = (
  <svg aria-hidden="true" viewBox="0 0 20 20" width="20" height="20" className="mp-palette-action">
    <rect x="2" y="4" width="16" height="12" rx="1.5" />
    <path d="M6 10h8M11 7l3 3-3 3" />
  </svg>
)
const CONDITION_ICON = (
  <svg aria-hidden="true" viewBox="0 0 20 20" width="20" height="20" className="mp-palette-condition">
    <path d="M10 2l8 8-8 8-8-8z" />
    <path d="M8 8.5a2 2 0 1 1 2.5 1.9c-.4.2-.5.5-.5.9v.4M10 13.6v.1" />
  </svg>
)

// What the marker decision editor's palette adds: the carrier blocks and conditions it supports.
const PALETTE = [
  {
    id: 'actions',
    label: 'Actions',
    icon: ACTION_ICON,
    items: [
      { id: 'ADD_MARKERS', label: 'Add markers', icon: swatch('mp-palette-swatch-action'), description: 'Gives an item these markers, when its gate allows.' },
      { id: 'REMOVE_MARKERS', label: 'Remove markers', icon: swatch('mp-palette-swatch-action'), description: 'Takes these markers away from an item, when its gate allows.' },
      { id: 'SET_MARKERS', label: 'Set markers', icon: swatch('mp-palette-swatch-action'), description: "Replaces an item's markers with exactly these." },
      { id: 'FILTER', label: 'Filter', icon: swatch('mp-palette-swatch-action'), description: 'Lets items through only when a condition holds.' },
      { id: 'SELECT', label: 'Select', icon: swatch('mp-palette-swatch-action'), description: 'Gives each item the markers of the row whose condition it meets.' },
    ],
  },
  {
    id: 'conditions',
    label: 'Conditions',
    icon: CONDITION_ICON,
    items: [
      { id: 'CONDITION', label: 'Condition', icon: swatch('mp-palette-swatch-condition'), description: "One test, on a property of the item or of something it's linked to." },
      { id: 'CONDITION_GROUP', label: 'Condition group', icon: swatch('mp-palette-swatch-condition'), description: 'Several tests on one property, each with its own outcome -- true and false, for example.' },
      { id: 'COUNTED', label: 'Counting condition', icon: swatch('mp-palette-swatch-condition'), description: 'Counts the linked items that pass a test, and compares the count.' },
      { id: 'TOTALITY', label: 'All / any / none of', icon: swatch('mp-palette-swatch-condition'), description: 'Combines several tests into one: all of them, any of them, or none.' },
    ],
  },
]

function ReadOnlyDiagram({ pipelineView, itemTypeId, initialView, onViewChange }) {
  const vm = useMarkerPipelineViewModel(pipelineView, itemTypeId, null, initialView, onViewChange)
  return <MarkerPipelineDiagram vm={vm} />
}

// Editing the pipeline in a dialog over the tab, like the state machine editor: arranging its diagram,
// and adding and deleting blocks from the palette, with the selected block's details beside it. With
// no changes, Close (or Escape) closes it; with changes, Save keeps them and Cancel drops them --
// Escape doesn't, so they can't be lost by a stray key.
const IGNORE = () => {}

function MarkerPipelineEditDialog({ pipelineView, itemTypeId, itemTypeName, savePipeline, onClose }) {
  const vm = useMarkerPipelineViewModel(pipelineView, itemTypeId, savePipeline, null, undefined, { editable: true })
  const inspector = useCollapsiblePanel(true)

  async function save() {
    if (await vm.saveEditing()) onClose()
  }

  return (
    <Dialog
      title={
        <>
          Edit marker decision <strong>{itemTypeName}</strong>
        </>
      }
      size="large"
      onCancel={vm.hasChanges ? IGNORE : onClose}
      actions={
        vm.hasChanges ? (
          <>
            {vm.saveError && <span className="mp-edit-error">{vm.saveError}</span>}
            {!vm.saveError && vm.saveBlocker && <span className="mp-edit-note">{vm.saveBlocker}</span>}
            <Button variant="ghost-secondary" icon="x" onClick={onClose} disabled={vm.isSaving}>
              Cancel
            </Button>
            <Button variant="ghost-secondary" icon="check" onClick={save} disabled={vm.isSaving || vm.saveBlocker !== null}>
              {vm.isSaving ? 'Saving…' : 'Save'}
            </Button>
          </>
        ) : (
          <Button variant="ghost-secondary" icon="x" onClick={onClose}>
            Close
          </Button>
        )
      }
    >
      <marker-pipeline-view className="marker-pipeline-editing">
        <diagram-editor-body>
          <DiagramPalette categories={PALETTE} targetRef={vm.containerRef} onPlace={(item, at) => vm.placeNode(item.id, at)} />
          <MarkerPipelineDiagram vm={vm} />
          <MarkerPipelineInspector pipeline={vm.pipeline} vm={vm} panel={inspector} />
        </diagram-editor-body>
      </marker-pipeline-view>
    </Dialog>
  )
}

// The item type's pipeline, read-only, with an Edit button opening it in MarkerPipelineEditDialog.
function MarkerPipelineView({ itemTypeId, itemTypeName, initialView, onViewChange }) {
  const { status, pipeline, savePipeline } = usePipelineForItemType(itemTypeId)
  const [editing, setEditing] = useState(false)

  if (status === 'loading') return null
  if (!pipeline) {
    return <p className="status">No marker decision pipeline defined.</p>
  }

  return (
    <marker-pipeline-view>
      <marker-pipeline-toolbar>
        <Button variant="ghost-secondary" icon="edit" onClick={() => setEditing(true)}>
          Edit
        </Button>
      </marker-pipeline-toolbar>
      <ReadOnlyDiagram pipelineView={pipeline} itemTypeId={itemTypeId} initialView={initialView} onViewChange={onViewChange} />
      {editing && (
        <MarkerPipelineEditDialog
          pipelineView={pipeline}
          itemTypeId={itemTypeId}
          itemTypeName={itemTypeName}
          savePipeline={savePipeline}
          onClose={() => setEditing(false)}
        />
      )}
    </marker-pipeline-view>
  )
}

export default MarkerPipelineView
