import { useState } from 'react'
import { useDraftMode } from '../../../global/viewmodel/useDraftMode'
import { revokeToken, updateUser } from '../AccessDelegate'

// The Info / Tokens tab's state and edits. Who a user is (name, email, admin, password, tokens) is
// never drafted, so in draft mode it's read-only (docs/ntrloc-system-model-notes.md §8.7, §11).

export function useUserOverviewViewModel({ detail, reload, onUserChanged }) {
  const { inDraft } = useDraftMode()
  const { user, tokens } = detail

  const [editing, setEditing] = useState(false)
  const [form, setForm] = useState(null)
  const [confirmingAdmin, setConfirmingAdmin] = useState(false)
  const [error, setError] = useState(null)
  const [resettingPassword, setResettingPassword] = useState(false)
  const [creatingToken, setCreatingToken] = useState(false)
  const [revokingToken, setRevokingToken] = useState(null)

  async function run(action) {
    setError(null)
    try {
      await action()
      await reload()
    } catch (e) {
      setError(e.message)
    }
  }

  function startEdit() {
    setForm({ displayName: user.displayName, email: user.email, isAdmin: user.isAdmin })
    setEditing(true)
  }

  function cancelEdit() {
    setEditing(false)
    setForm(null)
    setError(null)
  }

  function save({ confirmed = false } = {}) {
    // Making someone an admin, or no longer one, is weighty: admins have every permission and
    // every draft. Asked once more before saving.
    if (form.isAdmin !== user.isAdmin && !confirmed) {
      setConfirmingAdmin(true)
      return
    }
    setConfirmingAdmin(false)
    void run(async () => {
      await updateUser(user.id, { displayName: form.displayName.trim(), email: form.email.trim(), isAdmin: form.isAdmin })
      setEditing(false)
      onUserChanged()
    })
  }

  const formError = form && userFormError(form)

  return {
    user,
    tokens,
    identityEditable: !inDraft,
    inDraft,
    editing,
    form,
    setField: (field, value) => setForm((current) => ({ ...current, [field]: value })),
    formError,
    startEdit,
    cancelEdit,
    save,
    confirmingAdmin,
    cancelAdminChange: () => setConfirmingAdmin(false),
    error,
    resettingPassword,
    setResettingPassword,
    creatingToken,
    setCreatingToken,
    tokenCreated: reload,
    revokingToken,
    setRevokingToken,
    revoke: () => {
      const token = revokingToken
      setRevokingToken(null)
      void run(() => revokeToken(user.id, token.id))
    },
  }
}

function userFormError(form) {
  if (form.displayName.trim() === '') return 'A user needs a name.'
  if (form.email.trim() === '') return 'A user needs an email.'
  return null
}
