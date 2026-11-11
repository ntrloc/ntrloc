import { useCallback, useEffect, useMemo, useState } from 'react'
import { fetchMe } from '../delegate/AccountDelegate'
import { createDraft, fetchDrafts } from '../delegate/DraftDelegate'
import { DraftModeContext } from './useDraftMode'
import { isId } from '../delegate/draftAddress'

// Whether this tab is working live or in a draft, and which draft. The mode belongs to the tab, not
// the user or the server (docs/ntrloc-system-model-notes.md §11): one tab can stay live while another
// works in a draft, so it's kept in sessionStorage, which is per tab, and the server never stores it.
// In draft mode, requests for what a draft can hold address the draft (/api/drafts/{id}/...); each
// view takes the draft's id from here and passes it to its delegate.
//
// Who sees drafts at all: admins always, and may start them; a non-admin only while invited to at
// least one, and never starts one.
const STORAGE_KEY = 'draft'

// Only { id, name } with a real draft id is kept or read back; anything else counts as live.
function asStoredDraft(value) {
  return value && isId(value.id) && typeof value.name === 'string' ? { id: value.id, name: value.name } : null
}

function readStoredDraft() {
  try {
    const stored = sessionStorage.getItem(STORAGE_KEY)
    return stored ? asStoredDraft(JSON.parse(stored)) : null
  } catch {
    return null
  }
}

function storeDraft(draft) {
  try {
    const stored = asStoredDraft(draft)
    if (stored) sessionStorage.setItem(STORAGE_KEY, JSON.stringify(stored))
    else sessionStorage.removeItem(STORAGE_KEY)
  } catch {
    // Storage unavailable: the mode still holds for this page's lifetime.
  }
}

export function DraftModeProvider({ children }) {
  // { id, name } while in a draft; null while live.
  const [draft, setDraft] = useState(readStoredDraft)
  const [drafts, setDrafts] = useState([])
  // null until known.
  const [isAdmin, setIsAdmin] = useState(null)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState(null)

  // Takes the drafts this user can work in. If this tab's draft is no longer among them (applied,
  // discarded, or the invitation withdrawn), the tab goes back to live.
  const applyDrafts = useCallback((available) => {
    setDrafts(available)
    setDraft((current) => {
      if (current && !available.some((d) => d.id === current.id)) {
        storeDraft(null)
        return null
      }
      return current
    })
  }, [])

  const loadDrafts = useCallback(async () => {
    setLoading(true)
    setError(null)
    try {
      applyDrafts(await fetchDrafts())
    } catch (e) {
      setError(e.message)
    } finally {
      setLoading(false)
    }
  }, [applyDrafts])

  useEffect(() => {
    fetchMe()
      .then((me) => setIsAdmin(me.isAdmin))
      .catch(() => setIsAdmin(false))
    fetchDrafts()
      .then(applyDrafts)
      .catch((e) => setError(e.message))
  }, [applyDrafts])

  const enterDraft = useCallback((next) => {
    const chosen = { id: next.id, name: next.name }
    storeDraft(chosen)
    setDraft(chosen)
  }, [])

  // Starts a draft and switches this tab into it. Throws if it can't be created, for the caller to
  // show.
  const startDraft = useCallback(
    async (name) => {
      enterDraft(await createDraft({ name }))
    },
    [enterDraft],
  )

  const leaveDraft = useCallback(() => {
    storeDraft(null)
    setDraft(null)
  }, [])

  const value = useMemo(
    () => ({
      draft,
      inDraft: draft !== null,
      drafts,
      // Hidden until the user is known, so a non-admin never sees it flash up.
      visible: isAdmin === true || drafts.length > 0,
      canStartDrafts: isAdmin === true,
      loading,
      error,
      loadDrafts,
      enterDraft,
      startDraft,
      leaveDraft,
    }),
    [draft, drafts, isAdmin, loading, error, loadDrafts, enterDraft, startDraft, leaveDraft],
  )
  return <DraftModeContext.Provider value={value}>{children}</DraftModeContext.Provider>
}
