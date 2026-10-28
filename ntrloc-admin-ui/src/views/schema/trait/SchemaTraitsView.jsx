import { useState } from 'react'
import Button from '../../../controls/button/Button'
import NewDefinitionDialog from '../NewDefinitionDialog'
import { useSchemaTraitsViewModel } from './SchemaTraitsViewModel'
import './SchemaTraitsView.css'

// `dirtyTraitIds` are the traits with unsaved changes, marked like item types with them. `drafts` are
// new traits not yet saved, listed with the rest; onCreateTrait starts one from the name the New
// Trait dialog asks for.
function SchemaTraitsView({ selectedTraitId, onSelectTrait, dirtyTraitIds, drafts, onCreateTrait }) {
  const { traits, nameTaken } = useSchemaTraitsViewModel(drafts)
  const [creating, setCreating] = useState(false)

  return (
    <schema-traits-view>
      <Button onClick={() => setCreating(true)}>New Trait</Button>
      {creating && (
        <NewDefinitionDialog
          noun="trait"
          nameTaken={nameTaken}
          onAdd={(name) => {
            setCreating(false)
            onCreateTrait(name)
          }}
          onCancel={() => setCreating(false)}
        />
      )}
      {traits.length === 0 && <p>No traits yet.</p>}
      <ul className="schema-traits-list">
        {traits.map((trait) => (
          <li key={trait.id}>
            <button
              type="button"
              className="schema-traits-list-item"
              aria-current={trait.id === selectedTraitId || undefined}
              aria-label={dirtyTraitIds.has(trait.id) ? `${trait.name} (unsaved changes)` : undefined}
              onClick={() => onSelectTrait(trait.id)}
            >
              {trait.name}
              {dirtyTraitIds.has(trait.id) && (
                <span className="schema-traits-unsaved" aria-hidden="true" title="Unsaved changes" />
              )}
            </button>
          </li>
        ))}
      </ul>
    </schema-traits-view>
  )
}

export default SchemaTraitsView
