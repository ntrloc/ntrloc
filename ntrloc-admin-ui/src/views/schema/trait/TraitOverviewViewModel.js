function sortByName(nodes) {
  return [...nodes].sort((a, b) => a.name.localeCompare(b.name))
}

// Alphabetical order is this view's own presentation concern -- trait.implementingItemTypes itself
// is just whatever order TraitModel found them scanning the items list in.
export function useTraitOverviewViewModel(trait) {
  return { implementingItemTypes: sortByName(trait.implementingItemTypes) }
}
