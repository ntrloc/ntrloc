import { useState } from 'react'
import Button from '../../../controls/button/Button'
import Dialog from '../../../controls/dialog/Dialog'
import Input from '../../../controls/input/Input'
import OptionList from '../../../controls/option-list/OptionList'
import PopoverButton from '../../../controls/popover-button/PopoverButton'
import { createToken } from '../AccessDelegate'

const EXPIRY_OPTIONS = [
  { value: '30', label: '30 days' },
  { value: '90', label: '90 days' },
  { value: '365', label: '1 year' },
  { value: 'never', label: 'Never' },
]

// Issues a personal access token for a user. The token itself is shown once, right after it's
// created, and never again -- the server keeps only its hash.
function NewTokenDialog({ user, onCreated, onClose }) {
  const [name, setName] = useState('')
  const [expiry, setExpiry] = useState('90')
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState(null)
  const [issued, setIssued] = useState(null)

  async function create() {
    setSaving(true)
    setError(null)
    try {
      const token = await createToken(user.id, { name: name.trim(), expiresInDays: expiry === 'never' ? null : Number(expiry) })
      setIssued(token)
      onCreated()
    } catch (e) {
      setError(e.message)
    } finally {
      setSaving(false)
    }
  }

  if (issued) {
    return (
      <Dialog title="Token created" onCancel={onClose} actions={<Button variant="secondary" onClick={onClose}>Done</Button>}>
        <p className="user-dialog-text">
          Copy it now: this is the only time <strong>{issued.name}</strong> will be shown.
        </p>
        <code className="user-dialog-token">{issued.token}</code>
      </Dialog>
    )
  }

  return (
    <Dialog
      title={`New token for ${user.displayName}`}
      onCancel={onClose}
      actions={
        <>
          <Button variant="ghost-secondary" onClick={onClose}>
            Cancel
          </Button>
          <Button variant="secondary" disabled={name.trim() === '' || saving} onClick={create}>
            Create token
          </Button>
        </>
      }
    >
      <user-dialog-fields>
        <Input name="Name" autoFocus placeholder="What it's for" value={name} onChange={(e) => setName(e.target.value)} />
        <PopoverButton name="Expires after" summary={EXPIRY_OPTIONS.find((o) => o.value === expiry).label}>
          {({ close }) => (
            <OptionList
              options={EXPIRY_OPTIONS}
              value={expiry}
              onChange={(value) => {
                setExpiry(value)
                close()
              }}
            />
          )}
        </PopoverButton>
      </user-dialog-fields>
      {error && <p className="user-dialog-message user-dialog-error">{error}</p>}
    </Dialog>
  )
}

export default NewTokenDialog
