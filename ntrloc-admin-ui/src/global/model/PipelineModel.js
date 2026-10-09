import { useEffect, useSyncExternalStore } from 'react'
import { fetchPipelineForItemType } from '../delegate/PipelineDelegate'

// Keyed by itemTypeId, not a flat list -- an item type has at most one pipeline (see
// MarkerDecisionPipelineRepository's own comment), so "the pipeline for this item type" is the only
// shape this model needs, fetched on demand per type rather than eagerly loading every pipeline in
// the system up front the way MarkerModel loads every marker. Each cache entry is
// { status: 'loading' | 'ready' | 'error', pipeline: PipelineView | null }.

let cache = import.meta.hot?.data?.cache ?? new Map()
const listeners = new Set()

function notify() {
  listeners.forEach((listener) => listener())
}

function subscribe(listener) {
  listeners.add(listener)
  return () => listeners.delete(listener)
}

function load(itemTypeId) {
  cache.set(itemTypeId, { status: 'loading', pipeline: null })
  notify()
  fetchPipelineForItemType(itemTypeId)
    .then((pipeline) => {
      cache.set(itemTypeId, { status: 'ready', pipeline })
      notify()
    })
    .catch((error) => {
      console.error('Failed to load pipeline for item type', itemTypeId, error)
      cache.set(itemTypeId, { status: 'error', pipeline: null })
      notify()
    })
}

// Same Vite-HMR hand-off as MarkerModel's own dispose handler -- see that file's comment.
if (import.meta.hot) {
  import.meta.hot.dispose((data) => {
    data.cache = cache
  })
}

export function usePipelineForItemType(itemTypeId) {
  const entry = useSyncExternalStore(subscribe, () => cache.get(itemTypeId))
  useEffect(() => {
    if (!cache.has(itemTypeId)) load(itemTypeId)
  }, [itemTypeId])

  return entry ?? { status: 'loading', pipeline: null }
}
