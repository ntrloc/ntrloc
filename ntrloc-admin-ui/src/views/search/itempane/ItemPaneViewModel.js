import { useEffect, useState, useSyncExternalStore } from 'react'
import { ItemPaneModel } from './ItemPaneModel'

// One ItemPaneModel per pane, created once and reused across re-renders -- same convention as
// useSearchPaneViewModel. itemTypeName/itemId are fixed for the pane's lifetime (an ItemPane always
// shows one specific item), so the load effect only re-runs if those ever actually change.
// groupedProperties is derived here (not left to the view) so ItemPanePropertyGroupsView stays a
// dumb map-over-groups renderer -- same view/viewmodel split SearchPaneControlsViewModel's own
// summary strings already use.
export function useItemPaneViewModel(itemTypeName, itemId) {
  const [model] = useState(() => new ItemPaneModel())
  const status = useSyncExternalStore(model.subscribe, model.getStatus)
  const item = useSyncExternalStore(model.subscribe, model.getItem)

  useEffect(() => {
    model.load(itemTypeName, itemId)
  }, [model, itemTypeName, itemId])

  return { status, item, groupedProperties: item ? groupProperties(item.properties) : [] }
}

// Flattens a (possibly nested) properties object into an ordered list of { path, leaves } groups:
// one entry per level that has its own direct leaf values, path being that level's full dot-path
// (null for the root level, which gets no header of its own -- there's nothing to say "this is the
// top" that isn't already obvious). Within a level, leaves are listed before descending into its
// subgroups, and both bands are sorted alphabetically on their own -- the same two-band ordering
// PropertyTreeView already uses (direct values read like a summary of a group, before drilling into
// what it nests), just flattened into a list of groups instead of recursive JSX.
export function groupProperties(properties, path = null) {
  const entries = Object.entries(properties ?? {})
  const leafEntries = entries.filter(([, value]) => !isGroup(value)).sort(([a], [b]) => a.localeCompare(b))
  const groupEntries = entries.filter(([, value]) => isGroup(value)).sort(([a], [b]) => a.localeCompare(b))

  const groups = []
  if (leafEntries.length > 0) {
    groups.push({ path, leaves: leafEntries.map(([name, value]) => ({ name, value })) })
  }
  for (const [key, value] of groupEntries) {
    groups.push(...groupProperties(value, path ? `${path}.${key}` : key))
  }
  return groups
}

// Same leaf/group split PropertyTreeView.js already uses: a plain object is a nested group to
// recurse into, everything else (including arrays) is a leaf value as-is.
function isGroup(value) {
  return value !== null && typeof value === 'object' && !Array.isArray(value)
}
