import { useEffect, useSyncExternalStore } from 'react'
import { fetchAdminSchema } from '../delegate/SchemaDelegate'
import { onSchemaChanged } from '../delegate/ClusterEventDelegate'

// The only thing in the app that knows GET /api/admin/schema exists, or that a "schemaChanged"
// cluster event even exists -- everything else asks this model for item types (and, later, traits,
// controlled lists, mutations, ...) via useSchemaModel() and never sees the delegate or the raw
// schema shape directly. Fetched once, cached, shared by every consumer -- and reloaded
// automatically whenever a schemaChanged event arrives, so every subscriber updates together
// instead of each wiring its own SSE listener.

let cachedSchema = null
let status = 'loading' // 'loading' | 'ready' | 'error'
let hasStartedLoading = false
const listeners = new Set()

function notify() {
  listeners.forEach((listener) => listener())
}

function subscribe(listener) {
  listeners.add(listener)
  return () => listeners.delete(listener)
}

function getStatusSnapshot() {
  return status
}

function load() {
  hasStartedLoading = true
  status = 'loading'
  notify()
  return fetchAdminSchema()
    .then((schema) => {
      cachedSchema = schema
      status = 'ready'
      notify()
    })
    .catch(() => {
      status = 'error'
      notify()
    })
}

export function reload() {
  return load()
}

onSchemaChanged(() => {
  reload()
})

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
  return {
    id: item.id,
    name: item.name,
    description: item.description,
    abstractType: item.abstractType,
  }
}

// Only the item type's own top-level properties/groups -- a property group's own nested
// properties/groups are that group's own concern once property editing exists, not something this
// first pass needs to walk into.
function toItemTypeProperties(adminSchema, itemTypeId) {
  const item = adminSchema.items.find((it) => it.id === itemTypeId)
  if (!item) return []
  const properties = item.properties.map((property) => ({
    id: property.id,
    name: property.name,
    kind: 'property',
    type: property.type,
  }))
  const groups = item.groups.map((group) => ({
    id: group.id,
    name: group.name,
    kind: 'group',
  }))
  return [...properties, ...groups]
}

function toTraits(adminSchema) {
  return adminSchema.traits.map((trait) => ({
    id: trait.id,
    name: trait.name,
  }))
}

function toControlledLists(adminSchema) {
  return adminSchema.controlledLists.map((list) => ({
    id: list.id,
    name: list.name,
  }))
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

export function useSchemaModel() {
  const status = useSyncExternalStore(subscribe, getStatusSnapshot)

  useEffect(() => {
    if (!hasStartedLoading) load()
  }, [])

  return {
    status,
    getItemTypes: () => (cachedSchema ? toItemTypes(cachedSchema) : []),
    getItemType: (itemTypeId) => (cachedSchema ? toItemTypeDetail(cachedSchema, itemTypeId) : null),
    getItemTypeProperties: (itemTypeId) => (cachedSchema ? toItemTypeProperties(cachedSchema, itemTypeId) : []),
    getConcreteItemTypes: () => (cachedSchema ? concreteItemTypes(toItemTypes(cachedSchema)) : []),
    getProjectableProperties: (itemTypeId) => (cachedSchema ? projectableProperties(cachedSchema, itemTypeId) : []),
    getTraits: () => (cachedSchema ? toTraits(cachedSchema) : []),
    getControlledLists: () => (cachedSchema ? toControlledLists(cachedSchema) : []),
    // Escape hatch for ad-hoc/debugging use -- real features should use the shaped accessors above,
    // not this.
    getRawSchema: () => cachedSchema,
  }
}
