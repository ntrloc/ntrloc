import { useState } from 'react'
import Button from '../../../controls/button/Button'
import ModalPopupView from '../../../controls/modal/ModalPopupView'
import MarkerDialog from './MarkerDialog'
import { useAccessMarkersViewModel } from './AccessMarkersViewModel'
import './AccessMarkersView.css'

function AccessMarkersView({ itemTypeId, itemTypeName }) {
  const { markers, createMarker, updateMarker, deleteMarker } = useAccessMarkersViewModel(itemTypeId)
  // null = no dialog, 'create', or the marker object being edited.
  const [dialogState, setDialogState] = useState(null)
  const [pendingDelete, setPendingDelete] = useState(null)
  const [deleteError, setDeleteError] = useState(null)

  const scopeLabel = `Item type: ${itemTypeName}`

  async function handleSave({ name, description }) {
    if (dialogState === 'create') {
      await createMarker({ name, description })
    } else {
      await updateMarker(dialogState.id, { name, description })
    }
    setDialogState(null)
  }

  async function confirmDelete() {
    const marker = pendingDelete
    setPendingDelete(null)
    try {
      await deleteMarker(marker.id)
    } catch (e) {
      setDeleteError(e.message || 'Failed to delete marker.')
    }
  }

  return (
    <access-markers-view>
      {markers.length === 0 ? (
        <p className="status">No markers defined.</p>
      ) : (
        <ul className="access-markers-list">
          {markers.map((marker) => (
            <li key={marker.id}>
              <span className="access-marker-name">{marker.name}</span>
              {marker.description && <span className="access-marker-description">{marker.description}</span>}
              <span className="access-marker-actions">
                <button type="button" className="access-marker-action" onClick={() => setDialogState(marker)}>
                  Edit
                </button>
                <button type="button" className="access-marker-action" onClick={() => setPendingDelete(marker)}>
                  Delete
                </button>
              </span>
            </li>
          ))}
        </ul>
      )}

      <Button variant="secondary" onClick={() => setDialogState('create')}>
        + New Marker
      </Button>
      {deleteError && <p className="access-markers-error">{deleteError}</p>}

      {dialogState && (
        <MarkerDialog
          marker={dialogState === 'create' ? null : dialogState}
          scopeLabel={scopeLabel}
          onSave={handleSave}
          onCancel={() => setDialogState(null)}
        />
      )}

      <ModalPopupView
        open={!!pendingDelete}
        message={pendingDelete ? `Delete marker "${pendingDelete.name}"? This also removes any grants and item assignments that use it. This cannot be undone.` : ''}
        onConfirm={confirmDelete}
        onCancel={() => setPendingDelete(null)}
      />
    </access-markers-view>
  )
}

export default AccessMarkersView
