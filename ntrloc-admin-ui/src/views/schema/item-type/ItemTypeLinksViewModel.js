import { useLinkModel } from '../../../global/model/LinkModel'

// Alphabetical order is this view's own presentation concern -- LinkModel's accessor stays raw.
export function useItemTypeLinksViewModel(itemTypeId) {
  const { getItemTypeLinkDetails } = useLinkModel()
  const links = [...getItemTypeLinkDetails(itemTypeId)].sort((a, b) => a.name.localeCompare(b.name))

  return { links }
}
