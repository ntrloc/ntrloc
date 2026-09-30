import { useStateMachineModel } from '../../../global/model/StateMachineModel'

// Alphabetical order is this view's own presentation concern -- StateMachineModel's accessor stays
// raw, in whatever order the schema happened to list them.
export function useItemTypeStateMachinesViewModel(itemTypeId) {
  const { getItemTypeStateMachines } = useStateMachineModel()
  const machines = [...getItemTypeStateMachines(itemTypeId)].sort((a, b) => a.name.localeCompare(b.name))

  return { machines }
}
