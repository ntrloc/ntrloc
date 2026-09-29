import { useSchemaModel } from '../../global/model/SchemaModel'

// Alphabetical order is this view's own presentation concern, same as
// useSchemaItemTypesViewModel/useSchemaTraitsViewModel -- SchemaModel's accessor stays raw/unsorted.
export function useItemTypePropertiesViewModel(itemTypeId) {
  const { getItemTypeProperties } = useSchemaModel()
  const entries = [...getItemTypeProperties(itemTypeId)].sort((a, b) => a.name.localeCompare(b.name))

  return { entries }
}
