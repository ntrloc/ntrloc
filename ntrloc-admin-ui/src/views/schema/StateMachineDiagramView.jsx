import { useId } from 'react'
import { useStateMachineDiagramViewModel } from './StateMachineDiagramViewModel'
import { wrapText, estimateLabelWidth, NODE_LABEL_FONT_SIZE, NODE_LABEL_PADDING_X, NODE_LABEL_LINE_HEIGHT } from './stateMachineLayout'
import './StateMachineDiagramView.css'

// Renders any edge (direct, rail, or self-loop) from its precomputed point list as a straight-segment
// polyline -- every point list computeLayout produces is already orthogonal (each consecutive pair
// shares an x or a y), so there's no curve-fitting or collision logic left to do here.
function PolylineEdge({ geometry, markerId }) {
  const d = geometry.points.map((p, i) => `${i === 0 ? 'M' : 'L'} ${p.x} ${p.y}`).join(' ')
  return (
    <g className="sm-edge">
      <path className="sm-edge-hit" d={d} />
      <path className="sm-edge-path" d={d} markerEnd={`url(#${markerId})`} />
      {geometry.transition.name && <EdgeLabel text={geometry.transition.name} x={geometry.labelX} y={geometry.labelY} />}
    </g>
  )
}

// Background rect sized off the same string-length estimate computeLayout used to reserve room for
// this label in the first place -- see stateMachineLayout's own estimateLabelWidth comment.
function EdgeLabel({ text, x, y }) {
  const width = estimateLabelWidth(text)
  return (
    <g>
      <rect className="sm-edge-label-bg" x={x - width / 2} y={y - 8} width={width} height={16} />
      <text className="sm-edge-label" x={x} y={y}>
        {text}
      </text>
    </g>
  )
}

// START = a small filled dot; END = a filled dot inside an open ring (UML initial/final pseudostate
// convention). Drawn centered in the same bounding rect the router allocated for a normal node, so
// edge attachment math is unchanged.
function PseudoStateNode({ rect, kind }) {
  const cx = rect.x + rect.width / 2
  const cy = rect.y + rect.height / 2
  const r = Math.min(rect.width, rect.height) / 2
  return (
    <g className="sm-node">
      {kind === 'END' && <circle className="sm-node-pseudo-ring" cx={cx} cy={cy} r={r} />}
      <circle className="sm-node-pseudo-fill" cx={cx} cy={cy} r={kind === 'END' ? r * 0.55 : r} />
    </g>
  )
}

function StateNode({ rect, state }) {
  if (state.kind === 'START' || state.kind === 'END') return <PseudoStateNode rect={rect} kind={state.kind} />

  // Wrapped across lines (rather than left to overflow the box, SVG text's default behavior) so a
  // long name can share the box with a narrower vertical-orientation NODE_WIDTH -- see
  // stateMachineLayout's own comment on why vertical orientation specifically wants this trade.
  const lines = wrapText(state.name, rect.width - 2 * NODE_LABEL_PADDING_X, NODE_LABEL_FONT_SIZE)
  const centerX = rect.x + rect.width / 2
  const startY = rect.y + rect.height / 2 - ((lines.length - 1) * NODE_LABEL_LINE_HEIGHT) / 2

  return (
    <g className="sm-node">
      <rect className="sm-node-rect" x={rect.x} y={rect.y} width={rect.width} height={rect.height} />
      <text className="sm-node-label" x={centerX} y={startY}>
        {lines.map((line, i) => {
          const lineY = startY + i * NODE_LABEL_LINE_HEIGHT
          return (
            <tspan key={lineY} x={centerX} y={lineY}>
              {line}
            </tspan>
          )
        })}
      </text>
    </g>
  )
}

// Auto-laid-out visualization of a state machine -- states as boxes, transitions as labeled arrows,
// shape only. Deliberately shows nothing else (no guard conditions, no entry/exit/transition process
// detail) -- ported from the legacy admin UI's ntrloc-state-machine-diagram.js, which followed the
// same explicit design direction: states/transitions carry more detail than a shape diagram should
// surface; that's what opening a machine in its own editor is for. Read-only for now -- no
// click-to-select, since there's no editor built yet to select *for* (see StateMachineDiagramViewModel
// and stateMachineLayout for the state/geometry layers this renders).
function StateMachineDiagramView({ machine, orientation = 'horizontal' }) {
  const markerId = `sm-arrow-${useId()}`
  const { layout, zoom, scrollRef, onPointerDown, onPointerMove, onPointerUp } = useStateMachineDiagramViewModel(
    machine,
    orientation,
  )

  if (machine.states.length === 0) return <p className="state-machine-diagram-empty">No states defined.</p>

  return (
    <div
      ref={scrollRef}
      className="state-machine-diagram-scroll"
      onPointerDown={onPointerDown}
      onPointerMove={onPointerMove}
      onPointerUp={onPointerUp}
    >
      <svg
        className="state-machine-diagram-svg"
        width={layout.width * zoom}
        height={layout.height * zoom}
        viewBox={`0 0 ${layout.width} ${layout.height}`}
        role="img"
        aria-label={`${machine.name} state machine`}
      >
        <defs>
          <marker id={markerId} viewBox="0 0 10 10" refX="9" refY="5" markerWidth="6" markerHeight="6" orient="auto-start-reverse">
            <path className="sm-arrowhead" d="M 0 0 L 10 5 L 0 10 z" />
          </marker>
        </defs>
        {/* Edges drawn before nodes, in document order, so each opaque node box paints over the stub
            of every line running into it -- lines only need to be geometrically correct up to the
            node's boundary (already true by construction), not carefully clipped here. */}
        <g>
          {[...layout.selfLoopEdges, ...layout.edges].map((geometry, i) => (
            <PolylineEdge key={geometry.transition.id ?? i} geometry={geometry} markerId={markerId} />
          ))}
        </g>
        <g>
          {machine.states.map((state) => (
            <StateNode key={state.id} rect={layout.rects.get(state.id)} state={state} />
          ))}
        </g>
      </svg>
    </div>
  )
}

export default StateMachineDiagramView
