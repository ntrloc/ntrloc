import { useRef, useState } from 'react'

// Owns the set of currently-open panes -- the one thing SearchView itself needs to track. A pane is
// either a search pane or an item pane (kind discriminates the two); SearchContentsView renders the
// right component for each. Pane identity is a stable, ever-incrementing id (not array position), so
// a pane's label/key never shifts if an earlier pane is later removed -- matches ntrloc-search.js's
// own "Search {pane.id}" convention. No other pane state (windowState, ...) yet -- taking this one
// small piece at a time.
export function useSearchViewModel() {
  const [panes, setPanes] = useState([])
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
  }

  // Called after a search result's own delete succeeds -- an ItemPane is identified by which item
  // it shows, not by the pane id a search result has no way to know, so this filters on itemId
  // rather than reusing removePane. Closes every ItemPane showing this item, not just one -- "no
  // dedup" on open (addItemPane's own comment) means more than one can exist for the same item.
  function closeItemPanesFor(itemId) {
    setPanes((current) => current.filter((pane) => !(pane.kind === 'item' && pane.itemId === itemId)))
  }

  return { panes, addSearchPane, addItemPane, removePane, closeItemPanesFor }
}
