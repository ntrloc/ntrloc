import { fetchItemProjection } from '../searchpane/SearchDelegate'

// Owns this pane's single-item fetch -- one instance per pane (see ItemPaneViewModel), not a
// shared/global singleton. Unlike SearchModel, there's no property/link restriction: an ItemPane
// exists to show the *entire* item, so properties/links are left null (every property, every direct
// link -- see ItemProjectionSpec's own contract), and permissions/states are both requested too.
// The raw ProjectedItem is stored as-is rather than narrowed into its own view-model shape -- unlike
// SearchResultItemModel, nothing here is deliberately dropped.
export class ItemPaneModel {
  #status = 'idle' // 'idle' | 'loading' | 'ready' | 'notFound' | 'error'
  #item = null
  #listeners = new Set()

  load = async (itemTypeName, itemId) => {
    this.#status = 'loading'
    this.#notify()
    try {
      const item = await fetchItemProjection({
        itemTypeName,
        itemId,
        includePermissions: true,
        includeStates: true,
      })
      if (item === null) {
        this.#status = 'notFound'
      } else {
        this.#item = item
        this.#status = 'ready'
      }
    } catch {
      this.#status = 'error'
    }
    this.#notify()
  }

  getStatus = () => this.#status

  getItem = () => this.#item

  subscribe = (listener) => {
    this.#listeners.add(listener)
    return () => this.#listeners.delete(listener)
  }

  #notify() {
    this.#listeners.forEach((listener) => listener())
  }
}
