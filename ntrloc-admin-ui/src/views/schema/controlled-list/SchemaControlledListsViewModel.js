import { useSchemaStatus } from '../../../global/model/SchemaCache'
import { useControlledListModel } from '../../../global/model/ControlledListModel'

// New lists not yet saved (`drafts`) are listed alongside the saved ones. `nameTaken` says whether
// a new list would clash with one already there, saved or not -- ignoring case, as the server does.
export function useSchemaControlledListsViewModel(drafts) {
  const status = useSchemaStatus()
  const { getControlledLists } = useControlledListModel()
  const controlledLists = [...getControlledLists(), ...drafts].sort((a, b) => a.name.localeCompare(b.name))
  const nameTaken = (name) => controlledLists.some((list) => list.name.toLowerCase() === name.trim().toLowerCase())

  return { status, controlledLists, nameTaken }
}
