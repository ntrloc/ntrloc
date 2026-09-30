import { useSchemaStatus } from '../../../global/model/SchemaCache'
import { useItemTypeModel } from '../../../global/model/ItemTypeModel'

// Alphabetical order is this view's own presentation concern, not baked into ItemTypeModel's plain
// getItemTypes() -- unlike getConcreteItemTypes (sorted for a search-target picker), this list
// includes abstract types too, since a schema editor needs to see and manage every item type, not
// just the ones a search can target.
export function useSchemaItemTypesViewModel() {
  const status = useSchemaStatus()
  const { getItemTypes } = useItemTypeModel()
  const itemTypes = [...getItemTypes()].sort((a, b) => a.name.localeCompare(b.name))

  return { status, itemTypes }
}
