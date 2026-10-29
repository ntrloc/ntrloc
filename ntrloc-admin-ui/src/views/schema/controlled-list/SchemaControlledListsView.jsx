import { useState } from 'react'
import Button from '../../../controls/button/Button'
import NewDefinitionDialog from '../NewDefinitionDialog'
import { useSchemaControlledListsViewModel } from './SchemaControlledListsViewModel'
import './SchemaControlledListsView.css'

// Like the item types and traits lists: `dirtyControlledListIds` are marked as having unsaved
// changes, `drafts` are new lists not yet saved, and onCreateControlledList starts one from the
// name the New Controlled List dialog asks for.
function SchemaControlledListsView({
  selectedControlledListId,
  onSelectControlledList,
  dirtyControlledListIds,
  drafts,
  onCreateControlledList,
}) {
  const { controlledLists, nameTaken } = useSchemaControlledListsViewModel(drafts)
  const [creating, setCreating] = useState(false)

  return (
    <schema-controlled-lists-view>
      <Button onClick={() => setCreating(true)}>New Controlled List</Button>
      {creating && (
        <NewDefinitionDialog
          noun="controlled list"
          nameTaken={nameTaken}
          onAdd={(name) => {
            setCreating(false)
            onCreateControlledList(name)
          }}
          onCancel={() => setCreating(false)}
        />
      )}
      {controlledLists.length === 0 && <p>No controlled lists yet.</p>}
      <ul className="schema-controlled-lists-list">
        {controlledLists.map((list) => (
          <li key={list.id}>
            <button
              type="button"
              className="schema-controlled-lists-list-item"
              aria-current={list.id === selectedControlledListId || undefined}
              aria-label={dirtyControlledListIds.has(list.id) ? `${list.name} (unsaved changes)` : undefined}
              onClick={() => onSelectControlledList(list.id)}
            >
              {list.name}
              {dirtyControlledListIds.has(list.id) && (
                <span className="schema-controlled-lists-unsaved" aria-hidden="true" title="Unsaved changes" />
              )}
            </button>
          </li>
        ))}
      </ul>
    </schema-controlled-lists-view>
  )
}

export default SchemaControlledListsView
