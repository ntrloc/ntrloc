import { useState } from 'react'
import ModalPopupView from '../../../controls/modal/ModalPopupView'
import PropertyTreeView from '../PropertyTreeView'
import './SearchResultItemView.css'

function SearchResultItemView({ item, onOpen, onDelete }) {
  const [confirmingDelete, setConfirmingDelete] = useState(false)

  function handleConfirmDelete() {
    setConfirmingDelete(false)
    onDelete(item.itemId)
  }

  return (
    <search-result-item-view>
      <search-result-item-header>
        <search-result-item-identity>
          <span className="search-result-item-label">{item.displayLabel}</span>
          <span className="search-result-item-type">{item.itemType}</span>
        </search-result-item-identity>
        <search-result-item-actions>
          {item.permissions?.delete && (
            <button
              type="button"
              className="search-result-item-delete-btn"
              title="Delete"
              onClick={() => setConfirmingDelete(true)}
            >
              🗑
            </button>
          )}
          <button type="button" className="search-result-item-open-btn" title="Open" onClick={() => onOpen(item)}>
            ↗
          </button>
        </search-result-item-actions>
      </search-result-item-header>
      <PropertyTreeView properties={item.properties} />
      <ModalPopupView
        open={confirmingDelete}
        message={`Delete "${item.displayLabel}"? This can't be undone.`}
        onConfirm={handleConfirmDelete}
        onCancel={() => setConfirmingDelete(false)}
      />
    </search-result-item-view>
  )
}

export default SearchResultItemView
