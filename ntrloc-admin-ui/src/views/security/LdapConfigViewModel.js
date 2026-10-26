import { useEffect, useState } from 'react'
import { fetchLdapConfig, saveLdapConfig, testLdapConfig } from './SecurityDelegate'

// The draft's password starts blank, and a blank one on save or test means "use the stored one".
function draftOf(config) {
  const { bindPasswordSet: _, updatedAt: __, ...fields } = config
  return { ...fields, password: '' }
}

// Edit mode works on a draft copy of the saved config, so Cancel just discards it. Test runs
// against the draft (unsaved values included), so a config can be checked before it's committed.
export function useLdapConfigViewModel() {
  const [status, setStatus] = useState('loading')
  const [config, setConfig] = useState(null)
  const [editing, setEditing] = useState(false)
  const [draft, setDraft] = useState(null)
  const [busy, setBusy] = useState(null)
  const [testResult, setTestResult] = useState(null)
  const [error, setError] = useState(null)

  useEffect(() => {
    fetchLdapConfig()
      .then((loaded) => {
        setConfig(loaded)
        setStatus('ready')
      })
      .catch(() => setStatus('error'))
  }, [])

  function startEditing() {
    setDraft(draftOf(config))
    setTestResult(null)
    setError(null)
    setEditing(true)
  }

  function cancelEditing() {
    setEditing(false)
    setDraft(null)
    setTestResult(null)
    setError(null)
  }

  function setField(key, value) {
    setDraft((current) => ({ ...current, [key]: value }))
  }

  async function testConnection() {
    setBusy('testing')
    setTestResult(null)
    setError(null)
    try {
      setTestResult(await testLdapConfig(draft))
    } catch (e) {
      setError(e.message)
    } finally {
      setBusy(null)
    }
  }

  async function save() {
    setBusy('saving')
    setError(null)
    try {
      setConfig(await saveLdapConfig(draft))
      setEditing(false)
      setDraft(null)
      setTestResult(null)
    } catch (e) {
      setError(e.message)
    } finally {
      setBusy(null)
    }
  }

  return {
    status,
    values: editing ? draft : config,
    passwordSet: config?.bindPasswordSet ?? false,
    editing,
    busy,
    testResult,
    error,
    startEditing,
    cancelEditing,
    setField,
    testConnection,
    save,
  }
}
