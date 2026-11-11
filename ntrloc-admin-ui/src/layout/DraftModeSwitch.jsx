import { useEffect, useState } from 'react'
import Button from '../controls/button/Button'
import PopoverButton from '../controls/popover-button/PopoverButton'
import OptionList from '../controls/option-list/OptionList'
import { useDraftMode } from '../global/viewmodel/useDraftMode'
import NewDraftDialog from './NewDraftDialog'
import CloseDraftDialog from './CloseDraftDialog'
import ParticipantsDialog from './ParticipantsDialog'
import ReconcileDialog from './ReconcileDialog'
import ModifiedDot from '../controls/modified-dot/ModifiedDot'
import './DraftModeSwitch.css'

const LIVE = 'live'

// The popover's content: live, plus every draft this user can work in, and for admins, ways to manage
// the current draft's participants, to apply or discard it, and to start a new one. The list is fetched each time it opens, so a draft started or joined since shows up without
// a reload.
function DraftOptions({ close, onNewDraft, onCloseDraft, onParticipants, onReconcile }) {
  const { draft, drafts, canStartDrafts, loading, error, loadDrafts, enterDraft, leaveDraft, needsReconciling, notices, reloadChanges } = useDraftMode()

  useEffect(() => {
    void loadDrafts()
    reloadChanges()
  }, [loadDrafts, reloadChanges])

  function choose(value) {
    if (value === LIVE) leaveDraft()
    else enterDraft(drafts.find((d) => d.id === value))
    close()
  }

  const options = [{ value: LIVE, label: 'Live' }, ...drafts.map((d) => ({ value: d.id, label: d.name }))]
  return (
    <draft-mode-options>
      <OptionList options={options} value={draft?.id ?? LIVE} onChange={choose} />
      {loading && <p className="draft-mode-note">Loading drafts…</p>}
      {error && <p className="draft-mode-note draft-mode-error">{error}</p>}
      {!loading && !error && drafts.length === 0 && <p className="draft-mode-note">No drafts available.</p>}
      {canStartDrafts && draft && (
        <draft-mode-actions>
          <Button
            variant="ghost-secondary"
            onClick={() => {
              close()
              onParticipants()
            }}
          >
            Participants…
          </Button>
          {needsReconciling + notices.length > 0 && (
            <Button
              variant="ghost-secondary"
              onClick={() => {
                close()
                onReconcile()
              }}
            >
              Review… ({needsReconciling + notices.length})
            </Button>
          )}
          <Button
            variant="ghost-secondary"
            onClick={() => {
              close()
              onCloseDraft('apply')
            }}
          >
            Apply draft…
          </Button>
          <Button
            variant="ghost-secondary"
            onClick={() => {
              close()
              onCloseDraft('discard')
            }}
          >
            Discard draft…
          </Button>
        </draft-mode-actions>
      )}
      {canStartDrafts && (
        <draft-mode-actions>
          <Button
            variant="ghost-secondary"
            icon="plus"
            onClick={() => {
              close()
              onNewDraft()
            }}
          >
            New draft
          </Button>
        </draft-mode-actions>
      )}
    </draft-mode-options>
  )
}

// Switches this tab between live and a draft, and shows which it's in. Shown to admins, and to a
// non-admin only while they're invited to a draft.
function DraftModeSwitch() {
  const { draft, visible, changesMark } = useDraftMode()
  const [creating, setCreating] = useState(false)
  // 'apply' | 'discard' while asking
  const [closing, setClosing] = useState(null)
  const [managingParticipants, setManagingParticipants] = useState(false)
  const [reconciling, setReconciling] = useState(false)
  if (!visible) return null
  const summary = draft ? (
    <span className="draft-mode-summary">
      <span className="draft-mode-badge">Draft</span>
      {draft.name}
      {(changesMark === 'reconcile' || changesMark === 'notice') && <ModifiedDot kind={changesMark} />}
    </span>
  ) : (
    'Live'
  )

  return (
    <draft-mode-switch data-draft={draft ? true : undefined}>
      <PopoverButton summary={summary}>
        {({ close }) => (
          <DraftOptions
            close={close}
            onNewDraft={() => setCreating(true)}
            onCloseDraft={setClosing}
            onParticipants={() => setManagingParticipants(true)}
            onReconcile={() => setReconciling(true)}
          />
        )}
      </PopoverButton>
      {creating && <NewDraftDialog onClose={() => setCreating(false)} />}
      {closing && (
        <CloseDraftDialog
          action={closing}
          onClose={() => setClosing(null)}
          onReconcile={() => {
            setClosing(null)
            setReconciling(true)
          }}
        />
      )}
      {reconciling && <ReconcileDialog onClose={() => setReconciling(false)} />}
      {managingParticipants && <ParticipantsDialog onClose={() => setManagingParticipants(false)} />}
    </draft-mode-switch>
  )
}

export default DraftModeSwitch
