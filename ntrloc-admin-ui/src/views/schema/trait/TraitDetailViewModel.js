import { useTraitModel } from '../../../global/model/TraitModel'

export function useTraitDetailViewModel(traitId) {
  const { getTrait } = useTraitModel()

  return { trait: getTrait(traitId) }
}
