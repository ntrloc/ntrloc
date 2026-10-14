import PaneView from '../PaneView'
import SearchPaneControlsView from './SearchPaneControlsView'
import SearchPaneResultsView from './SearchPaneResultsView'
import { useSearchPaneViewModel } from './SearchPaneViewModel'
import './SearchPaneView.css'

function SearchPaneView({ label = 'Search', onClose, onOpenItem, onItemDeleted, maximized, onToggleMaximize }) {
  const { searchStatus, results, runSearch, deleteItem, pageable } = useSearchPaneViewModel()

  // Composes this pane's own delete (server call + re-fetch, see SearchModel) with the
  // cross-pane consequence a search result's own delete has no way to know about itself --
  // any ItemPane elsewhere showing the same item needs to close too.
  async function handleDeleteItem(itemId) {
    await deleteItem(itemId)
    onItemDeleted?.(itemId)
  }

  return (
    <PaneView title={label} onClose={onClose} maximized={maximized} onToggleMaximize={onToggleMaximize}>
      <SearchPaneControlsView onSearch={runSearch} />
      <SearchPaneResultsView
        searchStatus={searchStatus}
        results={results}
        pageable={pageable}
        onOpenItem={onOpenItem}
        onDeleteItem={handleDeleteItem}
      />
    </PaneView>
  )
}

export default SearchPaneView
