import { useState } from 'react'
import Button from '../../../controls/button/Button'
import Dialog from '../../../controls/dialog/Dialog'
import Input from '../../../controls/input/Input'
import './PropertyEditDialog.css'

// Creates a property group (`isNew`) or edits one, saved or not. As with
// PropertyEditDialog, Apply only hands the values back to the item type's pending edits, and waits
// for a name.
function GroupEditDialog({ group, isNew = false, onApply, onCancel }) {
  const [draft, setDraft] = useState(() => ({ name: group.name, description: group.description }))
  const [nameTouched, setNameTouched] = useState(false)
  const nameMissing = draft.name.trim() === ''

  return (
    <Dialog
      title={isNew && !group.name ? 'New group' : `Edit group ${group.name}`}
      onCancel={onCancel}
      actions={
        <>
          <Button variant="ghost-secondary" onClick={onCancel}>
            Cancel
          </Button>
          <Button
            variant="secondary"
            disabled={nameMissing}
            onClick={() => onApply({ name: draft.name.trim(), description: draft.description.trim() })}
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
            setDraft((current) => ({ ...current, name: e.target.value }))
          }}
        />
        <Input
          name="Description"
          value={draft.description}
          onChange={(e) => setDraft((current) => ({ ...current, description: e.target.value }))}
        />
      </property-edit-fields>
      {nameMissing && nameTouched && <p className="property-edit-error">A group needs a name.</p>}
    </Dialog>
  )
}

export default GroupEditDialog
