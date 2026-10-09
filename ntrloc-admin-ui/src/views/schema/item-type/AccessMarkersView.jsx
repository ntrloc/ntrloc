import { useState } from 'react'
import Button from '../../../controls/button/Button'
import ModalPopupView from '../../../controls/modal/ModalPopupView'
import CollapsiblePanel from '../../../controls/collapsible-panel/CollapsiblePanel'
import { useCollapsiblePanel } from '../../../controls/collapsible-panel/useCollapsiblePanel'
import MarkerDialog from './MarkerDialog'
import MarkerPipelineView from './marker-pipeline/MarkerPipelineView'
import { useAccessMarkersViewModel } from './AccessMarkersViewModel'
import './AccessMarkersView.css'

// Two panes: a collapsible marker list (own lock/collapse/float state, independent of the page
// nav sidebar's) and a main area showing every marker decision pipeline bound to this item type
// (read-only; see MarkerPipelineView). There's no marker-to-pipeline relationship decided yet
// (a pipeline isn't owned by one marker -- it's bound to an item type, and its own carrier blocks
// can each reference several markers), so selecting a marker here doesn't drive the main area yet.
function AccessMarkersView({ itemTypeId, itemTypeName }) {
  const { markers, createMarker, updateMarker, deleteMarker } = useAccessMarkersViewModel(itemTypeId)
  const panel = useCollapsiblePanel(true)
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
      <CollapsiblePanel
        title="Markers"
        locked={panel.locked}
        collapsed={panel.collapsed}
        floating={panel.floating}
        onToggleLocked={panel.toggleLocked}
        onMouseEnter={panel.onMouseEnter}
        onMouseLeave={panel.onMouseLeave}
      >
        <Button variant="secondary" className="access-markers-new-button" onClick={() => setDialogState('create')}>
          + New Marker
        </Button>
        {markers.length === 0 ? (
          <p className="status">No markers defined.</p>
        ) : (
          <ul className="access-markers-list">
            {markers.map((marker) => (
              <li key={marker.id}>
                <span className="access-marker-name">{marker.name}</span>
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
        {deleteError && <p className="access-markers-error">{deleteError}</p>}
      </CollapsiblePanel>

      <access-markers-pipeline-area>
        <MarkerPipelineView itemTypeId={itemTypeId} />
      </access-markers-pipeline-area>

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
