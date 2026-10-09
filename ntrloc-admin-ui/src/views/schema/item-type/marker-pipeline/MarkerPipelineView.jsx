import { usePipelineForItemType } from '../../../../global/model/PipelineModel'
import { useMarkerPipelineViewModel } from './MarkerPipelineViewModel'
import { STRIP_WIDTH, bodyMidY, chipLayout } from './markerPipelineLayout'
import './MarkerPipelineView.css'

const PIN_RING_R = 7
const PIN_FILL_R = 3.5

// A pin: ring + dot, the same bullseye motif as End, scaled down -- see MarkerPipelineView.css.
function Pin({ cx, cy }) {
  return (
    <g className="mp-pin">
      <circle className="mp-pin-ring" cx={cx} cy={cy} r={PIN_RING_R} />
      <circle className="mp-pin-fill" cx={cx} cy={cy} r={PIN_FILL_R} />
    </g>
  )
}

// START = a plain filled dot (the standard UML initial-pseudostate convention); END = a ring around
// a filled dot. Same shape family as a pin, just bigger -- pin < Start < End. Start previously had a
// notch bitten out toward its outgoing edge, but a notch is a fixed direction while an edge's actual
// angle depends on where the connected block ends up (rank/sibling layout, or a saved position later)
// -- the two didn't reliably line up, which read as a stray triangle rather than an opening. Plain
// and unambiguous beats a decoration that can't stay aligned with what it's decorating.
function PseudoStateNode({ rect, kind }) {
  const cx = rect.x + rect.width / 2
  const cy = rect.y + rect.height / 2
  const r = rect.width / 2

  return (
    <g className="mp-node">
      {kind === 'END' && <circle className="mp-pseudo-ring" cx={cx} cy={cy} r={r} />}
      <circle className="mp-pseudo-fill" cx={cx} cy={cy} r={kind === 'END' ? r * 0.55 : r} />
    </g>
  )
}

// A block is a full-width header band (its "<add>"-style title, divided from the rest by a
// full-width rule) over a body band. The body's single output port always gets its own divided strip
// on the right, unlabeled -- there's only ever one. The input side differs: a block with exactly one
// input port gets the same treatment, symmetric, on the left (e.g. <add>/<set>/<remove>); a block
// with several distinct input ports (e.g. <filter>'s carrier "input" + its "if" gate reference) gets
// no left strip at all -- each of those ports is instead an inline labeled row in the body, since
// they need individual labels a shared strip has no room for. Only <add>/<remove>/<set> are rendered
// today (single input, single output, no ports to label) -- <filter>'s inline-row shape is sized for
// but not exercised yet, since our one real pipeline has no gates.
function CarrierBlockNode({ rect, label, onContextMenu }) {
  const rightStripX = rect.x + rect.width - STRIP_WIDTH
  const midY = bodyMidY(rect)
  const { centerX, titleY, headerBottom, chips } = chipLayout(rect, label.markerNames)

  return (
    <g className="mp-node" onContextMenu={onContextMenu}>
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
      <Pin cx={rect.x + STRIP_WIDTH / 2} cy={midY} />
      <Pin cx={rightStripX + STRIP_WIDTH / 2} cy={midY} />
    </g>
  )
}

// Carrier-flow edges are drawn as plain straight lines, pin center to pin center -- no orthogonal
// routing, no arrowhead (see MarkerPipelineView.css's own comment on why an arrowhead would be
// redundant here).
function PipelineEdge({ edge }) {
  return <path className="mp-edge-path" d={`M ${edge.from.x} ${edge.from.y} L ${edge.to.x} ${edge.to.y}`} />
}

// Read-only diagram for the item type's one pipeline -- shape only, same deliberate restraint
// StateMachineDiagramView's own comment describes (no gate/condition detail surfaced here beyond
// what's already structurally visible; that's what opening the pipeline in its own editor will be
// for, once one exists). No click-to-select yet either, for the same reason: nothing built yet to
// select *for*. No name/title shown -- a pipeline has none; it's identified by its item type, which
// is already this whole tab's own heading.
function MarkerPipelineDiagram({ pipelineView, itemTypeId }) {
  const {
    layout,
    blocks,
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
  } = useMarkerPipelineViewModel(pipelineView, itemTypeId)

  // An SVG viewBox is an infinite-canvas window into the diagram's fixed coordinate space: (x, y)
  // is its top-left corner, (width/zoom, height/zoom) is how much of the diagram it shows. Panning
  // moves (x, y); zooming shrinks/grows the window. The SVG itself always stays at a fixed 100%/100%
  // of the container (see the stylesheet) -- nothing about its own size ever changes, only what
  // portion of the diagram it's looking at.
  const viewBox = view ? `${view.x} ${view.y} ${view.width / view.zoom} ${view.height / view.zoom}` : undefined

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
          {/* Nodes first, edges last: a connector is always fully visible crossing over a block or a
              pseudostate's own shape, never the other way around. */}
          <g>
            <PseudoStateNode rect={layout.rects.get(start.id)} kind="START" />
            {pipelineView.pipeline.carrierBlocks.map((block) => (
              <CarrierBlockNode
                key={block.id}
                rect={layout.rects.get(block.id)}
                label={blocks.get(block.id)}
                onContextMenu={(event) => onBlockContextMenu(event, block.id)}
              />
            ))}
            <PseudoStateNode rect={layout.rects.get(end.id)} kind="END" />
          </g>
          <g>
            {layout.edges.map((edge) => (
              <PipelineEdge key={edge.key} edge={edge} />
            ))}
          </g>
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

function MarkerPipelineView({ itemTypeId }) {
  const { status, pipeline } = usePipelineForItemType(itemTypeId)

  if (status === 'loading') return null
  if (!pipeline) {
    return <p className="status">No marker decision pipeline defined.</p>
  }

  return (
    <marker-pipeline-view>
      <MarkerPipelineDiagram pipelineView={pipeline} itemTypeId={itemTypeId} />
    </marker-pipeline-view>
  )
}

export default MarkerPipelineView
