import Button from '../../controls/button/Button'
import { useSchemaTraitsViewModel } from './SchemaTraitsViewModel'
import './SchemaTraitsView.css'

function SchemaTraitsView() {
  const { traits } = useSchemaTraitsViewModel()

  return (
    <schema-traits-view>
      <Button>New Trait</Button>
      {traits.length === 0 && <p>No traits yet.</p>}
      <ul className="schema-traits-list">
        {traits.map((trait) => (
          <li key={trait.id}>{trait.name}</li>
        ))}
      </ul>
    </schema-traits-view>
  )
}

export default SchemaTraitsView
