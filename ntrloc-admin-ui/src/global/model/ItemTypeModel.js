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

// sortableFields is what CollectionProjectionSpec.sortField/filter actually resolve against
// server-side (see RegisterPartitionManager.resolveProperty) -- distinct from projectableFields
// above, and the only list that's safe to offer for either control. `system` (itemId, createdAt,
// ...) is sortable via SYSTEM_SORT_COLUMNS but NOT filterable -- translatePredicate's
// PropertyExistencePredicate/PropertyValuePredicate branches resolve straight against real
// properties with no system-field fallback the way sort has -- so a filter property picker needs
// to exclude `system` entries itself; a sort property picker doesn't.
function sortableFields(adminSchema, itemTypeId) {
  const item = adminSchema.items.find((it) => it.id === itemTypeId)
  if (!item) return []
  return item.sortableFields
}

// stateMachines (AdminItemDefinitionView's own field) is the *effective* list -- inherited ones
// included, not just this type's own -- so this is really "can an item of this type ever have
// states," not just "does this exact type declare one." By name, not id, since a projected item
// only ever reports its own itemType as a name (see ProjectedItem), never the schema id.
function hasStateMachines(adminSchema, itemTypeName) {
  const item = adminSchema.items.find((it) => it.name === itemTypeName)
  return Boolean(item?.stateMachines?.length)
}

// item.traits is this type's own directly-declared traits (see SchemaAdminViewBuilder's own
// traitIdsByItem lookup) -- unlike stateMachines/properties/links above, not resolved through a
// supertype chain, but that's fine here: File is never inherited in this schema today, and nothing
// about trait-checking couldn't be extended the same "effective" way later if that changes. By
// name, same reasoning as hasStateMachines above.
function hasTrait(adminSchema, itemTypeName, traitName) {
  const item = adminSchema.items.find((it) => it.name === itemTypeName)
  return Boolean(item?.traits?.some((trait) => trait.name === traitName))
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
    getSortableFields: (itemTypeId) => (schema ? sortableFields(schema, itemTypeId) : []),
    getHasStateMachines: (itemTypeName) => (schema ? hasStateMachines(schema, itemTypeName) : false),
    getHasTrait: (itemTypeName, traitName) => (schema ? hasTrait(schema, itemTypeName, traitName) : false),
  }
}
