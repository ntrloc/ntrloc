import { useMarkerModel } from '../../../global/model/MarkerModel'

// Item-type-scoped markers only (scopeKind === 'ITEM_TYPE') -- a marker's scope determines which
// item instances it's eligible to be assigned to, so a marker scoped to a trait wouldn't belong
// on any one item type's own panel (same reasoning as the old vanilla-JS admin UI's
// markersForItem). Trait-scoped markers get their own panel on the trait editor when that's
// built; not yet.
export function useAccessMarkersViewModel(itemTypeId) {
  const { getMarkersForItemType, createMarker, updateMarker, deleteMarker } = useMarkerModel()
  const markers = [...getMarkersForItemType(itemTypeId)].sort((a, b) => a.name.localeCompare(b.name))

  function create({ name, description }) {
    return createMarker({ name, description, scopeKind: 'ITEM_TYPE', scopeId: itemTypeId })
  }

  function update(id, { name, description }) {
    return updateMarker(id, { name, description })
  }

  return { markers, createMarker: create, updateMarker: update, deleteMarker }
}
