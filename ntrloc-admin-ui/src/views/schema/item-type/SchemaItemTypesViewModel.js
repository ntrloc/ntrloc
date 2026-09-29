import { useSchemaModel } from '../../../global/model/SchemaModel'

// Alphabetical order is this view's own presentation concern, not baked into SchemaModel's plain
// getItemTypes() -- unlike getConcreteItemTypes (sorted for a search-target picker), this list
// includes abstract types too, since a schema editor needs to see and manage every item type, not
// just the ones a search can target.
export function useSchemaItemTypesViewModel() {
  const { status, getItemTypes } = useSchemaModel()
  const itemTypes = [...getItemTypes()].sort((a, b) => a.name.localeCompare(b.name))

  return { status, itemTypes }
}
