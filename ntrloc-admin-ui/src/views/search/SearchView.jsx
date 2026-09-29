import { useSearchViewModel } from './SearchViewModel'
import SearchHeaderView from './SearchHeaderView'
import SearchContentsView from './SearchContentsView'
import './SearchView.css'

function SearchView() {
  const { panes, addSearchPane, addItemPane, removePane, closeItemPanesFor } = useSearchViewModel()

  return (
    <search-view>
      <SearchHeaderView onAddSearchPane={addSearchPane} />
      <SearchContentsView
        panes={panes}
        onRemovePane={removePane}
        onOpenItem={(item) => addItemPane(item.itemType, item.itemId)}
        onItemDeleted={closeItemPanesFor}
      />
    </search-view>
  )
}

export default SearchView
