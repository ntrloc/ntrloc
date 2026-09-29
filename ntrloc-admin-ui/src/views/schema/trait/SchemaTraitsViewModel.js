import { useSchemaModel } from '../../../global/model/SchemaModel'

export function useSchemaTraitsViewModel() {
  const { status, getTraits } = useSchemaModel()
  const traits = [...getTraits()].sort((a, b) => a.name.localeCompare(b.name))

  return { status, traits }
}
