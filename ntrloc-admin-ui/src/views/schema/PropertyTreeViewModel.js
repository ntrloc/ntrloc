import { useState } from 'react'

function sortByName(nodes) {
  return [...nodes].sort((a, b) => a.name.localeCompare(b.name))
}

// Flattens one level of a group's (or the container's own) properties/groups into depth-annotated
// rows ready to render as table rows, recursing into every group that isn't currently collapsed. A
// container's own properties always list before its own subgroups (each cluster alphabetical on its
// own), not one combined alphabetical list -- a container's direct data before its structure,
// consistently at every level. Collapsing a group only ever hides *that group's* own children -- it
// doesn't touch any nested group's own collapsed state, so re-expanding it later shows descendants
// exactly as they were left, not reset back to expanded.
// inherited carries down from a trait's or supertype's group to everything under it -- not declared
// by this container, so not this container's to delete.
function flattenLevel(properties, groups, depth, collapsedGroupIds, inherited = false) {
  const propertyRows = sortByName(properties).map((property) => ({
    ...property,
    depth,
    inherited: inherited || property.inherited,
  }))
  const groupRows = sortByName(groups).flatMap((group) => {
    const collapsed = collapsedGroupIds.has(group.id)
    const hasChildren = group.properties.length > 0 || group.groups.length > 0
    const row = {
      kind: 'group',
      id: group.id,
      name: group.name,
      depth,
      collapsed,
      hasChildren,
      traitNamespace: group.traitNamespace,
    }
    const childrenInherited = inherited || group.inherited
    return collapsed
      ? [row]
      : [row, ...flattenLevel(group.properties, group.groups, depth + 1, collapsedGroupIds, childrenInherited)]
  })
  return [...propertyRows, ...groupRows]
}

function propertyMatches(property, nameFilter, descriptionFilter) {
  if (nameFilter && !property.name.toLowerCase().includes(nameFilter)) return false
  if (!descriptionFilter) return true
  return (property.description ?? '').toLowerCase().includes(descriptionFilter)
}

// Filtering only ever matches a property's own name/description -- never a group's, so a group can
// never appear in results just because its own name happens to match. Returns a flat, alphabetical
// list of matches (no group header rows, no indentation -- there's no tree left to convey nesting
// once non-matching branches are gone), each carrying its own immediate parent group's name so
// which group it's in is still visible without the tree around it.
function matchProperties(properties, groups, nameFilter, descriptionFilter, groupName, inherited = false) {
  const matches = properties
    .filter((property) => propertyMatches(property, nameFilter, descriptionFilter))
    .map((property) => ({ ...property, depth: 0, groupName, inherited: inherited || property.inherited }))
  const nested = groups.flatMap((group) =>
    matchProperties(
      group.properties,
      group.groups,
      nameFilter,
      descriptionFilter,
      group.name,
      inherited || group.inherited,
    ),
  )
  return sortByName([...matches, ...nested])
}

// Shared by both the item type and trait editors' Properties tab -- takes whichever tree its caller
// already fetched (getItemTypePropertyTree/getTraitPropertyTree) rather than knowing where trees
// come from itself. Which groups are collapsed, and the current name/description filter text, both
// live here rather than in the Model layer -- neither has a counterpart in the schema itself, it's
// purely how this view is currently looking at whatever tree it was handed. Starts with everything
// expanded (an empty set) and no filter, same "start open" precedent as Accordion/CollapsibleSection.
export function usePropertyTreeViewModel(tree) {
  const [collapsedGroupIds, setCollapsedGroupIds] = useState(() => new Set())
  const [nameFilter, setNameFilter] = useState('')
  const [descriptionFilter, setDescriptionFilter] = useState('')

  const isEmpty = tree.properties.length === 0 && tree.groups.length === 0

  const normalizedNameFilter = nameFilter.trim().toLowerCase()
  const normalizedDescriptionFilter = descriptionFilter.trim().toLowerCase()
  const filtering = normalizedNameFilter.length > 0 || normalizedDescriptionFilter.length > 0

  const rows = filtering
    ? matchProperties(tree.properties, tree.groups, normalizedNameFilter, normalizedDescriptionFilter, null)
    : flattenLevel(tree.properties, tree.groups, 0, collapsedGroupIds)

  function toggleGroup(groupId) {
    setCollapsedGroupIds((current) => {
      const next = new Set(current)
      if (next.has(groupId)) next.delete(groupId)
      else next.add(groupId)
      return next
    })
  }

  return { rows, toggleGroup, isEmpty, nameFilter, setNameFilter, descriptionFilter, setDescriptionFilter }
}
