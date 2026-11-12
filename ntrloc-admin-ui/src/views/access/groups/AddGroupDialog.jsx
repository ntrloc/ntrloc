import { useState } from 'react'
import Button from '../../../controls/button/Button'
import Dialog from '../../../controls/dialog/Dialog'
import Input from '../../../controls/input/Input'
import { useDraftMode } from '../../../global/viewmodel/useDraftMode'
import { createGroup } from '../AccessDelegate'
import './AddGroupDialog.css'

// A new group under `parent` (the group selected in Access): asks only for its name.
function AddGroupDialog({ parent, onCancel, onCreated }) {
  const { draft, reloadChanges } = useDraftMode()
  const [name, setName] = useState('')
  const [touched, setTouched] = useState(false)
  const [saving, setSaving] = useState(false)
  const [saveError, setSaveError] = useState(null)
  const trimmed = name.trim()
  const nameError = trimmed === '' ? 'A group needs a name.' : null

  async function create() {
    setSaving(true)
    setSaveError(null)
    try {
      const group = await createGroup(draft?.id ?? null, trimmed, parent.id)
      reloadChanges()
      onCreated(group.id)
    } catch (e) {
      setSaveError(e.message)
      setSaving(false)
    }
  }

  return (
    <Dialog
      title={`New group in ${parent.name}`}
      onCancel={onCancel}
      actions={
        <>
          <Button variant="ghost-secondary" onClick={onCancel} disabled={saving}>
            Cancel
          </Button>
          <Button variant="secondary" disabled={Boolean(nameError) || saving} onClick={create}>
            Create
          </Button>
        </>
      }
    >
      <Input
        name="Name"
        autoFocus
        value={name}
        onChange={(e) => {
          setTouched(true)
          setName(e.target.value)
        }}
      />
      {nameError && touched && <p className="add-group-message">{nameError}</p>}
      {saveError && <p className="add-group-message add-group-error">{saveError}</p>}
      <p className="add-group-hint">
        {parent.parentIds.length > 0
          ? `Its members get the grants of ${parent.name} and of every group above it.`
          : `Its members get the grants of ${parent.name}.`}
      </p>
    </Dialog>
  )
}

export default AddGroupDialog
