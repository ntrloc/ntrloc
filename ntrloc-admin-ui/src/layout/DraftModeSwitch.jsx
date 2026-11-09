import { useEffect, useState } from 'react'
import Button from '../controls/button/Button'
import PopoverButton from '../controls/popover-button/PopoverButton'
import OptionList from '../controls/option-list/OptionList'
import { useDraftMode } from '../global/viewmodel/useDraftMode'
import NewDraftDialog from './NewDraftDialog'
import './DraftModeSwitch.css'

const LIVE = 'live'

// The popover's content: live, plus every draft this user can work in, and a way to start a new
// one. The list is fetched each time it opens, so a draft started or joined since shows up without
// a reload.
function DraftOptions({ close, onNewDraft }) {
  const { draft, drafts, canStartDrafts, loading, error, loadDrafts, enterDraft, leaveDraft } = useDraftMode()

  useEffect(() => {
    void loadDrafts()
  }, [loadDrafts])

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
  const { draft, visible } = useDraftMode()
  const [creating, setCreating] = useState(false)
  if (!visible) return null
  const summary = draft ? (
    <span className="draft-mode-summary">
      <span className="draft-mode-badge">Draft</span>
      {draft.name}
    </span>
  ) : (
    'Live'
  )

  return (
    <draft-mode-switch data-draft={draft ? true : undefined}>
      <PopoverButton summary={summary}>
        {({ close }) => <DraftOptions close={close} onNewDraft={() => setCreating(true)} />}
      </PopoverButton>
      {creating && <NewDraftDialog onClose={() => setCreating(false)} />}
    </draft-mode-switch>
  )
}

export default DraftModeSwitch
