import { useState } from 'react'
import Button from '../controls/button/Button'
import Dialog from '../controls/dialog/Dialog'
import { applyDraft, discardDraft } from '../global/delegate/DraftDelegate'
import { useDraftMode } from '../global/viewmodel/useDraftMode'
import './CloseDraftDialog.css'

// What applying did, by family and outcome: [family, one, many, { outcome: label }].
const OUTCOMES = [
  ['groups', 'group change', 'group changes', { APPLIED: 'made in live', ALREADY_LIVE: 'already in live' }],
  ['memberships', 'membership change', 'membership changes', { APPLIED: 'made in live', ALREADY_LIVE: 'already in live' }],
  ['grants', 'permission', 'permissions', { APPLIED: 'changed in live', ALREADY_LIVE: 'already in live' }],
]

function outcomeLines(applied) {
  return OUTCOMES.flatMap(([family, one, many, labels]) =>
    Object.entries(labels)
      .map(([outcome, label]) => {
        const count = (applied[family] ?? []).filter((c) => c.outcome === outcome).length
        return count === 0 ? null : `${count} ${count === 1 ? one : many} ${label}`
      })
      .filter(Boolean),
  )
}

// Applies or discards this tab's draft, after asking. Applying then says what happened to its changes;
// either way the tab goes back to live. A draft with changes live has moved underneath can't be applied
// until they're reconciled (the server checks too).
function CloseDraftDialog({ action, onClose, onReconcile }) {
  const { draft, leaveDraft, loadDrafts, needsReconciling } = useDraftMode()
  // Kept from when the dialog opened: closing the draft leaves it, and draft becomes null.
  const [{ id, name }] = useState(() => draft)
  const [working, setWorking] = useState(false)
  const [error, setError] = useState(null)
  const [applied, setApplied] = useState(null)
  const applying = action === 'apply'
  const blocked = applying && needsReconciling > 0

  async function confirm() {
    setWorking(true)
    setError(null)
    try {
      if (applying) setApplied(await applyDraft(id))
      else await discardDraft(id)
      leaveDraft()
      void loadDrafts()
      if (!applying) onClose()
    } catch (e) {
      setError(e.message)
      setWorking(false)
    }
  }

  if (applied) {
    const lines = outcomeLines(applied)
    return (
      <Dialog
        title="Draft applied"
        onCancel={onClose}
        actions={
          <Button variant="secondary" onClick={onClose}>
            Done
          </Button>
        }
      >
        <p className="close-draft-text">“{name}” is applied and closed. You're back in live.</p>
        {lines.length === 0 ? (
          <p className="close-draft-text">It didn't change anything.</p>
        ) : (
          <ul className="close-draft-outcomes">
            {lines.map((line) => (
              <li key={line}>{line}</li>
            ))}
          </ul>
        )}
      </Dialog>
    )
  }

  return (
    <Dialog
      title={applying ? 'Apply draft' : 'Discard draft'}
      onCancel={onClose}
      actions={
        <>
          <Button variant="ghost-secondary" onClick={onClose} disabled={working}>
            Cancel
          </Button>
          {blocked && (
            <Button variant="ghost-secondary" onClick={onReconcile}>
              Reconcile…
            </Button>
          )}
          <Button variant={applying ? 'secondary' : 'danger'} onClick={confirm} disabled={working || blocked}>
            {applying ? 'Apply' : 'Discard'}
          </Button>
        </>
      }
    >
      <p className="close-draft-text">
        {applying
          ? `Apply “${name}”? Its changes go into live for everyone, and the draft closes.`
          : `Discard “${name}”? Its changes are thrown away, and the draft closes. Live is unchanged.`}
      </p>
      {blocked && (
        <p className="close-draft-text close-draft-error">
          {needsReconciling === 1 ? '1 change needs' : `${needsReconciling} changes need`} reconciling with live before
          this draft can be applied.
        </p>
      )}
      {error && <p className="close-draft-text close-draft-error">{error}</p>}
    </Dialog>
  )
}

export default CloseDraftDialog
