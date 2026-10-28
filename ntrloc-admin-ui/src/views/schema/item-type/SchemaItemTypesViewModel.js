import { useSchemaStatus } from '../../../global/model/SchemaCache'
import { useItemTypeModel } from '../../../global/model/ItemTypeModel'

// Alphabetical order is this view's own presentation concern, not baked into ItemTypeModel's plain
// getItemTypes() -- unlike getConcreteItemTypes (sorted for a search-target picker), this list
// includes abstract types too, since a schema editor needs to see and manage every item type, not
// just the ones a search can target. New item types not yet saved (`drafts`) are listed alongside.
// `nameTaken` says whether a new item type would clash with one already there, saved or not.
export function useSchemaItemTypesViewModel(drafts) {
  const status = useSchemaStatus()
  const { getItemTypes } = useItemTypeModel()
  const itemTypes = [...getItemTypes(), ...drafts].sort((a, b) => a.name.localeCompare(b.name))
  const nameTaken = (name) => itemTypes.some((itemType) => itemType.name === name.trim())

  return { status, itemTypes, nameTaken }
}
