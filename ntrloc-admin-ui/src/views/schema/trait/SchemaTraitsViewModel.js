import { useSchemaStatus } from '../../../global/model/SchemaCache'
import { useTraitModel } from '../../../global/model/TraitModel'

export function useSchemaTraitsViewModel() {
  const status = useSchemaStatus()
  const { getTraits } = useTraitModel()
  const traits = [...getTraits()].sort((a, b) => a.name.localeCompare(b.name))

  return { status, traits }
}
