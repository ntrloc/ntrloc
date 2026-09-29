import Button from '../../controls/button/Button'
import { useSchemaItemTypesViewModel } from './SchemaItemTypesViewModel'
import './SchemaItemTypesView.css'

function SchemaItemTypesView() {
  const { itemTypes } = useSchemaItemTypesViewModel()

  return (
    <schema-item-types-view>
      <Button>New Item Type</Button>
      {itemTypes.length === 0 && <p>No item types yet.</p>}
      <ul className="schema-item-types-list">
        {itemTypes.map((itemType) => (
          <li key={itemType.id}>{itemType.name}</li>
        ))}
      </ul>
    </schema-item-types-view>
  )
}

export default SchemaItemTypesView
