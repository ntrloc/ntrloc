import { useSchemaModel } from '../../../global/model/SchemaModel'

// Alphabetical order is this view's own presentation concern -- SchemaModel's accessor stays raw.
export function useItemTypeLinksViewModel(itemTypeId) {
  const { getItemTypeLinkDetails } = useSchemaModel()
  const links = [...getItemTypeLinkDetails(itemTypeId)].sort((a, b) => a.name.localeCompare(b.name))

  return { links }
}
