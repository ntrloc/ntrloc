import { useEffect, useSyncExternalStore } from 'react'
import {
  fetchMarkers,
  createMarker as createMarkerRequest,
  updateMarker as updateMarkerRequest,
  deleteMarker as deleteMarkerRequest,
} from '../delegate/MarkerDelegate'

// Same cache-plus-useSyncExternalStore shape as SchemaCache, scoped to markers instead -- fetched
// once, shared by every consumer. No cluster event to listen for here (see ClusterEventDelegate's
// own hardcoded EVENT_TYPES list): nothing outside this app's own create/update/delete calls below
// ever changes a marker, so there's nothing to push-reload for, unlike the schema.
//
// create/update/delete mutate cachedMarkers directly from the response, same as the old vanilla-JS
// admin UI's marker-service.js did -- no need to refetch the whole list after a write.

let cachedMarkers = import.meta.hot?.data?.cachedMarkers ?? null
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

function getSnapshot() {
  return cachedMarkers
}

function getStatusSnapshot() {
  return status
}

function load() {
  hasStartedLoading = true
  const isInitialLoad = cachedMarkers === null
  if (isInitialLoad) {
    status = 'loading'
    notify()
  }
  return fetchMarkers()
    .then((markers) => {
      cachedMarkers = markers
      status = 'ready'
      notify()
    })
    .catch((error) => {
      if (isInitialLoad) {
        status = 'error'
        notify()
      } else {
        console.error('Failed to reload markers', error)
      }
    })
}

function ensureLoadStarted() {
  if (!hasStartedLoading) void load()
}

// Same Vite-HMR hand-off as SchemaCache's own dispose handler -- see that file's comment.
if (import.meta.hot) {
  import.meta.hot.dispose((data) => {
    data.cachedMarkers = cachedMarkers
    data.status = status
    data.hasStartedLoading = hasStartedLoading
  })
}

export function useMarkerStatus() {
  const currentStatus = useSyncExternalStore(subscribe, getStatusSnapshot)
  useEffect(() => {
    ensureLoadStarted()
  }, [])
  return currentStatus
}

export function useMarkerModel() {
  const markers = useSyncExternalStore(subscribe, getSnapshot)
  useEffect(() => {
    ensureLoadStarted()
  }, [])

  function getMarkersForItemType(itemTypeId) {
    return (markers ?? []).filter((m) => m.scopeKind === 'ITEM_TYPE' && m.scopeId === itemTypeId)
  }

  async function createMarker({ name, description, scopeKind, scopeId }) {
    const marker = await createMarkerRequest({ name, description, scopeKind, scopeId })
    cachedMarkers = [...(cachedMarkers ?? []), marker]
    notify()
    return marker
  }

  async function updateMarker(id, { name, description }) {
    const marker = await updateMarkerRequest(id, { name, description })
    cachedMarkers = (cachedMarkers ?? []).map((m) => (m.id === id ? marker : m))
    notify()
    return marker
  }

  async function deleteMarker(id) {
    await deleteMarkerRequest(id)
    cachedMarkers = (cachedMarkers ?? []).filter((m) => m.id !== id)
    notify()
  }

  return { getMarkersForItemType, createMarker, updateMarker, deleteMarker }
}
