import { useState } from 'react'
import Button from '../controls/button/Button'
import Dialog from '../controls/dialog/Dialog'
import { applyDraft, discardDraft } from '../global/delegate/DraftDelegate'
import { useDraftMode } from '../global/viewmodel/useDraftMode'
import './CloseDraftDialog.css'

const OUTCOMES = [
  ['APPLIED', 'changed in live'],
  ['ALREADY_LIVE', 'already in live'],
]

function countOf(grants, outcome) {
  return grants.filter((g) => g.outcome === outcome).length
}

// Applies or discards this tab's draft, after asking. Applying then says what happened to each grant;
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
    const changed = OUTCOMES.filter(([outcome]) => countOf(applied.grants, outcome) > 0)
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
        {changed.length === 0 ? (
          <p className="close-draft-text">It didn't change any permissions.</p>
        ) : (
          <ul className="close-draft-outcomes">
            {changed.map(([outcome, label]) => {
              const count = countOf(applied.grants, outcome)
              return (
                <li key={outcome}>
                  {count} {count === 1 ? 'permission' : 'permissions'} {label}
                </li>
              )
            })}
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
