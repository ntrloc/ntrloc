import { useState } from 'react'
import Button from '../../controls/button/Button'
import Dialog from '../../controls/dialog/Dialog'
import './item-type/PropertyEditDialog.css'
import './DefinitionActions.css'

// The top right of an item type's, trait's or controlled list's header: Edit, and for a saved one
// Delete, which asks first and then deletes it straight away -- or, while editing, Cancel and Save.
// `vm` is that editor's view model; `onEdit`, if given, starts editing in place of vm.startEditing.
function DefinitionActions({ vm, name, onEdit = vm.startEditing }) {
  const [confirming, setConfirming] = useState(false)
  if (vm.editing) {
    return (
      <definition-actions>
        <Button variant="ghost-secondary" icon="x" onClick={vm.cancel} disabled={vm.saving}>
          Cancel
        </Button>
        <Button variant="ghost-secondary" icon="check" onClick={vm.save} disabled={vm.saving || vm.conflicts.length > 0 || Boolean(vm.invalid)}>
          {vm.saving ? 'Saving…' : 'Save'}
        </Button>
      </definition-actions>
    )
  }
  return (
    <definition-actions>
      <Button variant="ghost-secondary" icon="edit" onClick={onEdit}>
        Edit
      </Button>
      {vm.deletion && (
        <Button variant="ghost-danger" icon="trash" onClick={() => setConfirming(true)}>
          Delete
        </Button>
      )}
      {confirming && (
        <DeleteDialog noun={vm.noun} name={name} deletion={vm.deletion} onCancel={() => setConfirming(false)} />
      )}
    </definition-actions>
  )
}

// Says what else goes with it, and shows why the server refused, if it does -- e.g. while there are
// still items of an item type. Once deleted, the view model has already moved on from it.
function DeleteDialog({ noun, name, deletion, onCancel }) {
  const [deleting, setDeleting] = useState(false)
  const [error, setError] = useState(null)

  async function confirm() {
    setDeleting(true)
    setError(null)
    try {
      await deletion.confirm()
    } catch (e) {
      setError(e.message)
      setDeleting(false)
    }
  }

  return (
    <Dialog
      title={`Delete ${noun} ${name}?`}
      onCancel={onCancel}
      actions={
        <>
          <Button variant="ghost-secondary" onClick={onCancel} disabled={deleting}>
            Cancel
          </Button>
          <Button variant="danger" onClick={confirm} disabled={deleting}>
            {deleting ? 'Deleting…' : 'Delete'}
          </Button>
        </>
      }
    >
      {deletion.consequence && <p className="definition-delete-consequence">{deletion.consequence}</p>}
      <p className="definition-delete-consequence">This can't be undone.</p>
      {error && (
        <p className="property-edit-error" role="alert">
          {error}
        </p>
      )}
    </Dialog>
  )
}

export default DefinitionActions
