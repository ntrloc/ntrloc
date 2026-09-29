import { useSchemaModel } from '../../../global/model/SchemaModel'

export function useItemTypeDetailViewModel(itemTypeId) {
  const { getItemType } = useSchemaModel()

  return { itemType: getItemType(itemTypeId) }
}
