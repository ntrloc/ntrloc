import { useEffect, useState } from 'react'

// A list of sign-in providers (OIDC or SAML) and one-at-a-time editing of them. api supplies the
// calls (fetchAll, create, update, remove, test) and the drafts: newDraft for a provider being
// added, draftOf(provider) for an existing one. Editing works on a draft copy, so Cancel just
// discards it; editing is the registration ID being edited, or 'new' while adding one.
export function useSignInProvidersViewModel(api) {
  const [status, setStatus] = useState('loading')
  const [providers, setProviders] = useState([])
  const [editing, setEditing] = useState(null)
  const [draft, setDraft] = useState(null)
  const [busy, setBusy] = useState(null)
  const [testResult, setTestResult] = useState(null)
  const [error, setError] = useState(null)
  const [confirmingDelete, setConfirmingDelete] = useState(false)

  useEffect(() => {
    api.fetchAll()
      .then((loaded) => {
        setProviders(loaded)
        setStatus('ready')
      })
      .catch(() => setStatus('error'))
  }, [api])

  function startEditing(provider) {
    setDraft(provider ? api.draftOf(provider) : api.newDraft)
    setEditing(provider ? provider.registrationId : 'new')
    setTestResult(null)
    setError(null)
  }

  function stopEditing() {
    setEditing(null)
    setDraft(null)
    setTestResult(null)
    setError(null)
  }

  function setField(key, value) {
    setDraft((current) => ({ ...current, [key]: value }))
  }

  // Runs one API call with the busy flag set and any failure shown as the error.
  async function run(kind, action) {
    setBusy(kind)
    setError(null)
    try {
      await action()
    } catch (e) {
      setError(e.message)
    } finally {
      setBusy(null)
    }
  }

  function test() {
    setTestResult(null)
    return run('testing', async () => setTestResult(await api.test(draft)))
  }

  function save() {
    return run('saving', async () => {
      if (editing === 'new') {
        await api.create(draft)
      } else {
        await api.update(editing, draft)
      }
      setProviders(await api.fetchAll())
      stopEditing()
    })
  }

  function confirmDelete() {
    setConfirmingDelete(false)
    return run('deleting', async () => {
      await api.remove(editing)
      setProviders(await api.fetchAll())
      stopEditing()
    })
  }

  // Swaps in a provider as an action outside the form (a SAML key change) left it.
  function providerChanged(updated) {
    setProviders((current) => current.map((p) => (p.registrationId === updated.registrationId ? updated : p)))
  }

  return {
    status,
    providers,
    providerChanged,
    editing,
    draft,
    busy,
    testResult,
    error,
    confirmingDelete,
    startEditing,
    stopEditing,
    setField,
    test,
    save,
    requestDelete: () => setConfirmingDelete(true),
    cancelDelete: () => setConfirmingDelete(false),
    confirmDelete,
  }
}
