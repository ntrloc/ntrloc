import { useEffect, useState, useSyncExternalStore } from 'react'
import { ItemPaneModel } from './ItemPaneModel'

// One ItemPaneModel per pane, created once and reused across re-renders -- same convention as
// useSearchPaneViewModel. itemTypeName/itemId are fixed for the pane's lifetime (an ItemPane always
// shows one specific item), so the load effect only re-runs if those ever actually change.
export function useItemPaneViewModel(itemTypeName, itemId) {
  const [model] = useState(() => new ItemPaneModel())
  const status = useSyncExternalStore(model.subscribe, model.getStatus)
  const item = useSyncExternalStore(model.subscribe, model.getItem)

  useEffect(() => {
    model.load(itemTypeName, itemId)
  }, [model, itemTypeName, itemId])

  return { status, item }
}
