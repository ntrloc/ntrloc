import Button from '../../../controls/button/Button'
import { useSchemaControlledListsViewModel } from './SchemaControlledListsViewModel'
import './SchemaControlledListsView.css'

function SchemaControlledListsView({ selectedControlledListId, onSelectControlledList }) {
  const { controlledLists } = useSchemaControlledListsViewModel()

  return (
    <schema-controlled-lists-view>
      <Button>New Controlled List</Button>
      {controlledLists.length === 0 && <p>No controlled lists yet.</p>}
      <ul className="schema-controlled-lists-list">
        {controlledLists.map((list) => (
          <li key={list.id}>
            <button
              type="button"
              className="schema-controlled-lists-list-item"
              aria-current={list.id === selectedControlledListId || undefined}
              onClick={() => onSelectControlledList(list.id)}
            >
              {list.name}
            </button>
          </li>
        ))}
      </ul>
    </schema-controlled-lists-view>
  )
}

export default SchemaControlledListsView
