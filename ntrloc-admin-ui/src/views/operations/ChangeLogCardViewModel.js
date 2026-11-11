import { useEffect, useState } from 'react'
import { fetchChangeLog, saveChangeLogRetention } from './OperationsDelegate'

// The changelog card: what the log holds, how long it keeps entries, and editing that. In the field,
// blank means keep everything; otherwise a whole number of days, at least one.
export function useChangeLogCardViewModel() {
  const [settings, setSettings] = useState(null)
  const [status, setStatus] = useState('loading')
  const [editing, setEditing] = useState(false)
  const [days, setDays] = useState('')
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState(null)

  useEffect(() => {
    fetchChangeLog()
      .then((loaded) => {
        setSettings(loaded)
        setStatus('ready')
      })
      .catch(() => setStatus('error'))
  }, [])

  const trimmed = days.trim()
  const daysError = trimmed === '' || (/^\d+$/.test(trimmed) && Number(trimmed) >= 1) ? null : 'A whole number of days, at least 1, or blank to keep everything.'

  function startEditing() {
    setDays(settings.retentionDays == null ? '' : String(settings.retentionDays))
    setError(null)
    setEditing(true)
  }

  async function save() {
    setSaving(true)
    setError(null)
    try {
      setSettings(await saveChangeLogRetention(trimmed === '' ? null : Number(trimmed)))
      setEditing(false)
    } catch (e) {
      setError(e.message)
    }
    setSaving(false)
  }

  return { status, settings, editing, days, setDays, daysError, saving, error, startEditing, cancel: () => setEditing(false), save }
}
