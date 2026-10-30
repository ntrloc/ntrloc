import { layoutToSave, savedLayout } from './stateMachineDiagram'
import { editedFields, editMutations, transitionEnds } from './stateMachineEdits'

// Everything the state machine editor has changed, as one value, so each change is a step to undo.
// Every function here returns a new draft, leaving the one it's given alone.
//
// - name, description: the machine's own.
// - positions, edgeLayouts: the diagram (see layoutStateMachine).
// - edits: states' and transitions' names and descriptions (see stateMachineEdits).
// - newStates, newTransitions: added, under refIds (new-state-N, new-transition-N) until saving
//   creates them. A new transition is { id, fromId, toStateId, name, ... }.
// - endpoints: transitions moved to other states, Map<transition id, { from?, to? }>, only where they
//   differ from what's saved (or, for a new one, from where it was drawn).
// - deletedIds: saved states and transitions deleted.
// - nextRef: the number for the next refId.
export function initialDraft(machine) {
  const { positions, edgeLayouts } = savedLayout(machine)
  return {
    name: machine.name,
    description: machine.description ?? '',
    positions,
    edgeLayouts,
    edits: new Map(),
    newStates: [],
    newTransitions: [],
    endpoints: new Map(),
    deletedIds: new Set(),
    nextRef: 1,
  }
}

// The machine as the draft has it: deletions gone, additions in (a new transition in its from-state's
// list), names not yet applied (see applyEdits).
export function draftMachine(machine, draft) {
  const { deletedIds, newStates, newTransitions } = draft
  if (deletedIds.size === 0 && newStates.length === 0 && newTransitions.length === 0) return machine
  return {
    ...machine,
    states: [...machine.states, ...newStates]
      .filter((s) => !deletedIds.has(s.id))
      .map((s) => ({
        ...s,
        transitions: [...s.transitions, ...newTransitions.filter((t) => t.fromId === s.id)].filter((t) => !deletedIds.has(t.id)),
      })),
  }
}

const withMap = (map, key, value) => new Map(map).set(key, value)

export const setMachineField = (draft, field, value) => ({ ...draft, [field]: value })

export const editField = (draft, thing, field, value) => ({
  ...draft,
  edits: withMap(draft.edits, thing.id, { ...editedFields(draft.edits, thing), [field]: value }),
})

export const moveState = (draft, stateId, position) => ({ ...draft, positions: withMap(draft.positions, stateId, position) })

export const setEdgeLayout = (draft, edgeKey, lineLayout) => ({ ...draft, edgeLayouts: withMap(draft.edgeLayouts, edgeKey, lineLayout) })

// A transition with one end moved to stateId, its line laid out afresh (lineLayout).
export function moveTransitionEnd(draft, machine, edgeKey, end, stateId, lineLayout) {
  const original = draftMachine(machine, { ...draft, deletedIds: new Set() }).states.find((s) =>
    s.transitions.some((t) => t.id === edgeKey),
  )
  const drawn = { from: original.id, to: original.transitions.find((t) => t.id === edgeKey).toStateId }
  const moved = { ...draft.endpoints.get(edgeKey), [end]: stateId }
  for (const key of ['from', 'to']) if (moved[key] === drawn[key]) delete moved[key]
  const endpoints = new Map(draft.endpoints)
  if (Object.keys(moved).length === 0) endpoints.delete(edgeKey)
  else endpoints.set(edgeKey, moved)
  return { ...setEdgeLayout(draft, edgeKey, lineLayout), endpoints }
}

export function addState(draft, fields, position) {
  const state = {
    id: `new-state-${draft.nextRef}`,
    kind: 'NORMAL',
    description: '',
    entryProcessId: null,
    exitProcessId: null,
    entryMarkerDecisionKey: null,
    transitions: [],
    ...fields,
  }
  return { ...draft, newStates: [...draft.newStates, state], positions: withMap(draft.positions, state.id, position), nextRef: draft.nextRef + 1 }
}

export function addTransition(draft, fromId, toStateId, name, lineLayout) {
  const id = `new-transition-${draft.nextRef}`
  const transition = { id, fromId, toStateId, name, description: '', processId: null, guardCondition: null }
  return {
    ...draft,
    newTransitions: [...draft.newTransitions, transition],
    edgeLayouts: withMap(draft.edgeLayouts, id, lineLayout),
    nextRef: draft.nextRef + 1,
  }
}

const isNew = (id) => id.startsWith('new-')

function withoutIds(draft, ids) {
  const deletedIds = new Set(draft.deletedIds)
  for (const id of ids) if (!isNew(id)) deletedIds.add(id)
  return {
    ...draft,
    newStates: draft.newStates.filter((s) => !ids.includes(s.id)),
    newTransitions: draft.newTransitions.filter((t) => !ids.includes(t.id)),
    deletedIds,
  }
}

export const deleteTransition = (draft, transitionId) => withoutIds(draft, [transitionId])

// A state goes with every transition into or out of it.
export function deleteState(draft, machine, stateId) {
  const ends = transitionEnds(draftMachine(machine, draft), draft.endpoints)
  const touching = ends.filter((e) => e.fromId === stateId || e.toId === stateId).map((e) => e.key)
  return withoutIds(draft, [stateId, ...touching])
}

// The batch saving the draft takes, in an order the server can apply: deletions first (freeing names
// and state pairs), state updates, new states, transition updates and new transitions (which can lead
// to new states), then the diagram.
export function saveMutations(machine, draft, rects) {
  const current = draftMachine(machine, draft)
  const { deletedIds, edits, endpoints } = draft
  const savedTransitions = machine.states.flatMap((s) => s.transitions)
  const savedRemaining = draftMachine(machine, { ...draft, newStates: [], newTransitions: [] })
  const { states: stateUpdates, transitions: transitionUpdates } = editMutations(savedRemaining, edits, endpoints)
  const ends = new Map(transitionEnds(current, endpoints).map((e) => [e.key, e]))
  const renamed = draft.name.trim() !== machine.name || draft.description.trim() !== (machine.description ?? '')
  return [
    ...(renamed
      ? [{ type: 'UPDATE_STATE_MACHINE', id: machine.id, name: draft.name.trim(), description: draft.description.trim() || null }]
      : []),
    ...savedTransitions.filter((t) => deletedIds.has(t.id)).map((t) => ({ type: 'DELETE_TRANSITION', id: t.id })),
    ...machine.states.filter((s) => deletedIds.has(s.id)).map((s) => ({ type: 'DELETE_STATE', id: s.id })),
    ...stateUpdates,
    ...draft.newStates.map((state) => {
      const fields = editedFields(edits, state)
      return { type: 'CREATE_STATE', refId: state.id, stateMachineId: machine.id, name: fields.name.trim(), description: fields.description.trim() || null }
    }),
    ...transitionUpdates,
    ...draft.newTransitions.map((transition) => {
      const fields = editedFields(edits, transition)
      const { fromId, toId } = ends.get(transition.id)
      return {
        type: 'CREATE_TRANSITION',
        refId: transition.id,
        fromState: fromId,
        toState: toId,
        name: fields.name.trim(),
        description: fields.description.trim() || null,
      }
    }),
    { type: 'SET_STATE_MACHINE_LAYOUT', stateMachineId: machine.id, layout: layoutToSave(current, rects, draft.edgeLayouts) },
  ]
}
