import SearchPaneView from './searchpane/SearchPaneView'
import ItemPaneView from './itempane/ItemPaneView'
import './SearchContentsView.css'

function SearchContentsView({ panes = [], onRemovePane, onOpenItem, onItemDeleted }) {
  return (
    <search-contents-view>
      {panes.map((pane) =>
        pane.kind === 'item' ? (
          <ItemPaneView
            key={pane.id}
            itemTypeName={pane.itemTypeName}
            itemId={pane.itemId}
            onClose={() => onRemovePane(pane.id)}
          />
        ) : (
          <SearchPaneView
            key={pane.id}
            label={`Search ${pane.id}`}
            onClose={() => onRemovePane(pane.id)}
            onOpenItem={onOpenItem}
            onItemDeleted={onItemDeleted}
          />
        ),
      )}
    </search-contents-view>
  )
}

export default SearchContentsView
