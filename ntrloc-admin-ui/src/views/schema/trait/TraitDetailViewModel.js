import { useSchemaModel } from '../../../global/model/SchemaModel'

export function useTraitDetailViewModel(traitId) {
  const { getTrait } = useSchemaModel()

  return { trait: getTrait(traitId) }
}
