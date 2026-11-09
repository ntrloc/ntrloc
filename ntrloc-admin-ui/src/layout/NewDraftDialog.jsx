import { useState } from 'react'
import Button from '../controls/button/Button'
import Dialog from '../controls/dialog/Dialog'
import Input from '../controls/input/Input'
import { useDraftMode } from '../global/viewmodel/useDraftMode'
import './NewDraftDialog.css'

// Starts a draft: asks for its name, creates it, and switches this tab into it.
function NewDraftDialog({ onClose }) {
  const { startDraft } = useDraftMode()
  const [name, setName] = useState('')
  const [touched, setTouched] = useState(false)
  const [saving, setSaving] = useState(false)
  const [saveError, setSaveError] = useState(null)
  const trimmed = name.trim()
  const nameError = trimmed === '' ? 'A draft needs a name.' : null

  async function create() {
    setSaving(true)
    setSaveError(null)
    try {
      await startDraft(trimmed)
      onClose()
    } catch (e) {
      setSaveError(e.message)
      setSaving(false)
    }
  }

  return (
    <Dialog
      title="New draft"
      onCancel={onClose}
      actions={
        <>
          <Button variant="ghost-secondary" onClick={onClose}>
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
      {nameError && touched && <p className="new-draft-message">{nameError}</p>}
      {saveError && <p className="new-draft-message new-draft-error">{saveError}</p>}
      <p className="new-draft-hint">Changes made in a draft stay out of live until the draft is applied.</p>
    </Dialog>
  )
}

export default NewDraftDialog
