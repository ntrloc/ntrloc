import { useEffect, useSyncExternalStore } from 'react'
import { addSchemaReloadHook, useSchemaSnapshot } from './SchemaCache'
import { fetchControlledListValues } from '../delegate/ControlledListDelegate'

// `usedBy` is every property pointing at the list: { propertyId, propertyName, ownerLabel, ownerId,
// ownerKind }, ownerKind being ITEM, TRAIT or LINK.
const toControlledList = (list) => ({
  id: list.id,
  name: list.name,
  valueType: list.valueType,
  valueCount: list.valueCount,
  usedBy: list.usedBy,
})

export function useControlledListModel() {
  const schema = useSchemaSnapshot()
  return {
    getControlledLists: () => (schema ? schema.controlledLists.map(toControlledList) : []),
    getControlledList: (listId) => {
      const list = schema?.controlledLists.find((candidate) => candidate.id === listId)
      return list ? toControlledList(list) : null
    },
  }
}

// A controlled list's own values aren't part of the bulk schema fetch above (see
// fetchControlledListValues' own comment), so this is a second, independent, lazy cache keyed by
// listId -- fetched once and shared by every consumer asking for the same list, e.g. every Input
// editing a different property that happens to point at the same controlled list.
const valuesCache = new Map() // listId -> { status: 'loading' | 'ready' | 'error', values: [] }
const valuesListeners = new Map() // listId -> Set<fn>
const IDLE_SNAPSHOT = { status: 'idle', values: [] }
// Returned by getSnapshot below for a listId not yet in valuesCache (the window between mount and
// loadValues' own effect running) -- a stable reference, not a fresh object literal per call, which
// useSyncExternalStore requires: returning a new object every call looks indistinguishable from an
// ever-changing store and trips React's own "getSnapshot should be cached" loop guard.
const PENDING_SNAPSHOT = { status: 'loading', values: [] }

function notifyValues(listId) {
  valuesListeners.get(listId)?.forEach((listener) => listener())
}

function loadValues(listId) {
  if (valuesCache.has(listId)) return
  valuesCache.set(listId, { status: 'loading', values: [] })
  fetchControlledListValues(listId)
    .then((body) => {
      valuesCache.set(listId, { status: 'ready', values: body.values })
      notifyValues(listId)
    })
    .catch(() => {
      valuesCache.set(listId, { status: 'error', values: [] })
      notifyValues(listId)
    })
}

// With every schema reload, the values already loaded are fetched again -- saved edits, here or
// elsewhere, change them.
addSchemaReloadHook(() =>
  Promise.all(
    [...valuesCache.keys()].map((listId) =>
      fetchControlledListValues(listId)
        .then((body) => valuesCache.set(listId, { status: 'ready', values: body.values }))
        .catch(() => valuesCache.set(listId, { status: 'error', values: [] }))
        .finally(() => notifyValues(listId)),
    ),
  ),
)

// `listId` may be null/undefined (a property with no controlled list) -- returns a permanently-idle
// snapshot rather than requiring every caller to guard the hook call itself, since the hook still
// has to run unconditionally either way (rules of hooks).
export function useControlledListValues(listId) {
  const snapshot = useSyncExternalStore(
    (listener) => {
      if (!listId) return () => {}
      if (!valuesListeners.has(listId)) valuesListeners.set(listId, new Set())
      valuesListeners.get(listId).add(listener)
      return () => valuesListeners.get(listId)?.delete(listener)
    },
    () => (listId ? (valuesCache.get(listId) ?? PENDING_SNAPSHOT) : IDLE_SNAPSHOT),
  )

  useEffect(() => {
    if (listId) loadValues(listId)
  }, [listId])

  return snapshot
}
