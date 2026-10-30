import { useSchemaSnapshot } from './SchemaCache'

// A machine as the admin UI draws and inspects it: its states and transitions with their details
// (processes, guard), and its saved diagram layout.
function toItemTypeStateMachines(adminSchema, itemTypeId) {
  const item = adminSchema.items.find((it) => it.id === itemTypeId)
  if (!item?.stateMachines) return []

  return item.stateMachines.map((machine) => ({
    id: machine.id,
    name: machine.name,
    description: machine.description,
    layout: machine.layout ?? null,
    states: machine.states.map((state) => ({
      id: state.id,
      name: state.name,
      kind: state.kind,
      description: state.description,
      entryProcessId: state.entryProcessId,
      exitProcessId: state.exitProcessId,
      transitions: (state.transitions ?? []).map((transition) => ({
        id: transition.id,
        toStateId: transition.toStateId,
        toStateName: transition.toStateName,
        name: transition.name,
        description: transition.description,
        processId: transition.processId,
        guardCondition: transition.guardCondition,
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
