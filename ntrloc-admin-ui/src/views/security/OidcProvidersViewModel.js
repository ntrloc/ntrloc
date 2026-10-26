import { useEffect, useState } from 'react'
import {
  createOidcProvider,
  deleteOidcProvider,
  fetchOidcProviders,
  testOidcIssuer,
  updateOidcProvider,
} from './SecurityDelegate'

const NEW_PROVIDER = {
  registrationId: '',
  displayName: '',
  issuerUri: '',
  clientId: '',
  clientSecret: '',
  scopes: 'openid profile email',
  enabled: false,
}

// One provider is edited at a time, on a draft copy, so Cancel just discards it. editing is the
// registration ID being edited, or 'new' while adding one. The draft's client secret starts blank:
// required for a new provider, and a blank one keeps the stored secret on an existing one.
export function useOidcProvidersViewModel() {
  const [status, setStatus] = useState('loading')
  const [providers, setProviders] = useState([])
  const [editing, setEditing] = useState(null)
  const [draft, setDraft] = useState(null)
  const [busy, setBusy] = useState(null)
  const [testResult, setTestResult] = useState(null)
  const [error, setError] = useState(null)
  const [confirmingDelete, setConfirmingDelete] = useState(false)

  useEffect(() => {
    fetchOidcProviders()
      .then((loaded) => {
        setProviders(loaded)
        setStatus('ready')
      })
      .catch(() => setStatus('error'))
  }, [])

  function startEditing(provider) {
    setDraft(provider ? { ...provider, clientSecret: '' } : NEW_PROVIDER)
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

  function testIssuer() {
    setTestResult(null)
    return run('testing', async () => setTestResult(await testOidcIssuer(draft.issuerUri)))
  }

  function save() {
    return run('saving', async () => {
      if (editing === 'new') {
        await createOidcProvider(draft)
      } else {
        await updateOidcProvider(editing, draft)
      }
      setProviders(await fetchOidcProviders())
      stopEditing()
    })
  }

  function confirmDelete() {
    setConfirmingDelete(false)
    return run('deleting', async () => {
      await deleteOidcProvider(editing)
      setProviders(await fetchOidcProviders())
      stopEditing()
    })
  }

  return {
    status,
    providers,
    editing,
    draft,
    busy,
    testResult,
    error,
    confirmingDelete,
    startEditing,
    stopEditing,
    setField,
    testIssuer,
    save,
    requestDelete: () => setConfirmingDelete(true),
    cancelDelete: () => setConfirmingDelete(false),
    confirmDelete,
  }
}
