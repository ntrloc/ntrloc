import { useSchemaModel } from '../../global/model/SchemaModel'

export function useSchemaControlledListsViewModel() {
  const { status, getControlledLists } = useSchemaModel()
  const controlledLists = [...getControlledLists()].sort((a, b) => a.name.localeCompare(b.name))

  return { status, controlledLists }
}
