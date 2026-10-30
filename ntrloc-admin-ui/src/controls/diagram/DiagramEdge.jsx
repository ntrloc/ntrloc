import DiagramIconButton from './DiagramIconButton'
import { edgeLabelSize } from './diagramText'

const CORNER_RADIUS = 6
const ARROW_LENGTH = 9
const ARROW_HALF_WIDTH = 4.5
const END_HANDLE_RADIUS = 4.5

// The points without repeats -- a straight line still has its unused corners, at the same spot.
function distinct(points) {
  return points.filter((p, i) => i === 0 || p.x !== points[i - 1].x || p.y !== points[i - 1].y)
}

// A path through `points` with its corners rounded, each radius held to half the shorter of the two
// segments meeting there.
function roundedPath(points) {
  let d = `M ${points[0].x} ${points[0].y} `
  for (let i = 1; i < points.length - 1; i++) {
    const prev = points[i - 1]
    const curr = points[i]
    const next = points[i + 1]
    const inLen = Math.hypot(curr.x - prev.x, curr.y - prev.y)
    const outLen = Math.hypot(next.x - curr.x, next.y - curr.y)
    const r = Math.min(CORNER_RADIUS, inLen / 2, outLen / 2)
    const before = { x: curr.x - ((curr.x - prev.x) * r) / inLen, y: curr.y - ((curr.y - prev.y) * r) / inLen }
    const after = { x: curr.x + ((next.x - curr.x) * r) / outLen, y: curr.y + ((next.y - curr.y) * r) / outLen }
    d += `L ${before.x} ${before.y} Q ${curr.x} ${curr.y} ${after.x} ${after.y} `
  }
  const last = points[points.length - 1]
  return `${d}L ${last.x} ${last.y}`
}

// The points with the last one pulled back by the arrowhead's length, so the line stops at its base.
function shortenEnd(points) {
  const tip = points[points.length - 1]
  const from = points[points.length - 2]
  const length = Math.hypot(tip.x - from.x, tip.y - from.y)
  if (length <= ARROW_LENGTH) return points
  const t = (length - ARROW_LENGTH) / length
  return [...points.slice(0, -1), { x: from.x + (tip.x - from.x) * t, y: from.y + (tip.y - from.y) * t }]
}

// The arrowhead at the end of the path, pointing along its last segment.
function arrowPoints(points) {
  const tip = points[points.length - 1]
  const from = points[points.length - 2]
  const length = Math.hypot(tip.x - from.x, tip.y - from.y)
  const ux = (tip.x - from.x) / length
  const uy = (tip.y - from.y) / length
  const baseX = tip.x - ux * ARROW_LENGTH
  const baseY = tip.y - uy * ARROW_LENGTH
  return `${tip.x},${tip.y} ${baseX - uy * ARROW_HALF_WIDTH},${baseY + ux * ARROW_HALF_WIDTH} ${baseX + uy * ARROW_HALF_WIDTH},${baseY - ux * ARROW_HALF_WIDTH}`
}

// A selected segment's grip: a short bar across its middle, along the segment.
const GRIP_LENGTH = 14
const GRIP_THICKNESS = 5
const GRIP_MIN_SEGMENT = 24

function SegmentGrip({ segment, onPointerDown }) {
  const length = Math.hypot(segment.b.x - segment.a.x, segment.b.y - segment.a.y)
  if (length < GRIP_MIN_SEGMENT) return null
  const cx = (segment.a.x + segment.b.x) / 2
  const cy = (segment.a.y + segment.b.y) / 2
  // A segment that moves along x runs vertically.
  const width = segment.axis === 'x' ? GRIP_THICKNESS : GRIP_LENGTH
  const height = segment.axis === 'x' ? GRIP_LENGTH : GRIP_THICKNESS
  return (
    <rect
      className={`diagram-edge-segment-grip diagram-edge-grip-${segment.axis}`}
      x={cx - width / 2}
      y={cy - height / 2}
      width={width}
      height={height}
      rx={2}
      onPointerDown={onPointerDown}
    />
  )
}

// One routed edge (from routeEdges): its line, an arrowhead, and an optional label on its middle
// segment.
//
// Given onSegmentPointerDown(event, segment), each segment (and the label, for its segment) can be
// grabbed to drag it sideways; given onEndPointerDown(event, 'from' | 'to'), its ends can be dragged
// round their boxes. `selected` draws it highlighted, with a grip on each segment and a handle on
// each end -- and, given onDelete, a delete button under its label.
function DiagramEdge({ route, label, arrow = true, selected = false, className, onSegmentPointerDown, onEndPointerDown, onDelete }) {
  const points = distinct(route.points)
  const classes = ['diagram-edge', selected && 'diagram-edge-selected', className].filter(Boolean).join(' ')
  const grab = (segment) => onSegmentPointerDown && ((event) => onSegmentPointerDown(event, segment))
  const first = route.points[0]
  const last = route.points[route.points.length - 1]
  return (
    <g className={classes}>
      <path className="diagram-edge-line" d={roundedPath(arrow ? shortenEnd(points) : points)} />
      {arrow && <polygon className="diagram-edge-arrow" points={arrowPoints(points)} />}
      {onSegmentPointerDown &&
        route.segments.map((segment) => (
          <line
            key={segment.index}
            className={`diagram-edge-segment diagram-edge-grip-${segment.axis}`}
            x1={segment.a.x}
            y1={segment.a.y}
            x2={segment.b.x}
            y2={segment.b.y}
            onPointerDown={grab(segment)}
          />
        ))}
      {label && (
        <g
          className={onSegmentPointerDown ? `diagram-edge-grip-${route.label.segment.axis}` : undefined}
          onPointerDown={grab(route.label.segment)}
        >
          <EdgeLabel text={label} x={route.label.x} y={route.label.y} />
        </g>
      )}
      {selected &&
        onSegmentPointerDown &&
        route.segments
          .filter((segment) => !label || segment !== route.label.segment)
          .map((segment) => <SegmentGrip key={segment.index} segment={segment} onPointerDown={grab(segment)} />)}
      {selected && onDelete && (
        <DiagramIconButton
          kind="delete"
          label={label ? `Delete ${label}` : 'Delete'}
          x={route.label.x}
          y={route.label.y + (label ? edgeLabelSize(label).height / 2 + 10 : 12)}
          onClick={onDelete}
        />
      )}
      {selected && onEndPointerDown && (
        <>
          <circle className="diagram-edge-end" cx={first.x} cy={first.y} r={END_HANDLE_RADIUS} onPointerDown={(event) => onEndPointerDown(event, 'from')} />
          <circle className="diagram-edge-end" cx={last.x} cy={last.y} r={END_HANDLE_RADIUS} onPointerDown={(event) => onEndPointerDown(event, 'to')} />
        </>
      )}
    </g>
  )
}

function EdgeLabel({ text, x, y }) {
  const { width, height } = edgeLabelSize(text)
  return (
    <>
      <rect className="diagram-edge-label-bg" x={x - width / 2} y={y - height / 2} width={width} height={height} rx={3} />
      <text className="diagram-edge-label" x={x} y={y}>
        {text}
      </text>
    </>
  )
}

export default DiagramEdge
