import { useState } from 'react'
import Button from '../../../controls/button/Button'
import Dialog from '../../../controls/dialog/Dialog'
import { deleteGroup } from '../AccessDelegate'
import './DeleteGroupDialog.css'

// Asks before deleting a group, saying what goes with it: its grants, and every subgroup below it with
// theirs. Only a dialog that has listed subgroups asks the server to delete them (force); if some
// appeared since, the server refuses and the dialog says so.
function DeleteGroupDialog({ group, subgroups, onCancel, onDeleted }) {
  const [deleting, setDeleting] = useState(false)
  const [error, setError] = useState(null)

  async function confirm() {
    setDeleting(true)
    setError(null)
    try {
      await deleteGroup(group.id, subgroups.length > 0)
      onDeleted()
    } catch (e) {
      setError(e.message)
      setDeleting(false)
    }
  }

  return (
    <Dialog
      title={`Delete group ${group.name}?`}
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
      <p className="delete-group-consequence">Its members leave it, and its grants are deleted.</p>
      {subgroups.length > 0 && (
        <>
          <p className="delete-group-consequence">
            {subgroups.length === 1 ? 'Its subgroup is' : `Its ${subgroups.length} subgroups are`} deleted too, with
            their grants:
          </p>
          <ul className="delete-group-subgroups">
            {subgroups.map((g) => (
              <li key={g.id}>{g.name}</li>
            ))}
          </ul>
        </>
      )}
      <p className="delete-group-consequence">This can't be undone.</p>
      {error && (
        <p className="delete-group-error" role="alert">
          {error}
        </p>
      )}
    </Dialog>
  )
}

export default DeleteGroupDialog
