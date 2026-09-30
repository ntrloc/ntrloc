function sortByName(nodes) {
  return [...nodes].sort((a, b) => a.name.localeCompare(b.name))
}

// Shared by both the item type and trait editors' Links tab -- takes whichever list its caller
// already fetched (getItemTypeLinks/getTraitLinks), same split as PropertyTreeView/
// PropertyTreeViewModel. Alphabetical order is this view's own presentation concern.
export function useLinkListViewModel(links) {
  return { links: sortByName(links) }
}
