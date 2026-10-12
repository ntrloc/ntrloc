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

// A subject ('linked item: X', 'property: X') or predicate ('= APPROVED', 'any match') chip -- see
// textChip in markerPipelineLayout.js. kind drives which of the two pill styles it renders as (see
// MarkerPipelineView.css): a filled green rounded rect for a subject, an unfilled magenta one for a
// predicate.
function TextChip({ chip }) {
  const rectClass = chip.kind === 'subject' ? 'mp-subject-chip-rect' : 'mp-predicate-chip-rect'
  const textClass = chip.kind === 'subject' ? 'mp-subject-chip-text' : 'mp-predicate-chip-text'
  return (
    <g>
      <rect className={rectClass} x={chip.x} y={chip.y} width={chip.width} height={chip.height} />
      <text className={textClass} x={chip.x + chip.width / 2} y={chip.y + chip.height / 2 + 4}>
        {chip.text}
      </text>
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

// A block is a full-width header band (its "<add>"-style title, divided from the rest by a
// full-width rule) over a body band. The body's single output port always gets its own divided strip
// on the right, unlabeled -- there's only ever one. The input side is the same, symmetric, on the
// left, for a block with exactly one input port (<add>/<set>/<remove>). A block with several
// distinct input ports -- just <filter>, carrier "input" plus its "if" gate reference -- gets its
// own FilterNode below instead, with each port its own labeled row.
function CarrierBlockNode({ rect, label, editable, onContextMenu, onPointerDown, onPointerMove, onPointerUp }) {
  const rightStripX = rect.x + rect.width - STRIP_WIDTH
  const midY = bodyMidY(rect)
  const { centerX, titleY, headerBottom, chips } = chipLayout(rect, label.markerNames)

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
  const { centerX, titleY, headerBottom, input, ifPort } = filterPortLayout(rect)

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
  const { centerX, titleY, headerBottom, propertyChip, rows } = selectRowLayout(rect, label.rows, label.propertyLabel)

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
// Condition (always exactly one pin) is a title over stacked description lines, with an optional
// nested "count" sub-box for a COUNTED predicate (see describeCondition) and one pin centered on the
// whole body.
function SignalNode({ rect, node, description, editable, onPointerDown, onPointerMove, onPointerUp }) {
  const bodyRight = rect.x + rect.width - STRIP_WIDTH
  const isGroup = node.pinIds.length > 1

  const groupLayout = isGroup ? signalRowLayout(rect, node.pinIds, description) : null
  const conditionLayout = isGroup ? null : signalConditionLayout(rect, description)
  const { centerX, titleY, headerBottom } = groupLayout ?? conditionLayout

  return (
    <g
      className={editable ? 'mp-node mp-node-editable' : 'mp-node'}
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
          {conditionLayout.lines.map((chip, i) => (
            <TextChip key={i} chip={chip} />
          ))}
          {conditionLayout.nested && (
            <g>
              <rect
                className="mp-signal-nested-rect"
                x={conditionLayout.nested.rect.x}
                y={conditionLayout.nested.rect.y}
                width={conditionLayout.nested.rect.width}
                height={conditionLayout.nested.rect.height}
              />
              <text className="mp-signal-nested-title" x={conditionLayout.nested.rect.x + conditionLayout.nested.rect.width / 2} y={conditionLayout.nested.titleY}>
                {conditionLayout.nested.title}
              </text>
              {conditionLayout.nested.lines.map((chip, i) => (
                <TextChip key={i} chip={chip} />
              ))}
            </g>
          )}
          <Pin cx={bodyRight + STRIP_WIDTH / 2} cy={conditionLayout.pinY} variant="signal" />
        </>
      )}
    </g>
  )
}

// Every edge is a polyline (see orthogonalPath) that always connects horizontally at both ends --
// turned into a plain "M x,y L x,y L x,y ..." path string, sharp corners, no rounding. Shared by
// both edge kinds below so the two can never draw their own points differently.
function edgePathD(points) {
  return points.map((p, i) => `${i === 0 ? 'M' : 'L'} ${p.x} ${p.y}`).join(' ')
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
function MarkerPipelineDiagram({ pipelineView, itemTypeId, saveLayout }) {
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
  } = useMarkerPipelineViewModel(pipelineView, itemTypeId, saveLayout)

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
        <svg className="marker-pipeline-diagram-svg" viewBox={viewBox}>
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
              const Node = label.isFilter ? FilterNode : label.rows ? SelectNode : CarrierBlockNode
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

function MarkerPipelineView({ itemTypeId }) {
  const { status, pipeline, saveLayout } = usePipelineForItemType(itemTypeId)

  if (status === 'loading') return null
  if (!pipeline) {
    return <p className="status">No marker decision pipeline defined.</p>
  }

  return (
    <marker-pipeline-view>
      <MarkerPipelineDiagram pipelineView={pipeline} itemTypeId={itemTypeId} saveLayout={saveLayout} />
    </marker-pipeline-view>
  )
}

export default MarkerPipelineView
