import { useRef, useState } from 'react'

// Owns the set of currently-open panes -- the one thing SearchView itself needs to track. A pane is
// either a search pane or an item pane (kind discriminates the two); SearchContentsView renders the
// right component for each. Pane identity is a stable, ever-incrementing id (not array position), so
// a pane's label/key never shifts if an earlier pane is later removed -- matches ntrloc-search.js's
// own "Search {pane.id}" convention. maximizedPaneId is at most one pane at a time (a window-manager
// "maximize", not a multi-select) -- null means none maximized.
export function useSearchViewModel() {
  const [panes, setPanes] = useState([])
  const [maximizedPaneId, setMaximizedPaneId] = useState(null)
  const nextPaneId = useRef(1)

  function addSearchPane() {
    const id = nextPaneId.current++
    setPanes((current) => [...current, { id, kind: 'search' }])
  }

  // itemTypeName/itemId identify what to show -- ItemPaneView fetches the rest itself (see
  // ItemPaneModel). Always opens a new pane, even for an item already open elsewhere; no dedup.
  function addItemPane(itemTypeName, itemId) {
    const id = nextPaneId.current++
    setPanes((current) => [...current, { id, kind: 'item', itemTypeName, itemId }])
  }

  function removePane(id) {
    setPanes((current) => current.filter((pane) => pane.id !== id))
    // A closed pane can't stay "the maximized one" -- otherwise every other pane would remain
    // hidden behind a maximize that no longer has anything to restore.
    setMaximizedPaneId((current) => (current === id ? null : current))
  }

  // Toggling the already-maximized pane restores it; toggling any other pane maximizes that one
  // instead (and implicitly un-maximizes whatever was maximized before, since only one id is ever
  // tracked) -- this is the whole of "maximize" behavior, no stack or z-order history needed.
  function toggleMaximize(id) {
    setMaximizedPaneId((current) => (current === id ? null : id))
  }

  // Called after an item's own delete succeeds, from either a search result or an ItemPane's own
  // delete button -- an ItemPane is identified by which item it shows, not by the pane id a search
  // result has no way to know, so this filters on itemId rather than reusing removePane. Closes
  // every ItemPane showing this item, not just one -- "no dedup" on open (addItemPane's own
  // comment) means more than one can exist for the same item. Same maximized-pane hygiene as
  // removePane above -- one of the closed panes may have been the maximized one.
  function closeItemPanesFor(itemId) {
    const closedIds = new Set(panes.filter((pane) => pane.kind === 'item' && pane.itemId === itemId).map((pane) => pane.id))
    setPanes((current) => current.filter((pane) => !(pane.kind === 'item' && pane.itemId === itemId)))
    if (closedIds.size > 0) {
      setMaximizedPaneId((current) => (closedIds.has(current) ? null : current))
    }
  }

  return { panes, addSearchPane, addItemPane, removePane, closeItemPanesFor, maximizedPaneId, toggleMaximize }
}
