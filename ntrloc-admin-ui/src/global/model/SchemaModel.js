import { useEffect, useSyncExternalStore } from 'react'
import { fetchAdminSchema } from '../delegate/SchemaDelegate'
import { onSchemaChanged } from '../delegate/ClusterEventDelegate'

// The only thing in the app that knows GET /api/admin/schema exists, or that a "schemaChanged"
// cluster event even exists -- everything else asks this model for item types (and, later, traits,
// controlled lists, mutations, ...) via useSchemaModel() and never sees the delegate or the raw
// schema shape directly. Fetched once, cached, shared by every consumer -- and reloaded
// automatically whenever a schemaChanged event arrives, so every subscriber updates together
// instead of each wiring its own SSE listener.

// Seeded from import.meta.hot.data when present (see the dispose handler below) -- a Vite HMR
// update to this file re-evaluates all of this from scratch otherwise, resetting an
// already-mounted SchemaView back to its initial 'loading' status forever (nothing re-fires its
// mount effect to call load() again). Plain init values in production, where import.meta.hot is
// undefined.
let cachedSchema = import.meta.hot?.data?.cachedSchema ?? null
let status = import.meta.hot?.data?.status ?? 'loading' // 'loading' | 'ready' | 'error'
let hasStartedLoading = import.meta.hot?.data?.hasStartedLoading ?? false
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

// useSchemaModel subscribes on this too, separately from status -- load() always reassigns
// cachedSchema to a brand-new object rather than mutating it in place (see its own comment), so its
// identity alone is a true reflection of "the model changed", the same way SearchModel's own
// #items/#offset/etc. each get their own useSyncExternalStore subscription in SearchPaneViewModel
// rather than one combined signal. status intentionally stays pinned at 'ready' through a
// background reload (so an already-rendered tree isn't torn down), so it can't be the only thing
// consumers watch for "there's new data" -- this is.
function getSchemaSnapshot() {
  return cachedSchema
}

function load() {
  hasStartedLoading = true
  // Only the very first fetch should flip status to 'loading' -- a later reload (schemaChanged)
  // already has a working cachedSchema to keep showing while the new one comes in. Consumers key
  // their rendering off status, so leaving it at 'ready' throughout a background reload is what
  // keeps e.g. SchemaView's currently-selected item type and open tabs from being torn down and
  // rebuilt every time someone else edits the schema -- the new data just flows through to
  // whatever's already on screen once it arrives, same as any other re-render.
  const isInitialLoad = cachedSchema === null
  if (isInitialLoad) {
    status = 'loading'
    notify()
  }
  return fetchAdminSchema()
    .then((schema) => {
      cachedSchema = schema
      status = 'ready'
      notify()
    })
    .catch((error) => {
      if (isInitialLoad) {
        status = 'error'
        notify()
      } else {
        console.error('Failed to reload schema', error)
      }
    })
}

export function reload() {
  return load()
}

const unsubscribeFromSchemaChanged = onSchemaChanged(() => {
  reload()
})

// Dev-only: Vite HMR re-evaluates this module's top-level code (including the subscription above)
// on every edit, but ClusterEventDelegate's own listener set is a separate module that doesn't get
// reset alongside it -- without unsubscribing the old listener first, repeated edits pile up stale
// listeners pointing at abandoned module instances instead of replacing the one that matters.
// Handing cachedSchema/status/hasStartedLoading forward through import.meta.hot.data (read back at
// the top of this file) is what lets the next module instance pick up where this one left off,
// instead of resetting to 'loading' with nothing left to ever call load() again. All inert in a
// production build, where import.meta.hot is undefined.
if (import.meta.hot) {
  import.meta.hot.dispose((data) => {
    unsubscribeFromSchemaChanged()
    data.cachedSchema = cachedSchema
    data.status = status
    data.hasStartedLoading = hasStartedLoading
  })
}

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

function toPropertyNode(property) {
  return {
    kind: 'property',
    id: property.id,
    name: property.name,
    description: property.description,
    type: property.type,
    cardinality: property.cardinality,
    usage: property.usage,
  }
}

// Recursive -- a group's own properties/groups nest to any depth, mirroring
// AdminPropertyGroupView's own shape on the backend. traitNamespace marks a synthetic group (not a
// real one an item type declared) standing in for everything a trait it implements contributes --
// its id/name are literally the trait's own, which is also all a consumer needs to jump to that
// trait (see SchemaContentResolver.ownAndTraitContents on the backend).
function toGroupNode(group) {
  return {
    kind: 'group',
    id: group.id,
    name: group.name,
    description: group.description,
    traitNamespace: group.traitNamespace,
    properties: group.properties.map(toPropertyNode),
    groups: group.groups.map(toGroupNode),
  }
}

// Shared by item types and traits alike -- both just have their own properties/groups list, and
// this cares only about that shape, not which kind of container it came from. Unflattened -- which
// rows are visible (a group can be collapsed) and in what order is PropertyTreeViewModel's concern,
// not this model's.
function toPropertyTree(container) {
  return {
    properties: container.properties.map(toPropertyNode),
    groups: container.groups.map(toGroupNode),
  }
}

function toItemTypePropertyTree(adminSchema, itemTypeId) {
  const item = adminSchema.items.find((it) => it.id === itemTypeId)
  return item ? toPropertyTree(item) : { properties: [], groups: [] }
}

function toTraitPropertyTree(adminSchema, traitId) {
  const trait = adminSchema.traits.find((t) => t.id === traitId)
  return trait ? toPropertyTree(trait) : { properties: [], groups: [] }
}

// A link's own properties/groups live in their own top-level list on the wire (one entry per
// linkId, shared by both perspectives), not on either item type -- unlike an item type/trait's
// properties, which live right on the container itself.
function toLinkPropertyTree(adminSchema, linkId) {
  const link = adminSchema.links.find((l) => l.id === linkId)
  return link ? toPropertyTree(link) : { properties: [], groups: [] }
}

// Used by the trait editor's own Links tab (still just a plain list there) -- both item types and
// traits have a links map on the wire, keyed by the link's own perspective name (e.g. "photos",
// "creator"), null rather than an empty map when there are none. Item types get a richer view (see
// toItemTypeLinkDetails below); a trait isn't one specific item type, so a trait's own link entry
// has no single concrete "other side" to diagram the way an item type's does.
function toLinkNames(container) {
  if (!container.links) return []
  return Object.keys(container.links).map((name) => ({ name }))
}

function toTraitLinks(adminSchema, traitId) {
  const trait = adminSchema.traits.find((t) => t.id === traitId)
  return trait ? toLinkNames(trait) : []
}

// Every link is really two perspectives (one on each side) sharing a linkId -- this item type's own
// side is right there in its own links map, but the *reverse* perspective's own name/cardinality
// only exists on the target item type's own links map. Found by scanning it for whichever entry
// shares this perspective's linkId but isn't this exact perspective (id differs) -- the id check is
// what keeps a self-link (an item type linking to its own kind) from matching itself as its own
// reverse. Only the first target is resolved for now -- a link naming more than one possible target
// type is a real (if rarer) case this doesn't yet have a diagram for.
function toItemTypeLinkDetails(adminSchema, itemTypeId) {
  const item = adminSchema.items.find((it) => it.id === itemTypeId)
  if (!item || !item.links) return []

  return Object.entries(item.links).flatMap(([name, perspectives]) =>
    perspectives.map((perspective) => {
      const target = perspective.targets[0] ?? null
      const targetItem = target ? adminSchema.items.find((it) => it.name === target.name) : null

      let reverse = null
      if (targetItem?.links) {
        for (const [reverseName, reversePerspectives] of Object.entries(targetItem.links)) {
          const match = reversePerspectives.find((p) => p.linkId === perspective.linkId && p.id !== perspective.id)
          if (match) {
            reverse = { name: reverseName, cardinality: { min: match.minCardinality, max: match.maxCardinality } }
            break
          }
        }
      }

      return {
        id: perspective.id,
        linkId: perspective.linkId,
        name,
        cardinality: { min: perspective.minCardinality, max: perspective.maxCardinality },
        target: target ? { name: target.name } : null,
        reverse,
      }
    }),
  )
}

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
  // "Implements" (see toItemTypeDetail) reads from the other direction.
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
  // Not read here -- getItemTypes/getItemType/etc. below still go through cachedSchema directly,
  // same as before. Subscribing is what matters: it's what forces a re-render on a background
  // reload, when cachedSchema gets reassigned to a new object but status doesn't change (see
  // getSchemaSnapshot's own comment).
  useSyncExternalStore(subscribe, getSchemaSnapshot)

  useEffect(() => {
    if (!hasStartedLoading) load()
  }, [])

  return {
    status,
    getItemTypes: () => (cachedSchema ? toItemTypes(cachedSchema) : []),
    getItemType: (itemTypeId) => (cachedSchema ? toItemTypeDetail(cachedSchema, itemTypeId) : null),
    getItemTypePropertyTree: (itemTypeId) =>
      cachedSchema ? toItemTypePropertyTree(cachedSchema, itemTypeId) : { properties: [], groups: [] },
    getItemTypeLinkDetails: (itemTypeId) => (cachedSchema ? toItemTypeLinkDetails(cachedSchema, itemTypeId) : []),
    getLinkPropertyTree: (linkId) => (cachedSchema ? toLinkPropertyTree(cachedSchema, linkId) : { properties: [], groups: [] }),
    getConcreteItemTypes: () => (cachedSchema ? concreteItemTypes(toItemTypes(cachedSchema)) : []),
    getProjectableProperties: (itemTypeId) => (cachedSchema ? projectableProperties(cachedSchema, itemTypeId) : []),
    getTraits: () => (cachedSchema ? toTraits(cachedSchema) : []),
    getTrait: (traitId) => (cachedSchema ? toTraitDetail(cachedSchema, traitId) : null),
    getTraitPropertyTree: (traitId) =>
      cachedSchema ? toTraitPropertyTree(cachedSchema, traitId) : { properties: [], groups: [] },
    getTraitLinks: (traitId) => (cachedSchema ? toTraitLinks(cachedSchema, traitId) : []),
    getControlledLists: () => (cachedSchema ? toControlledLists(cachedSchema) : []),
    // Escape hatch for ad-hoc/debugging use -- real features should use the shaped accessors above,
    // not this.
    getRawSchema: () => cachedSchema,
  }
}
