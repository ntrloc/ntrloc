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
  // One remembered pan/zoom per item type, surviving MarkerPipelineView's own key={itemTypeId}
  // remount below -- this component (unlike that one) never itself remounts just because
  // itemTypeId changes (same instance across the whole accordion/tab's lifetime), so a stable Map
  // outlives switching away and back. useState purely for the stable identity (never calling the
  // setter -- mutated in place instead, via .set() in onViewChange below), not because its own
  // value needs to drive a re-render here: a useRef would do the same job, but reading ref.current
  // during render (for initialView below) is exactly the hazard that pattern warns against, where
  // this is just a plain object read. Deliberately separate from MarkerPipelineView's own
  // reset-on-remount state (edit mode, draft positions/edge bends) -- pan/zoom is just "where am I
  // looking," worth remembering, while a draft edit is real pending state nobody would want
  // silently following them to a different pipeline.
  const [viewByItemTypeId] = useState(() => new Map())
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
        {/* key={itemTypeId} forces a fresh MarkerPipelineView (and its ViewModel) per item type --
            without it, switching item types reuses the same component instance, so mid-edit draft
            positions/edge bends (were someone to switch away mid-edit) would otherwise leak from
            whichever pipeline was viewed previously. initialView/onViewChange thread pan/zoom
            through viewByItemTypeIdRef above instead, so that one piece of state survives the
            remount -- each pipeline comes back exactly where it was left, not re-fit from scratch. */}
        <MarkerPipelineView
          key={itemTypeId}
          itemTypeId={itemTypeId}
          initialView={viewByItemTypeId.get(itemTypeId) ?? null}
          onViewChange={(view) => viewByItemTypeId.set(itemTypeId, view)}
        />
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
