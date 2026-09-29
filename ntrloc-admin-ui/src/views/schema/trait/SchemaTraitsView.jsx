import Button from '../../../controls/button/Button'
import { useSchemaTraitsViewModel } from './SchemaTraitsViewModel'
import './SchemaTraitsView.css'

function SchemaTraitsView({ selectedTraitId, onSelectTrait }) {
  const { traits } = useSchemaTraitsViewModel()

  return (
    <schema-traits-view>
      <Button>New Trait</Button>
      {traits.length === 0 && <p>No traits yet.</p>}
      <ul className="schema-traits-list">
        {traits.map((trait) => (
          <li key={trait.id}>
            <button
              type="button"
              className="schema-traits-list-item"
              aria-current={trait.id === selectedTraitId || undefined}
              onClick={() => onSelectTrait(trait.id)}
            >
              {trait.name}
            </button>
          </li>
        ))}
      </ul>
    </schema-traits-view>
  )
}

export default SchemaTraitsView
