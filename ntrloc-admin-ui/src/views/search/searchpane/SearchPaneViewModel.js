import { useState, useSyncExternalStore } from 'react'
import { SearchModel } from './SearchModel'

// Owns this pane's SearchModel instance -- search execution, delegate access, and response shaping
// all live there now, not here. One model per pane, created once and reused across re-renders.
//
// "Page" doesn't exist on the model (see SearchModel's own comment) -- it's derived here, the one
// place that's allowed to know the model's raw offset/limit/totalCount are related to paging at
// all. pageable is a distinct object (not merged into this hook's own return value) so anything
// consuming it -- Pager -- only ever sees the minimal surface it actually needs, not this view
// model's other concerns (results, runSearch, ...). No subscribe of its own: whatever re-renders
// because of the useSyncExternalStore calls below already hands pageable's consumers fresh values
// on every render, so Pager gets reactivity for free just by being a child in that render tree.
export function useSearchPaneViewModel() {
  const [model] = useState(() => new SearchModel())
  const searchStatus = useSyncExternalStore(model.subscribe, model.getStatus)
  const results = useSyncExternalStore(model.subscribe, model.getItems)
  const offset = useSyncExternalStore(model.subscribe, model.getOffset)
  const limit = useSyncExternalStore(model.subscribe, model.getLimit)
  const totalCount = useSyncExternalStore(model.subscribe, model.getTotalCount)

  const pageable = {
    currentPage: () => Math.floor(offset / limit) + 1,
    totalPages: () => Math.max(1, Math.ceil(totalCount / limit)),
    moveToPreviousPage: () => model.fetchOffset(offset - limit),
    moveToNextPage: () => model.fetchOffset(offset + limit),
    moveToPage: (page) => model.fetchOffset((page - 1) * limit),
  }

  return { searchStatus, results, runSearch: model.search, deleteItem: model.deleteItem, pageable }
}
