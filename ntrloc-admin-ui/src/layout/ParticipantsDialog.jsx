import { useEffect, useState } from 'react'
import Button from '../controls/button/Button'
import Dialog from '../controls/dialog/Dialog'
import IconButton from '../controls/icon-button/IconButton'
import OptionList from '../controls/option-list/OptionList'
import PopoverButton from '../controls/popover-button/PopoverButton'
import {
  fetchInvitableUsers,
  fetchParticipants,
  inviteParticipant,
  uninviteParticipant,
} from '../global/delegate/DraftDelegate'
import { useDraftMode } from '../global/viewmodel/useDraftMode'
import './ParticipantsDialog.css'

// Who may work in this tab's draft besides admins: an admin invites non-admins and removes them.
// Each change is made at once; there's nothing to save.
function ParticipantsDialog({ onClose }) {
  // Kept from when the dialog opened, in case the tab leaves the draft meanwhile.
  const { draft: current } = useDraftMode()
  const [draft] = useState(() => current)
  const [participants, setParticipants] = useState(null)
  const [users, setUsers] = useState([])
  const [error, setError] = useState(null)
  // Bumped after each change, to load the list again.
  const [version, setVersion] = useState(0)

  useEffect(() => {
    Promise.all([fetchParticipants(draft.id), fetchInvitableUsers()])
      .then(([loaded, invitable]) => {
        setParticipants(loaded)
        setUsers(invitable)
      })
      .catch((e) => setError(e.message))
  }, [draft.id, version])

  async function change(action) {
    setError(null)
    try {
      await action()
    } catch (e) {
      setError(e.message)
    }
    setVersion((v) => v + 1)
  }

  const invitable = users.filter((u) => !participants?.some((p) => p.id === u.id))

  return (
    <Dialog
      title={`Participants: ${draft.name}`}
      onCancel={onClose}
      actions={
        <Button variant="secondary" onClick={onClose}>
          Done
        </Button>
      }
    >
      <p className="participants-hint">Admins can work in every draft. Invite other users to let them work in this one.</p>
      <participants-header>
        <h4>Invited</h4>
        {invitable.length > 0 && (
          <PopoverButton summary="Invite">
            {({ close }) => (
              <OptionList
                options={invitable.map((u) => ({ value: u.id, label: u.displayName }))}
                value={null}
                onChange={(userId) => {
                  close()
                  void change(() => inviteParticipant(draft.id, userId))
                }}
              />
            )}
          </PopoverButton>
        )}
      </participants-header>
      {participants === null && !error && <p className="participants-muted">Loading…</p>}
      {participants?.length === 0 && <p className="participants-muted">No one is invited.</p>}
      {participants?.length > 0 && (
        <ul className="participants-rows">
          {participants.map((p) => (
            <li key={p.id}>
              <span className="participants-row-main">
                <span>{p.displayName}</span>
                <span className="participants-muted">{p.email}</span>
              </span>
              <IconButton
                icon="x"
                label={`Remove ${p.displayName}`}
                onClick={() => void change(() => uninviteParticipant(draft.id, p.id))}
              />
            </li>
          ))}
        </ul>
      )}
      {error && <p className="participants-error">{error}</p>}
    </Dialog>
  )
}

export default ParticipantsDialog
