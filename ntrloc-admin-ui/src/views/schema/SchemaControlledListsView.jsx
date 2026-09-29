import Button from '../../controls/button/Button'
import { useSchemaControlledListsViewModel } from './SchemaControlledListsViewModel'
import './SchemaControlledListsView.css'

function SchemaControlledListsView() {
  const { controlledLists } = useSchemaControlledListsViewModel()

  return (
    <schema-controlled-lists-view>
      <Button>New Controlled List</Button>
      {controlledLists.length === 0 && <p>No controlled lists yet.</p>}
      <ul className="schema-controlled-lists-list">
        {controlledLists.map((list) => (
          <li key={list.id}>{list.name}</li>
        ))}
      </ul>
    </schema-controlled-lists-view>
  )
}

export default SchemaControlledListsView
