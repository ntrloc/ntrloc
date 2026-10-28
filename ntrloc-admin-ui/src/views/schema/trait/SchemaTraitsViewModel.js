import { useSchemaStatus } from '../../../global/model/SchemaCache'
import { useTraitModel } from '../../../global/model/TraitModel'

// New traits not yet saved (`drafts`) are listed alongside the saved ones. `nameTaken` says whether a
// new trait would clash with one already there, saved or not.
export function useSchemaTraitsViewModel(drafts) {
  const status = useSchemaStatus()
  const { getTraits } = useTraitModel()
  const traits = [...getTraits(), ...drafts].sort((a, b) => a.name.localeCompare(b.name))
  const nameTaken = (name) => traits.some((trait) => trait.name === name.trim())

  return { status, traits, nameTaken }
}
