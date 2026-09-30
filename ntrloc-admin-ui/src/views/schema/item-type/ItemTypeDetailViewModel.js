import { useItemTypeModel } from '../../../global/model/ItemTypeModel'

export function useItemTypeDetailViewModel(itemTypeId) {
  const { getItemType } = useItemTypeModel()

  return { itemType: getItemType(itemTypeId) }
}
