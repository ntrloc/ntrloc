import { useEffect, useSyncExternalStore } from 'react'
import { fetchPipelineForItemType, updatePipeline as updatePipelineRequest } from '../delegate/PipelineDelegate'

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

  // The diagram editor's save: the pipeline as edited and its layout, `enabled` carried through
  // unchanged. Mutates the cache from the response directly, same as MarkerModel's own create/update
  // do, rather than refetching.
  async function savePipeline({ pipeline, layout }) {
    const current = cache.get(itemTypeId)?.pipeline
    const updated = await updatePipelineRequest(current.id, { pipeline, enabled: current.enabled, layout })
    cache.set(itemTypeId, { status: 'ready', pipeline: updated })
    notify()
    return updated
  }

  return { ...(entry ?? { status: 'loading', pipeline: null }), savePipeline }
}
