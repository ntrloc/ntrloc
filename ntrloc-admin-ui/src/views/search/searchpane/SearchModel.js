import { deleteItem as deleteItemDelegate, fetchCollectionProjection } from './SearchDelegate'
import { toSearchResultItemModel } from './SearchResultItemModel'

const DEFAULT_LIMIT = 50

// Owns this pane's search execution -- the only thing that talks to SearchDelegate. One instance
// per pane (see SearchPaneViewModel), not a shared/global singleton like SchemaModel: each pane has
// its own independent query and results, with nothing to share across panes.
//
// Deliberately has no concept of "page" -- the wire protocol doesn't either (CollectionProjectionSpec
// takes offset/limit; ProjectionResult returns totalCount, nothing else pagination-related). This
// class owns exactly what the protocol owns: offset, limit, totalCount, and the itemTypeName of
// whatever it last searched (so a later offset-only re-fetch knows what to re-run). "Page" is a
// derived, presentation-only concept -- see SearchPaneViewModel's own pageable object.
//
// Methods are arrow class fields (bound per instance at construction) rather than plain methods, so
// they can be passed around detached -- e.g. straight to useSyncExternalStore -- and still resolve
// private fields correctly instead of throwing on a missing `this`.
export class SearchModel {
  #status = 'idle' // 'idle' | 'loading' | 'ready' | 'error'
  #items = []
  #itemTypeName = null
  #propertyPaths = null
  #offset = 0
  #limit = DEFAULT_LIMIT
  #totalCount = 0
  #listeners = new Set()

  // propertyPaths is an array of dot-paths, or null for "no restriction at all" -- passed straight
  // through to fetchProjection unchanged, matching CollectionProjectionSpec's own "null means all,
  // non-null (even empty) means only these" contract. Comes from SearchPaneControlsView's
  // property-selection tree, which can select whole branches, specific deep leaves, or a mix -- see
  // propertyTree.js/checkboxTreeSelection.js for what a selection actually means on the wire.
  search = (itemTypeName, propertyPaths) => {
    this.#itemTypeName = itemTypeName
    this.#propertyPaths = propertyPaths
    this.#offset = 0
    return this.#fetch()
  }

  fetchOffset = (offset) => {
    this.#offset = Math.max(0, offset)
    return this.#fetch()
  }

  // Re-fetches the current page after a successful delete, rather than removing the item from
  // #items locally -- totalCount/pagination need to reflect the deletion too, not just the visible
  // row, and this is the one place that already knows how to re-run the last search.
  deleteItem = async (itemId) => {
    await deleteItemDelegate(itemId)
    return this.#fetch()
  }

  #fetch = async () => {
    this.#status = 'loading'
    this.#notify()
    try {
      const raw = await fetchCollectionProjection({
        itemTypeName: this.#itemTypeName,
        offset: this.#offset,
        limit: this.#limit,
        properties: this.#propertyPaths,
        includePermissions: true,
      })
      this.#items = raw.items.map(toSearchResultItemModel)
      this.#totalCount = raw.totalCount
      this.#status = 'ready'
    } catch {
      this.#status = 'error'
    }
    this.#notify()
  }

  getStatus = () => this.#status

  getItems = () => this.#items

  getOffset = () => this.#offset

  getLimit = () => this.#limit

  getTotalCount = () => this.#totalCount

  subscribe = (listener) => {
    this.#listeners.add(listener)
    return () => this.#listeners.delete(listener)
  }

  #notify() {
    this.#listeners.forEach((listener) => listener())
  }
}
