import { useState } from 'react'
import Button from '../../../controls/button/Button'
import Dialog from '../../../controls/dialog/Dialog'
import Input from '../../../controls/input/Input'
import PopoverButton from '../../../controls/popover-button/PopoverButton'
import {
  CARDINALITY_LABELS,
  CONTROLLED_LIST_TYPES,
  TYPE_LABELS,
  USAGE_LABELS,
  validCardinalities,
} from '../propertyLabels'
import ChoiceField from './ChoiceField'
import './PropertyEditDialog.css'

const capitalized = (text) => text.charAt(0).toUpperCase() + text.slice(1)

const typeOptions = Object.entries(TYPE_LABELS).map(([value, label]) => ({ value, label }))

// Creates a property (`isNew`) or edits one. Nothing here reaches the server: Apply hands the values
// back to the item type's pending edits, which go out with everything else on Save. A saved
// property's type can't be changed, since existing items already hold values of it; a new one's
// can, and the type decides which cardinalities and controlled lists are on offer. A property must
// have a name, so Apply waits for one. `controlledLists` is every list, as { id, name, valueType }.
function PropertyEditDialog({ property, isNew = false, controlledLists, onApply, onCancel }) {
  const [draft, setDraft] = useState(() => ({
    name: property.name,
    description: property.description,
    type: property.type,
    cardinality: property.cardinality,
    usage: property.usage,
    controlledListId: property.controlledListId,
  }))
  // The missing-name message waits until the name has been typed in, so a new property's dialog
  // doesn't open with an error showing.
  const [nameTouched, setNameTouched] = useState(false)
  const set = (key) => (value) => setDraft((current) => ({ ...current, [key]: value }))

  // A new type keeps only the cardinality and list it can still have.
  const setType = (type) =>
    setDraft((current) => ({
      ...current,
      type,
      cardinality: validCardinalities(type).includes(current.cardinality) ? current.cardinality : 'SINGLE',
      controlledListId: controlledLists.find((list) => list.id === current.controlledListId)?.valueType === type
        ? current.controlledListId
        : null,
    }))

  const typeLabel = TYPE_LABELS[draft.type] ?? draft.type
  const listOptions = [
    { value: '', label: 'None' },
    ...controlledLists
      .filter((list) => list.valueType === draft.type)
      .map((list) => ({ value: list.id, label: list.name })),
  ]
  const cardinalityOptions = validCardinalities(draft.type).map((value) => ({ value, label: CARDINALITY_LABELS[value] }))
  const usageOptions = Object.entries(USAGE_LABELS).map(([value, label]) => ({ value, label: capitalized(label) }))
  const nameMissing = draft.name.trim() === ''

  return (
    <Dialog
      title={isNew ? 'New property' : `Edit property ${property.name}`}
      onCancel={onCancel}
      actions={
        <>
          <Button variant="ghost-secondary" onClick={onCancel}>
            Cancel
          </Button>
          <Button
            variant="secondary"
            disabled={nameMissing}
            onClick={() => onApply({ ...draft, name: draft.name.trim(), description: draft.description.trim() })}
          >
            Apply
          </Button>
        </>
      }
    >
      <property-edit-fields>
        <Input
          name="Name"
          autoFocus
          value={draft.name}
          onChange={(e) => {
            setNameTouched(true)
            set('name')(e.target.value)
          }}
        />
        <Input name="Description" value={draft.description} onChange={(e) => set('description')(e.target.value)} />
        {isNew ? (
          <ChoiceField name="Type" options={typeOptions} value={draft.type} onChange={setType} />
        ) : (
          <Input name="Type" editable={false} value={typeLabel} />
        )}
        {CONTROLLED_LIST_TYPES.includes(draft.type) ? (
          <ChoiceField
            name="Controlled list"
            options={listOptions}
            value={draft.controlledListId ?? ''}
            onChange={(value) => set('controlledListId')(value || null)}
          />
        ) : (
          <PopoverButton name="Controlled list" editable={false} summary={`Not available for ${typeLabel}`} />
        )}
        <ChoiceField name="Cardinality" options={cardinalityOptions} value={draft.cardinality} onChange={set('cardinality')} />
        <ChoiceField name="Usage" options={usageOptions} value={draft.usage} onChange={set('usage')} />
      </property-edit-fields>
      {nameMissing && nameTouched && <p className="property-edit-error">A property needs a name.</p>}
    </Dialog>
  )
}

export default PropertyEditDialog
