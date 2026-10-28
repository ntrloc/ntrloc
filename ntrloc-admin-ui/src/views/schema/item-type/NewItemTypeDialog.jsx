import { useState } from 'react'
import Button from '../../../controls/button/Button'
import Dialog from '../../../controls/dialog/Dialog'
import Input from '../../../controls/input/Input'
import './PropertyEditDialog.css'

// Asks for a new item type's name -- all it needs to be listed and edited. Nothing is saved yet:
// Add opens it in the editor, where everything else is set and saved with Save. `nameTaken` says
// whether an item type, saved or not, already has a given name.
function NewItemTypeDialog({ nameTaken, onAdd, onCancel }) {
  const [name, setName] = useState('')
  const [touched, setTouched] = useState(false)
  const trimmed = name.trim()
  let error = null
  if (trimmed === '') error = 'An item type needs a name.'
  else if (nameTaken(trimmed)) error = `There's already an item type named ${trimmed}.`

  return (
    <Dialog
      title="New item type"
      onCancel={onCancel}
      actions={
        <>
          <Button variant="ghost-secondary" onClick={onCancel}>
            Cancel
          </Button>
          <Button variant="secondary" disabled={Boolean(error)} onClick={() => onAdd(trimmed)}>
            Add
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
      {error && touched && <p className="property-edit-error">{error}</p>}
    </Dialog>
  )
}

export default NewItemTypeDialog
