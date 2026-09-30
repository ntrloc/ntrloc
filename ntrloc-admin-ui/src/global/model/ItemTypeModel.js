import { useSchemaSnapshot } from './SchemaCache'
import { toPropertyTree } from './propertyTreeShaping'

function toItemTypes(adminSchema) {
  return adminSchema.items.map((item) => ({
    id: item.id,
    name: item.name,
    abstractType: item.abstractType,
  }))
}

function toItemTypeDetail(adminSchema, itemTypeId) {
  const item = adminSchema.items.find((it) => it.id === itemTypeId)
  if (!item) return null
  // supertypeId is just an id on the wire -- resolved to {id, name} here so a consumer never needs
  // its own copy of "look up an item type by id" just to show/link to the parent.
  const supertype = item.supertypeId ? adminSchema.items.find((it) => it.id === item.supertypeId) : null
  return {
    id: item.id,
    name: item.name,
    description: item.description,
    abstractType: item.abstractType,
    displayLabelPattern: item.displayLabelPattern,
    supertype: supertype ? { id: supertype.id, name: supertype.name } : null,
    traits: item.traits.map((trait) => ({ id: trait.id, name: trait.name })),
  }
}

function toItemTypePropertyTree(adminSchema, itemTypeId) {
  const item = adminSchema.items.find((it) => it.id === itemTypeId)
  return item ? toPropertyTree(item) : { properties: [], groups: [] }
}

// Abstract item types exist only to be extended -- never instantiated, so never a valid
// search target on their own.
function concreteItemTypes(itemTypes) {
  return itemTypes.filter((it) => !it.abstractType).sort((a, b) => a.name.localeCompare(b.name))
}

// projectableFields is its own flat, dot-notation list per item type (e.g. "File.name") -- distinct
// from sortableFields, which addresses a BINARY leaf via a synthetic ".metadata.length"-style path
// meant only for sort/filter (see RegisterPartitionManager.BINARY_METADATA_PATH_TYPES). Selecting a
// binary property always returns its whole value (id/url/metadata together), so projectableFields
// lists it by its own bare name instead -- reusing sortableFields here would offer an option that
// looks narrow but isn't. System fields (itemId, itemType, createdAt, ...) are already excluded
// server-side: they're item metadata, not entries in ProjectedItem.properties.
function projectableProperties(adminSchema, itemTypeId) {
  const item = adminSchema.items.find((it) => it.id === itemTypeId)
  if (!item) return []
  return item.projectableFields
}

export function useItemTypeModel() {
  const schema = useSchemaSnapshot()
  return {
    getItemTypes: () => (schema ? toItemTypes(schema) : []),
    getItemType: (itemTypeId) => (schema ? toItemTypeDetail(schema, itemTypeId) : null),
    getItemTypePropertyTree: (itemTypeId) =>
      schema ? toItemTypePropertyTree(schema, itemTypeId) : { properties: [], groups: [] },
    getConcreteItemTypes: () => (schema ? concreteItemTypes(toItemTypes(schema)) : []),
    getProjectableProperties: (itemTypeId) => (schema ? projectableProperties(schema, itemTypeId) : []),
  }
}
