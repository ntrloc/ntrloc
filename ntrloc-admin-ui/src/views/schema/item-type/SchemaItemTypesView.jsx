import { useState } from 'react'
import Button from '../../../controls/button/Button'
import NewItemTypeDialog from './NewItemTypeDialog'
import { useSchemaItemTypesViewModel } from './SchemaItemTypesViewModel'
import './SchemaItemTypesView.css'

// `drafts` are new item types not yet saved, listed with the rest and marked unsaved like any item
// type with pending edits; onCreateItemType starts one from the name the New Item Type dialog asks for.
function SchemaItemTypesView({ selectedItemTypeId, onSelectItemType, dirtyItemTypeIds, drafts, onCreateItemType }) {
  const { itemTypes, nameTaken } = useSchemaItemTypesViewModel(drafts)
  const [creating, setCreating] = useState(false)

  return (
    <schema-item-types-view>
      <Button onClick={() => setCreating(true)}>New Item Type</Button>
      {creating && (
        <NewItemTypeDialog
          nameTaken={nameTaken}
          onAdd={(name) => {
            setCreating(false)
            onCreateItemType(name)
          }}
          onCancel={() => setCreating(false)}
        />
      )}
      {itemTypes.length === 0 && <p>No item types yet.</p>}
      <ul className="schema-item-types-list">
        {itemTypes.map((itemType) => (
          <li key={itemType.id}>
            <button
              type="button"
              className="schema-item-types-list-item"
              aria-current={itemType.id === selectedItemTypeId || undefined}
              aria-label={dirtyItemTypeIds.has(itemType.id) ? `${itemType.name} (unsaved changes)` : undefined}
              onClick={() => onSelectItemType(itemType.id)}
            >
              {itemType.name}
              {dirtyItemTypeIds.has(itemType.id) && (
                <span className="schema-item-types-unsaved" aria-hidden="true" title="Unsaved changes" />
              )}
            </button>
          </li>
        ))}
      </ul>
    </schema-item-types-view>
  )
}

export default SchemaItemTypesView
