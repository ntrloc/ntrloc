import Pager from '../../../controls/pager/Pager'
import SearchResultItemView from './SearchResultItemView'
import './SearchPaneResultsView.css'

function SearchPaneResultsView({ searchStatus = 'idle', results, pageable, onOpenItem, onDeleteItem }) {
  if (searchStatus === 'loading') return <search-pane-results-view>Searching…</search-pane-results-view>
  if (searchStatus === 'error') return <search-pane-results-view>Search failed.</search-pane-results-view>
  if (searchStatus !== 'ready') return <search-pane-results-view />

  return (
    <search-pane-results-view>
      <search-pane-results-list>
        {results.length === 0 && <p>No results.</p>}
        {results.map((item) => (
          <SearchResultItemView key={item.itemId} item={item} onOpen={onOpenItem} onDelete={onDeleteItem} />
        ))}
      </search-pane-results-list>
      <Pager pageable={pageable} />
    </search-pane-results-view>
  )
}

export default SearchPaneResultsView
