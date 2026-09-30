function sortByName(nodes) {
  return [...nodes].sort((a, b) => a.name.localeCompare(b.name))
}

// Alphabetical order is this view's own presentation concern -- itemType.traits itself is just
// whatever order the backend returned them in.
export function useItemTypeOverviewViewModel(itemType) {
  return { traits: sortByName(itemType.traits) }
}
