import { useSchemaSnapshot } from './SchemaCache'

// Shape only -- states/transitions as the diagram needs them (id/name/kind per state, and
// id/toStateId/toStateName/name per transition). Deliberately drops entry/exit process ids and
// guard conditions for now, matching the legacy diagram's own "shape only" design direction (see
// ntrloc-state-machine-diagram.js's own comment) -- showing that extra detail is editor territory,
// a separate, not-yet-built piece of work.
function toItemTypeStateMachines(adminSchema, itemTypeId) {
  const item = adminSchema.items.find((it) => it.id === itemTypeId)
  if (!item?.stateMachines) return []

  return item.stateMachines.map((machine) => ({
    id: machine.id,
    name: machine.name,
    description: machine.description,
    states: machine.states.map((state) => ({
      id: state.id,
      name: state.name,
      kind: state.kind,
      transitions: (state.transitions ?? []).map((transition) => ({
        id: transition.id,
        toStateId: transition.toStateId,
        toStateName: transition.toStateName,
        name: transition.name,
      })),
    })),
  }))
}

export function useStateMachineModel() {
  const schema = useSchemaSnapshot()
  return {
    getItemTypeStateMachines: (itemTypeId) => (schema ? toItemTypeStateMachines(schema, itemTypeId) : []),
  }
}
