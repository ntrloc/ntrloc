import { useEffect, useState } from 'react'
import Button from '../../../controls/button/Button'
import './MarkerDialog.css'

// Create and edit share one dialog -- marker is null for create, the marker being edited
// otherwise. Scope is fixed by the caller (whichever item type's Access Markers tab this was
// opened from) and only ever shown as a readout, never editable here: a marker's scope is what
// its own grants are validated against, so changing it after grants exist would silently
// invalidate them (same reasoning as the old vanilla-JS admin UI's edit dialog).
//
// Deliberately NOT always-mounted the way ModalPopupView is (open/return-null) -- a plain confirm
// dialog has no state of its own to worry about, but this one does (name/description), and the
// caller only ever renders an instance of this while there's an active create-or-edit in
// progress, so a fresh mount (fresh state) is exactly what reopening it for a different marker --
// or for create right after an edit -- needs.
function MarkerDialog({ marker, scopeLabel, onSave, onCancel }) {
  const [name, setName] = useState(marker?.name ?? '')
  const [description, setDescription] = useState(marker?.description ?? '')
  const [error, setError] = useState(null)
  const [saving, setSaving] = useState(false)

  useEffect(() => {
    function handleKeyDown(e) {
      if (e.key === 'Escape') onCancel()
    }
    document.addEventListener('keydown', handleKeyDown)
    return () => document.removeEventListener('keydown', handleKeyDown)
  }, [onCancel])

  async function handleSave() {
    const trimmedName = name.trim()
    if (!trimmedName) {
      setError('Name is required.')
      return
    }
    setSaving(true)
    setError(null)
    try {
      await onSave({ name: trimmedName, description: description.trim() || null })
    } catch (e) {
      setError(e.message || 'Failed to save marker.')
      setSaving(false)
    }
  }

  return (
    <marker-dialog-backdrop
      onMouseDown={(e) => {
        if (e.target === e.currentTarget) onCancel()
      }}
    >
      <marker-dialog-view role="dialog" aria-modal="true">
        <h3 className="marker-dialog-title">{marker ? 'Edit Marker' : 'New Marker'}</h3>
        <div className="marker-dialog-field">
          <label htmlFor="marker-name-input">Name</label>
          <input
            id="marker-name-input"
            autoFocus
            value={name}
            placeholder="e.g. Confidential"
            onChange={(e) => setName(e.target.value)}
          />
        </div>
        <div className="marker-dialog-field">
          <label htmlFor="marker-description-input">Description</label>
          <input
            id="marker-description-input"
            value={description}
            placeholder="Optional"
            onChange={(e) => setDescription(e.target.value)}
          />
        </div>
        <div className="marker-dialog-field">
          {/* Not a <label> -- there's no form control to associate it with, just a readout. */}
          <span className="marker-dialog-field-label">Scope</span>
          <div className="marker-dialog-scope-readout">{scopeLabel}</div>
        </div>
        {error && <p className="marker-dialog-error">{error}</p>}
        <marker-dialog-actions>
          <Button variant="secondary" onClick={onCancel} disabled={saving}>
            Cancel
          </Button>
          <Button variant="primary" onClick={handleSave} disabled={saving}>
            {marker ? 'Save' : 'Create'}
          </Button>
        </marker-dialog-actions>
      </marker-dialog-view>
    </marker-dialog-backdrop>
  )
}

export default MarkerDialog
