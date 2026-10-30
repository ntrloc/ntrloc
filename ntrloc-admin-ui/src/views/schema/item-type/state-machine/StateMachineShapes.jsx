import DiagramEdge from '../../../../controls/diagram/DiagramEdge'
import DiagramIconButton from '../../../../controls/diagram/DiagramIconButton'
import { STATE_LINE_HEIGHT } from './stateMachineDiagram'
import './StateMachineShapes.css'

// Start is a filled dot, end a dot in a ring (the UML pseudostates).
function PseudoState({ rect, kind }) {
  const cx = rect.x + rect.width / 2
  const cy = rect.y + rect.height / 2
  const r = rect.width / 2
  return (
    <>
      {kind === 'END' && <circle className="sm-pseudo-ring" cx={cx} cy={cy} r={r} />}
      <circle className="sm-pseudo-fill" cx={cx} cy={cy} r={kind === 'END' ? r * 0.55 : r} />
    </>
  )
}

function NormalState({ rect, lines }) {
  const cx = rect.x + rect.width / 2
  const firstY = rect.y + rect.height / 2 - ((lines.length - 1) * STATE_LINE_HEIGHT) / 2
  return (
    <>
      <rect className="sm-state-rect" x={rect.x} y={rect.y} width={rect.width} height={rect.height} rx={8} />
      <text className="sm-state-label">
        {lines.map((line, i) => (
          <tspan key={line + i} x={cx} y={firstY + i * STATE_LINE_HEIGHT}>
            {line}
          </tspan>
        ))}
      </text>
    </>
  )
}

const isSelected = (selection, kind, id) => selection?.kind === kind && selection.id === id

// A machine's states and transitions, laid out by layoutStateMachine, inside a DiagramCanvas (with
// the sm-diagram class, for its colors). With the pointer-down handlers, states, transition segments
// and transition ends can be dragged. selection: { kind: 'state' | 'transition', id }, drawn
// highlighted -- a selected transition last, so it's on top. targetStateId: the state a dragged
// transition end is on, highlighted too. invalidIds: states and transitions marked as having a
// problem.
//
// While editing, `editing` has the selected things' buttons: stateButtons(state) (where a state's add
// and delete buttons go, null for one it hasn't), onAddPointerDown(event, stateId, point),
// onDeleteState(stateId) and onDeleteTransition(transitionId).
function StateMachineShapes({
  machine,
  layout,
  selection,
  targetStateId,
  invalidIds,
  onStatePointerDown,
  onSegmentPointerDown,
  onEndPointerDown,
  editing,
}) {
  const selectedEdge = (e) => isSelected(selection, 'transition', e.key)
  const selectedState = selection?.kind === 'state' ? machine.states.find((s) => s.id === selection.id) : null
  const buttons = editing && selectedState ? editing.stateButtons(selectedState) : null
  const edges = [...layout.edges.filter((e) => !selectedEdge(e)), ...layout.edges.filter(selectedEdge)]
  return (
    <g className="sm-shapes">
      {machine.states.map((state) => (
        <g
          key={state.id}
          className={[
            onStatePointerDown && 'diagram-node-draggable',
            (isSelected(selection, 'state', state.id) || state.id === targetStateId) && 'sm-state-selected',
            invalidIds?.has(state.id) && 'sm-invalid',
          ]
            .filter(Boolean)
            .join(' ')}
          onPointerDown={onStatePointerDown && ((event) => onStatePointerDown(event, state.id))}
        >
          {state.kind === 'START' || state.kind === 'END' ? (
            <PseudoState rect={layout.rects.get(state.id)} kind={state.kind} />
          ) : (
            <NormalState rect={layout.rects.get(state.id)} lines={layout.boxes.get(state.id).lines} />
          )}
        </g>
      ))}
      {edges.map((edge) => (
        <DiagramEdge
          key={edge.key}
          route={layout.routes.get(edge.key)}
          label={edge.label}
          selected={selectedEdge(edge)}
          className={invalidIds?.has(edge.key) ? 'sm-invalid' : undefined}
          onSegmentPointerDown={onSegmentPointerDown && ((event, segment) => onSegmentPointerDown(event, edge.key, segment))}
          onEndPointerDown={onEndPointerDown && ((event, end) => onEndPointerDown(event, edge.key, end))}
          onDelete={editing && (() => editing.onDeleteTransition(edge.key))}
        />
      ))}
      {buttons?.delete && (
        <DiagramIconButton
          kind="delete"
          label={`Delete ${selectedState.name}`}
          {...buttons.delete}
          onClick={() => editing.onDeleteState(selectedState.id)}
        />
      )}
      {buttons?.add && (
        <DiagramIconButton
          kind="add"
          label="Drag to another state to add a transition"
          {...buttons.add}
          onPointerDown={(event) => editing.onAddPointerDown(event, selectedState.id, buttons.add)}
        />
      )}
    </g>
  )
}

export default StateMachineShapes
