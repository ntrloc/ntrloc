import { useSchemaSnapshot } from './SchemaCache'
import { toPropertyTree } from './propertyTreeShaping'

function toTraits(adminSchema) {
  return adminSchema.traits.map((trait) => ({
    id: trait.id,
    name: trait.name,
  }))
}

function toTraitDetail(adminSchema, traitId) {
  const trait = adminSchema.traits.find((t) => t.id === traitId)
  if (!trait) return null
  // Not a field on the trait itself on the wire -- derived by scanning every item type's own
  // traits list for a reference back to this one, the same relationship an item type's own
  // "Implements" (see ItemTypeModel's toItemTypeDetail) reads from the other direction.
  const implementingItemTypes = adminSchema.items
    .filter((item) => item.traits.some((t) => t.id === traitId))
    .map((item) => ({ id: item.id, name: item.name }))
  return {
    id: trait.id,
    name: trait.name,
    description: trait.description,
    implementingItemTypes,
  }
}

function toTraitPropertyTree(adminSchema, traitId) {
  const trait = adminSchema.traits.find((t) => t.id === traitId)
  return trait ? toPropertyTree(trait) : { properties: [], groups: [] }
}

// Used by the trait editor's own Links tab (still just a plain list there) -- both item types and
// traits have a links map on the wire, keyed by the link's own perspective name (e.g. "photos",
// "creator"), null rather than an empty map when there are none. An item type gets a richer view
// (see LinkModel's toItemTypeLinkDetails); a trait isn't one specific item type, so a trait's own
// link entry has no single concrete "other side" to diagram the way an item type's does.
function toLinkNames(container) {
  if (!container.links) return []
  return Object.keys(container.links).map((name) => ({ name }))
}

function toTraitLinks(adminSchema, traitId) {
  const trait = adminSchema.traits.find((t) => t.id === traitId)
  return trait ? toLinkNames(trait) : []
}

export function useTraitModel() {
  const schema = useSchemaSnapshot()
  return {
    getTraits: () => (schema ? toTraits(schema) : []),
    getTrait: (traitId) => (schema ? toTraitDetail(schema, traitId) : null),
    getTraitPropertyTree: (traitId) =>
      schema ? toTraitPropertyTree(schema, traitId) : { properties: [], groups: [] },
    getTraitLinks: (traitId) => (schema ? toTraitLinks(schema, traitId) : []),
  }
}
