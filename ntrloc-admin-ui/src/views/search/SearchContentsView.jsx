import SearchPaneView from './searchpane/SearchPaneView'
import ItemPaneView from './itempane/ItemPaneView'
import './SearchContentsView.css'

function SearchContentsView({ panes = [], onRemovePane, onOpenItem, onItemDeleted, maximizedPaneId, onToggleMaximize }) {
  return (
    <search-contents-view>
      {panes.map((pane) =>
        pane.kind === 'item' ? (
          <ItemPaneView
            key={pane.id}
            itemTypeName={pane.itemTypeName}
            itemId={pane.itemId}
            onClose={() => onRemovePane(pane.id)}
            maximized={pane.id === maximizedPaneId}
            onToggleMaximize={() => onToggleMaximize(pane.id)}
          />
        ) : (
          <SearchPaneView
            key={pane.id}
            label={`Search ${pane.id}`}
            onClose={() => onRemovePane(pane.id)}
            onOpenItem={onOpenItem}
            onItemDeleted={onItemDeleted}
            maximized={pane.id === maximizedPaneId}
            onToggleMaximize={() => onToggleMaximize(pane.id)}
          />
        ),
      )}
    </search-contents-view>
  )
}

export default SearchContentsView
