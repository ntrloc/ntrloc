import { useEffect, useSyncExternalStore } from 'react'
import { fetchAdminSchema } from '../delegate/SchemaDelegate'
import { onSchemaChanged } from '../delegate/ClusterEventDelegate'

// The only thing in the app that knows GET /api/admin/schema exists, or that a "schemaChanged"
// cluster event even exists -- every schema-related Model (ItemTypeModel, TraitModel, LinkModel,
// ControlledListModel, StateMachineModel) builds its own shaped getters on top of useSchemaSnapshot
// below, rather than each fetching/caching/subscribing to the schema itself. Fetched once, cached,
// shared by every consumer -- and reloaded automatically whenever a schemaChanged event arrives, so
// every subscriber updates together instead of each wiring its own SSE listener.

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

// Every per-concept Model's own hook subscribes on this too, separately from status -- load()
// always reassigns cachedSchema to a brand-new object rather than mutating it in place (see its own
// comment), so its identity alone is a true reflection of "the schema changed", the same way
// SearchModel's own #items/#offset/etc. each get their own useSyncExternalStore subscription in
// SearchPaneViewModel rather than one combined signal. status intentionally stays pinned at 'ready'
// through a background reload (so an already-rendered tree isn't torn down), so it can't be the only
// thing consumers watch for "there's new data" -- this is.
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

function ensureLoadStarted() {
  if (!hasStartedLoading) load()
}

// For a consumer that only needs to know whether the schema is loaded (e.g. to show a loading/error
// state) without needing any shaped data itself -- SchemaView, and every schema section's own list
// ViewModel, call this directly rather than going through any one concept's Model.
export function useSchemaStatus() {
  const status = useSyncExternalStore(subscribe, getStatusSnapshot)
  useEffect(() => {
    ensureLoadStarted()
  }, [])
  return status
}

// The raw schema snapshot every per-concept Model's own hook builds its shaped getters on top of --
// not meant to be used directly by a View or ViewModel. See ItemTypeModel/TraitModel/LinkModel/
// ControlledListModel/StateMachineModel instead.
export function useSchemaSnapshot() {
  const schema = useSyncExternalStore(subscribe, getSchemaSnapshot)
  useEffect(() => {
    ensureLoadStarted()
  }, [])
  return schema
}

// Escape hatch for ad-hoc/debugging use -- real features should use one of the shaped Models above,
// not this.
export function getRawSchema() {
  return cachedSchema
}
