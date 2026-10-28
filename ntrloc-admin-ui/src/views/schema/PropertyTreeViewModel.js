import { useState } from 'react'

function sortByName(nodes) {
  return [...nodes].sort((a, b) => a.name.localeCompare(b.name))
}

function propertyMatches(property, filter) {
  return property.name.toLowerCase().includes(filter) || (property.description ?? '').toLowerCase().includes(filter)
}

// One container (the tree's top level or a group) ready to render: its own properties, then its own
// subgroups, each alphabetical. `inherited` carries down from a trait's or supertype's group to
// everything under it -- not declared by this container, so not this container's to change.
// Filtering keeps only properties whose name or description matches, and drops any group with no
// match anywhere inside it -- a group's own name never matches. Collapsing a group hides only its
// own contents, never a nested group's collapsed state, and is ignored while filtering so every
// match stays visible.
function shapeLevel(node, filter, collapsedGroupIds, inherited) {
  const properties = sortByName(node.properties)
    .filter((property) => !filter || propertyMatches(property, filter))
    .map((property) => ({ ...property, inherited: inherited || property.inherited }))
  const groups = sortByName(node.groups)
    .map((group) => {
      const groupInherited = inherited || group.inherited
      return {
        ...group,
        inherited: groupInherited,
        collapsed: !filter && collapsedGroupIds.has(group.id),
        ...shapeLevel(group, filter, collapsedGroupIds, groupInherited),
      }
    })
    .filter((group) => !filter || group.properties.length > 0 || group.groups.length > 0)
  return { properties, groups }
}

// Shared by the item type and trait editors' Properties tab and each link's properties -- takes
// whichever tree its caller already has rather than knowing where trees come from itself. Which
// groups are collapsed, and the filter text, live here rather than in the Model layer: neither has a
// counterpart in the schema, it's only how this view is looking at the tree it was handed. Starts
// with everything expanded and no filter. `externalFilter`, when given, is a filter the caller
// shows and owns itself, in place of this one's own.
export function usePropertyTreeViewModel(tree, externalFilter) {
  const [collapsedGroupIds, setCollapsedGroupIds] = useState(() => new Set())
  const [ownFilter, setFilter] = useState('')
  const filter = externalFilter ?? ownFilter

  const isEmpty = tree.properties.length === 0 && tree.groups.length === 0
  const normalizedFilter = filter.trim().toLowerCase()
  const root = shapeLevel(tree, normalizedFilter, collapsedGroupIds, false)
  const noMatches = normalizedFilter.length > 0 && root.properties.length === 0 && root.groups.length === 0

  function toggleGroup(groupId) {
    setCollapsedGroupIds((current) => {
      const next = new Set(current)
      if (next.has(groupId)) next.delete(groupId)
      else next.add(groupId)
      return next
    })
  }

  return { root, toggleGroup, isEmpty, noMatches, filter, setFilter }
}
