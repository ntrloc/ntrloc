import { useState } from 'react'
import Button from '../../../controls/button/Button'
import Dialog from '../../../controls/dialog/Dialog'
import Input from '../../../controls/input/Input'
import { resetPassword } from '../AccessDelegate'

const MIN_LENGTH = 12

// Sets a new password for a local account. Typed twice, so a typo can't lock the user out.
function ResetPasswordDialog({ user, onClose }) {
  const [password, setPassword] = useState('')
  const [repeat, setRepeat] = useState('')
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState(null)

  let problem = null
  if (password.length < MIN_LENGTH) problem = `At least ${MIN_LENGTH} characters.`
  else if (password !== repeat) problem = "The two passwords don't match."

  async function save() {
    setSaving(true)
    setError(null)
    try {
      await resetPassword(user.id, password)
      onClose()
    } catch (e) {
      setError(e.message)
      setSaving(false)
    }
  }

  return (
    <Dialog
      title={`Reset ${user.displayName}'s password`}
      onCancel={onClose}
      actions={
        <>
          <Button variant="ghost-secondary" onClick={onClose}>
            Cancel
          </Button>
          <Button variant="secondary" disabled={Boolean(problem) || saving} onClick={save}>
            Reset password
          </Button>
        </>
      }
    >
      <user-dialog-fields>
        <Input name="New password" type="password" autoFocus value={password} onChange={(e) => setPassword(e.target.value)} />
        <Input name="Repeat it" type="password" value={repeat} onChange={(e) => setRepeat(e.target.value)} />
      </user-dialog-fields>
      {password !== '' && problem && <p className="user-dialog-message">{problem}</p>}
      {error && <p className="user-dialog-message user-dialog-error">{error}</p>}
    </Dialog>
  )
}

export default ResetPasswordDialog
