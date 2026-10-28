import { useState } from 'react'
import Button from '../../../controls/button/Button'
import Dialog from '../../../controls/dialog/Dialog'
import Input from '../../../controls/input/Input'
import ChoiceField from './ChoiceField'
import './PropertyEditDialog.css'
import './LinkEditDialog.css'

const toDraft = (perspective) => ({
  name: perspective.name,
  description: perspective.description,
  min: String(perspective.minCardinality ?? 0),
  max: perspective.maxCardinality == null ? '' : String(perspective.maxCardinality),
})

const WHOLE_NUMBER = /^\d+$/

// What's wrong with one direction's draft, or null. A blank maximum means any number.
function problem(draft, label) {
  if (draft.name.trim() === '') return `${label} needs a name.`
  if (!WHOLE_NUMBER.test(draft.min.trim())) return `${label}'s minimum must be a whole number.`
  const max = draft.max.trim()
  if (max === '') return null
  if (!WHOLE_NUMBER.test(max) || Number(max) < 1) return `${label}'s maximum must be blank (any) or at least 1.`
  if (Number(max) < Number(draft.min)) return `${label}'s maximum can't be less than its minimum.`
  return null
}

const toValues = (draft) => ({
  name: draft.name.trim(),
  description: draft.description.trim(),
  minCardinality: Number(draft.min.trim()),
  maxCardinality: draft.max.trim() === '' ? null : Number(draft.max.trim()),
})

function DirectionFields({ heading, draft, onChange, autoFocus }) {
  const set = (key) => (e) => onChange({ ...draft, [key]: e.target.value })
  return (
    <section className="link-edit-direction">
      <h3>{heading.charAt(0).toUpperCase() + heading.slice(1)}</h3>
      <property-edit-fields>
        <Input name="Name" autoFocus={autoFocus} value={draft.name} onChange={set('name')} />
        <Input name="Description" value={draft.description} onChange={set('description')} />
        <Input name="Minimum" inputMode="numeric" value={draft.min} onChange={set('min')} />
        <Input name="Maximum" inputMode="numeric" placeholder="Any" value={draft.max} onChange={set('max')} />
      </property-edit-fields>
    </section>
  )
}

// Creates a link (`isNew`) or edits one: its two directions, this item type's own (`forward`) and
// the target's way back (`reverse`, absent for a one-way link), each with its name, description,
// and how many items it allows. Only a new link's target -- an item type or a trait -- can be
// chosen, from `targetOptions`; `targetName` names the target with a given id. Properties are added once the link is in the
// list, not here. As with PropertyEditDialog, Apply only hands the values back to the item type's
// pending edits, and waits until everything is valid. Problems show once something has been
// changed, not as soon as the dialog opens.
function LinkEditDialog({ isNew = false, sourceName, targetOptions, targetId, targetName, forward, reverse, onApply, onCancel }) {
  const [target, setTarget] = useState(targetId)
  const [forwardDraft, setForwardDraft] = useState(() => toDraft(forward))
  const [reverseDraft, setReverseDraft] = useState(() => reverse && toDraft(reverse))
  const [touched, setTouched] = useState(false)
  const touch = (setter) => (value) => {
    setTouched(true)
    setter(value)
  }

  const error =
    (isNew && !target ? 'Choose what this links to.' : null) ??
    problem(forwardDraft, 'This link') ??
    (reverseDraft && problem(reverseDraft, 'The way back'))
  const shownTarget = isNew && !target ? 'the target' : targetName(target)

  return (
    <Dialog
      title={isNew && !forward.name ? 'New link' : `Edit link ${forward.name}`}
      onCancel={onCancel}
      actions={
        <>
          <Button variant="ghost-secondary" onClick={onCancel}>
            Cancel
          </Button>
          <Button
            variant="secondary"
            disabled={Boolean(error)}
            onClick={() => onApply(toValues(forwardDraft), reverseDraft && toValues(reverseDraft), target)}
          >
            Apply
          </Button>
        </>
      }
    >
      <link-edit-fields>
        {isNew ? (
          <ChoiceField
            name="Links to"
            options={targetOptions}
            value={target}
            onChange={touch(setTarget)}
            placeholder="Choose an item type or trait"
          />
        ) : (
          <Input name="Links to" editable={false} value={shownTarget} />
        )}
        <DirectionFields
          heading={`${sourceName} to ${shownTarget}`}
          draft={forwardDraft}
          onChange={touch(setForwardDraft)}
          autoFocus
        />
        {reverseDraft && (
          <DirectionFields heading={`${shownTarget} back to ${sourceName}`} draft={reverseDraft} onChange={touch(setReverseDraft)} />
        )}
      </link-edit-fields>
      {error && touched && <p className="property-edit-error">{error}</p>}
    </Dialog>
  )
}

export default LinkEditDialog
