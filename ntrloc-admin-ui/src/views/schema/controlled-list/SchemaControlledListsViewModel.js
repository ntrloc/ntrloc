import { useSchemaStatus } from '../../../global/model/SchemaCache'
import { useControlledListModel } from '../../../global/model/ControlledListModel'

export function useSchemaControlledListsViewModel() {
  const status = useSchemaStatus()
  const { getControlledLists } = useControlledListModel()
  const controlledLists = [...getControlledLists()].sort((a, b) => a.name.localeCompare(b.name))

  return { status, controlledLists }
}
