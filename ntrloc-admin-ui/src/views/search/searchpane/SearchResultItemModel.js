// Plain data shape for one projected search result item -- no I/O.

export function toSearchResultItemModel(rawItem) {
  return {
    itemId: rawItem.itemId,
    itemType: rawItem.itemType,
    displayLabel: rawItem.displayLabel,
    properties: rawItem.properties,
    permissions: rawItem.permissions,
  }
}
