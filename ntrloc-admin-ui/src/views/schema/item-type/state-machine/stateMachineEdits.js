// Pending edits to a machine's states and transitions: Map<state or transition id, { name,
// description }>, the fields as typed.

const RESERVED_STATE_NAMES = new Set(['__start__', '__end__'])

const fieldsOf = (thing) => ({ name: thing.name, description: thing.description ?? '' })

// What an editor shows for a state or transition: its pending edits, or its saved fields.
export function editedFields(edits, thing) {
  return edits.get(thing.id) ?? fieldsOf(thing)
}

// The machine as edited -- for the diagram, whose boxes and labels follow the names as they're typed.
export function applyEdits(machine, edits) {
  if (edits.size === 0) return machine
  return {
    ...machine,
    states: machine.states.map((state) => ({
      ...state,
      ...edits.get(state.id),
      transitions: state.transitions.map((t) => ({ ...t, ...edits.get(t.id) })),
    })),
  }
}

// What's wrong with each edited name, by id: a state's must be filled in, not one of the reserved
// start/end names, and not another state's; a transition's must be filled in.
export function nameProblems(machine, edits) {
  const problems = new Map()
  const edited = applyEdits(machine, edits)
  const normal = edited.states.filter((s) => s.kind === 'NORMAL')
  const counts = new Map()
  for (const state of normal) counts.set(state.name.trim(), (counts.get(state.name.trim()) ?? 0) + 1)
  for (const state of normal) {
    const name = state.name.trim()
    if (name === '') problems.set(state.id, 'Enter a name.')
    else if (RESERVED_STATE_NAMES.has(name)) problems.set(state.id, 'That name is reserved.')
    else if (counts.get(name) > 1 && edits.has(state.id)) problems.set(state.id, 'Another state has this name.')
  }
  for (const transition of edited.states.flatMap((s) => s.transitions)) {
    if (transition.name.trim() === '') problems.set(transition.id, 'Enter a name.')
  }
  return problems
}

const changed = (saved, edit) => edit.name.trim() !== saved.name || edit.description.trim() !== (saved.description ?? '')

// Each transition's ends: { key, fromId, toId }, with the ones moved in the editor where they've
// been moved to. endpoints: Map<transition id, { from?, to? }>.
export function transitionEnds(machine, endpoints) {
  return machine.states.flatMap((s) =>
    s.transitions.map((t) => ({ key: t.id, fromId: endpoints.get(t.id)?.from ?? s.id, toId: endpoints.get(t.id)?.to ?? t.toStateId })),
  )
}

// Whether one end ('from' or 'to') of a transition may be moved to a state, by the server's rules:
// nothing leads out of the end state or into the start state, or from a state back to itself; the
// start state leads to one state and unguarded; and only one transition leads from a state to
// another. ends: transitionEnds.
export function canMoveEnd(machine, ends, transition, end, stateId) {
  const statesById = new Map(machine.states.map((s) => [s.id, s]))
  const own = ends.find((e) => e.key === transition.id)
  const fromId = end === 'from' ? stateId : own.fromId
  const toId = end === 'to' ? stateId : own.toId
  const from = statesById.get(fromId)
  const to = statesById.get(toId)
  if (!from || !to || fromId === toId || from.kind === 'END' || to.kind === 'START') return false
  const others = ends.filter((e) => e.key !== transition.id && e.fromId === fromId)
  if (others.some((e) => e.toId === toId)) return false
  return from.kind !== 'START' || (others.length === 0 && !transition.guardCondition)
}

// The updates saving the edits takes, states' and transitions' apart -- new states have to be
// created between the two, as a moved transition can lead to one. Each update replaces all of a
// state's or transition's fields, so the ones not edited here go along unchanged. A moved transition
// names its new states (ids, or new states' refIds).
export function editMutations(machine, edits, endpoints = new Map()) {
  const states = []
  const transitions = []
  for (const state of machine.states) {
    const edit = edits.get(state.id)
    if (edit && changed(state, edit)) {
      states.push({
        type: 'UPDATE_STATE',
        id: state.id,
        name: edit.name.trim(),
        description: edit.description.trim() || null,
        entryProcessId: state.entryProcessId,
        exitProcessId: state.exitProcessId,
      })
    }
    for (const transition of state.transitions) {
      const fields = editedFields(edits, transition)
      const moved = endpoints.get(transition.id)
      if (moved || changed(transition, fields)) {
        transitions.push({
          type: 'UPDATE_TRANSITION',
          id: transition.id,
          name: fields.name.trim(),
          description: fields.description.trim() || null,
          processId: transition.processId,
          guardCondition: transition.guardCondition,
          fromState: moved?.from ?? null,
          toState: moved?.to ?? null,
        })
      }
    }
  }
  return { states, transitions }
}
