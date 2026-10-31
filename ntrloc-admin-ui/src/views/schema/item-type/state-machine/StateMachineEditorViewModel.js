import { useState } from 'react'
import { useCollapsiblePanel } from '../../../../controls/collapsible-panel/useCollapsiblePanel'
import { dragLineEnd } from '../../../../controls/diagram/lineEnds'
import { moveEnd, moveSegment } from '../../../../controls/diagram/orthogonalRoutes'
import { startDiagramDrag } from '../../../../controls/diagram/startDiagramDrag'
import { useDiagramSelection } from '../../../../controls/diagram/useDiagramSelection'
import { useDiagramView } from '../../../../controls/diagram/useDiagramView'
import { applySchemaMutations } from '../../../../global/delegate/SchemaDelegate'
import { reload as reloadSchema } from '../../../../global/model/SchemaCache'
import { useUndoable, useUndoShortcuts } from '../../../../global/useUndoable'
import { layoutStateMachine, STATE_BUTTON_INSET, stateBox } from './stateMachineDiagram'
import * as drafts from './stateMachineDraft'
import { applyEdits, canMoveEnd, editedFields, nameProblems, transitionEnds } from './stateMachineEdits'

const GRID = 8
const snap = (value) => Math.round(value / GRID) * GRID
// Where a transition's end is drawn while it's dragged loose of any state.
const LOOSE_END = 'loose-end'
// The transition being drawn from a state's add button, before it's let go on another.
const PENDING_TRANSITION = 'pending-transition'

// A name not already in `taken`: `base`, or `base 2`, `base 3`...
function distinctName(base, taken) {
  const nameFor = (n) => (n === 1 ? base : `${base} ${n}`)
  let n = 1
  while (taken.has(nameFor(n))) n++
  return nameFor(n)
}

// Editing a machine: its name and description; its states and transitions -- adding, deleting,
// naming and describing them, and moving a transition's ends to other states; and arranging its
// diagram -- dragging states, and the segments and ends of transitions. All of it is a draft (see
// stateMachineDraft) until it's saved, with each change a step to undo (Cmd/Ctrl+Z) or redo
// (Cmd/Ctrl+Shift+Z, or Ctrl+Y). onClose closes the editor, after a save or without one. Pressing a
// state or a transition selects it, shows it in the inspector, and starts dragging it.
export function useStateMachineEditorViewModel(machine, onClose) {
  const inspector = useCollapsiblePanel(true)
  const history = useUndoable(() => drafts.initialDraft(machine))
  const draft = history.present
  // A transition end being dragged: { edgeKey, end, nodeId (null while it's loose), point,
  // lineLayout }, and `pending` while it's a new transition being drawn.
  const [endDrag, setEndDrag] = useState(null)
  const selection = useDiagramSelection()
  const [saving, setSaving] = useState(false)
  const [saveError, setSaveError] = useState(null)

  const shown = endDrag?.pending ? { ...draft, newTransitions: [...draft.newTransitions, endDrag.pending] } : draft
  const edited = applyEdits(drafts.draftMachine(machine, shown), draft.edits)
  const problems = nameProblems(drafts.draftMachine(machine, draft), draft.edits)
  const nameProblem = draft.name.trim() === '' ? 'Enter a name.' : null
  const layout = layoutStateMachine(edited, draft.positions, ...withEndDrag(draft.edgeLayouts, draft.endpoints, endDrag))
  const canvas = useDiagramView(layout.bounds, { interactive: true })

  useUndoShortcuts(history)

  const transitions = () => edited.states.flatMap((s) => s.transitions)

  function addState() {
    if (!canvas.view) return
    const name = distinctName('New state', new Set(edited.states.map((s) => s.name.trim())))
    const { width, height } = stateBox({ kind: 'NORMAL', name })
    const { x, y, zoom } = canvas.view
    const position = { x: snap(x + canvas.view.width / zoom / 2 - width / 2), y: snap(y + canvas.view.height / zoom / 2 - height / 2) }
    history.change((d) => drafts.addState(d, { name }, position))
    selection.select('state', `new-state-${draft.nextRef}`)
  }

  function deleteState(stateId) {
    history.change((d) => drafts.deleteState(d, machine, stateId))
    selection.clear()
  }

  function deleteTransition(transitionId) {
    history.change((d) => drafts.deleteTransition(d, transitionId))
    selection.clear()
  }

  function onStatePointerDown(event, stateId) {
    if (!canvas.view) return
    const start = layout.rects.get(stateId)
    selection.select('state', stateId)
    const gesture = history.startGesture()
    startDiagramDrag(
      event,
      canvas.view.zoom,
      (dx, dy) => gesture.update((d) => drafts.moveState(d, stateId, { x: snap(start.x + dx), y: snap(start.y + dy) })),
      gesture.end,
    )
  }

  function onSegmentPointerDown(event, edgeKey, segment) {
    if (!canvas.view) return
    const route = layout.routes.get(edgeKey)
    const start = segment.a[segment.axis]
    selection.select('transition', edgeKey)
    const gesture = history.startGesture()
    startDiagramDrag(
      event,
      canvas.view.zoom,
      (dx, dy) =>
        gesture.update((d) => drafts.setEdgeLayout(d, edgeKey, moveSegment(route, segment, snap(start + (segment.axis === 'x' ? dx : dy))))),
      gesture.end,
    )
  }

  // Drags a transition's end from `start` (see dragLineEnd), calling onDrop with where it was let go
  // on a state ({ nodeId, lineLayout }) -- not when it's let go loose. attachedId: the state it's on
  // to begin with, if any; canAttach(id): whether it may go to a state; lineLayoutAt(at, point): its
  // line, with the end at `at` (from dragLineEnd).
  function dragTransitionEnd(event, { edgeKey, end, start, attachedId, canAttach, lineLayoutAt, pending, onDrop }) {
    const stateRects = new Map(edited.states.map((s) => [s.id, layout.rects.get(s.id)]))
    let current = { nodeId: attachedId }
    startDiagramDrag(
      event,
      canvas.view.zoom,
      (dx, dy) => {
        const point = { x: start.x + dx, y: start.y + dy }
        const at = dragLineEnd({ point, attachedId: current.nodeId, rects: stateRects, canAttach, zoom: canvas.view.zoom })
        current = { nodeId: at.nodeId, lineLayout: lineLayoutAt(at, point) }
        setEndDrag({ edgeKey, end, nodeId: at.nodeId, point, lineLayout: current.lineLayout, pending })
      },
      () => {
        setEndDrag(null)
        if (current.nodeId && current.lineLayout) onDrop(current)
      },
    )
  }

  // Moving a transition's end: round its state's border while it's near it, then loose, snapping to
  // any state it may be moved to. Let go on a state, it's moved there; let go loose, it goes back.
  function onEndPointerDown(event, edgeKey, end) {
    if (!canvas.view) return
    const route = layout.routes.get(edgeKey)
    const ends = transitionEnds(edited, draft.endpoints)
    const own = ends.find((e) => e.key === edgeKey)
    const originalId = end === 'from' ? own.fromId : own.toId
    const transition = transitions().find((t) => t.id === edgeKey)
    const otherEnd = end === 'from' ? 'to' : 'from'
    selection.select('transition', edgeKey)
    dragTransitionEnd(event, {
      edgeKey,
      end,
      start: end === 'from' ? route.points[0] : route.points[route.points.length - 1],
      attachedId: originalId,
      canAttach: (id) => id === originalId || canMoveEnd(edited, ends, transition, end, id),
      // Back on its own state it keeps its shape; on another, or loose, its segments start afresh.
      lineLayoutAt: (at, point) => (at.nodeId === originalId ? moveEnd(route, end, point) : { [otherEnd]: route.layout[otherEnd], [end]: at.port }),
      onDrop: ({ nodeId, lineLayout }) =>
        history.change((d) =>
          nodeId === originalId
            ? drafts.setEdgeLayout(d, edgeKey, lineLayout)
            : drafts.moveTransitionEnd(d, machine, edgeKey, end, nodeId, lineLayout),
        ),
    })
  }

  // Drawing a new transition from a state's add button (at `start`), to any state it may lead to.
  function onAddPointerDown(event, stateId, start) {
    if (!canvas.view) return
    const pending = { id: PENDING_TRANSITION, fromId: stateId, toStateId: stateId, name: '', description: '', processId: null, guardCondition: null }
    const withPending = applyEdits(drafts.draftMachine(machine, { ...draft, newTransitions: [...draft.newTransitions, pending] }), draft.edits)
    const ends = transitionEnds(withPending, draft.endpoints)
    dragTransitionEnd(event, {
      edgeKey: PENDING_TRANSITION,
      end: 'to',
      start,
      attachedId: null,
      canAttach: (id) => canMoveEnd(withPending, ends, pending, 'to', id),
      lineLayoutAt: (at) => ({ to: at.port }),
      pending,
      onDrop: ({ nodeId, lineLayout }) => {
        const name = distinctName('New transition', new Set(transitions().map((t) => t.name.trim())))
        history.change((d) => drafts.addTransition(d, stateId, nodeId, name, lineLayout))
        selection.select('transition', `new-transition-${draft.nextRef}`)
      },
    })
  }

  // Where a selected state's buttons go: delete on the left (a normal state's), add a transition on
  // the right -- or in the middle, for start, which leads to only one state.
  function stateButtons(state) {
    const rect = layout.rects.get(state.id)
    const midY = rect.y + rect.height / 2
    const outgoing = layout.edges.some((e) => e.fromId === state.id)
    let add = null
    if (state.kind === 'NORMAL') add = { x: rect.x + rect.width - STATE_BUTTON_INSET, y: midY }
    else if (state.kind === 'START' && !outgoing) add = { x: rect.x + rect.width / 2, y: midY }
    return { add, delete: state.kind === 'NORMAL' ? { x: rect.x + STATE_BUTTON_INSET, y: midY } : null }
  }

  async function save() {
    setSaving(true)
    setSaveError(null)
    try {
      await applySchemaMutations(drafts.saveMutations(machine, draft, layout.rects))
      await reloadSchema('after state machine save')
      onClose()
    } catch (error) {
      setSaveError(error.message)
      setSaving(false)
    }
  }

  return {
    name: draft.name,
    setName: (value) => history.change((d) => drafts.setMachineField(d, 'name', value), 'machine:name'),
    description: draft.description,
    setDescription: (value) => history.change((d) => drafts.setMachineField(d, 'description', value), 'machine:description'),
    nameProblem,
    invalid: nameProblem !== null || problems.size > 0,
    draft: drafts.draftMachine(machine, draft),
    edited,
    addState,
    deleteState,
    deleteTransition,
    stateButtons,
    fieldsOf: (thing) => editedFields(draft.edits, thing),
    editField: (thing, field, value) => history.change((d) => drafts.editField(d, thing, field, value), `${thing.id}:${field}`),
    problems,
    inspector,
    layout,
    canvas,
    saving,
    saveError,
    save,
    cancel: onClose,
    // Whether the draft differs from what's saved: undone back to it, it doesn't.
    hasChanges: history.canUndo,
    targetStateId: endDrag?.nodeId ?? null,
    selection: selection.selection,
    clearSelection: selection.clear,
    onStatePointerDown,
    onSegmentPointerDown,
    onEndPointerDown,
    onAddPointerDown,
  }
}

// What the diagram is drawn from while a transition's end is dragged: that transition's line as it's
// being dragged, between the states it's being dragged to -- or to a point, while it's loose.
function withEndDrag(edgeLayouts, endpoints, endDrag) {
  if (!endDrag) return [edgeLayouts, { endpoints }]
  const { edgeKey, end, nodeId, point, lineLayout } = endDrag
  const extraRects = new Map()
  if (!nodeId) extraRects.set(LOOSE_END, { x: point.x, y: point.y, width: 0, height: 0 })
  return [
    new Map(edgeLayouts).set(edgeKey, lineLayout),
    {
      endpoints: new Map(endpoints).set(edgeKey, { ...endpoints.get(edgeKey), [end]: nodeId ?? LOOSE_END }),
      extraRects,
    },
  ]
}
