import { useSearchViewModel } from './SearchViewModel'
import { useDraftMode } from '../../global/viewmodel/useDraftMode'
import SearchHeaderView from './SearchHeaderView'
import SearchContentsView from './SearchContentsView'
import './SearchView.css'

function SearchView() {
  const { panes, addSearchPane, addItemPane, removePane, closeItemPanesFor, maximizedPaneId, toggleMaximize } =
    useSearchViewModel()
  const { inDraft } = useDraftMode()

  return (
    <search-view>
      <SearchHeaderView onAddSearchPane={addSearchPane} />
      {inDraft && (
        <p className="search-draft-note">
          You're in a draft: you see items as the draft's grants allow, but items aren't drafted yet, so they're read-only
          here.
        </p>
      )}
      <SearchContentsView
        panes={panes}
        onRemovePane={removePane}
        onOpenItem={(item) => addItemPane(item.itemType, item.itemId)}
        onItemDeleted={closeItemPanesFor}
        maximizedPaneId={maximizedPaneId}
        onToggleMaximize={toggleMaximize}
      />
    </search-view>
  )
}

export default SearchView
