import { useSchemaSnapshot } from './SchemaCache'

function toControlledLists(adminSchema) {
  return adminSchema.controlledLists.map((list) => ({
    id: list.id,
    name: list.name,
  }))
}

export function useControlledListModel() {
  const schema = useSchemaSnapshot()
  return {
    getControlledLists: () => (schema ? toControlledLists(schema) : []),
  }
}
