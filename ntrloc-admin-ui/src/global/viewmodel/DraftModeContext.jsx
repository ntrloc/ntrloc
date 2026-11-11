import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { fetchMe } from '../delegate/AccountDelegate'
import { createDraft, fetchDraftChanges, fetchDrafts } from '../delegate/DraftDelegate'
import { DraftModeContext } from './useDraftMode'

// Whether this tab is working live or in a draft, and which draft. The mode belongs to the tab, not
// the user or the server (docs/ntrloc-system-model-notes.md §11): one tab can stay live while another
// works in a draft. It's held in memory only, so a reload returns the tab to live.
// In draft mode, requests for what a draft can hold address the draft (/api/drafts/{id}/...); each
// view takes the draft's id from here and passes it to its delegate.
//
// What the draft changes (for an admin) is kept here too, for the modified dots that lead to each
// change; a view that changes something in the draft calls reloadChanges.
//
// Who sees drafts at all: admins always, and may start them; a non-admin only while invited to at
// least one, and never starts one.
const NO_CHANGES = []

// Only the latest request's answer is kept, so quick changes can't leave an older answer showing. On
// failure the dots stay as they were; a draft that has gone sends the tab back to live elsewhere.
function loadChanges(draftId, latestRequest, setChanges) {
  const request = ++latestRequest.current
  fetchDraftChanges(draftId)
    .then(({ grants }) => {
      if (request === latestRequest.current) setChanges({ draftId, grants })
    })
    .catch(() => {})
}

export function DraftModeProvider({ children }) {
  // { id, name } while in a draft; null while live.
  const [draft, setDraft] = useState(null)
  const [drafts, setDrafts] = useState([])
  // null until known.
  const [isAdmin, setIsAdmin] = useState(null)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState(null)
  // The changes as last fetched, tagged with the draft they're for.
  const [changes, setChanges] = useState({ draftId: null, grants: [] })
  const changesRequest = useRef(0)

  // Takes the drafts this user can work in. If this tab's draft is no longer among them (applied,
  // discarded, or the invitation withdrawn), the tab goes back to live.
  const applyDrafts = useCallback((available) => {
    setDrafts(available)
    setDraft((current) => (current && available.some((d) => d.id === current.id) ? current : null))
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

  const draftId = draft?.id ?? null
  const reloadChanges = useCallback(() => {
    if (draftId && isAdmin === true) loadChanges(draftId, changesRequest, setChanges)
  }, [draftId, isAdmin])

  useEffect(reloadChanges, [reloadChanges])

  const grantChanges = changes.draftId === draftId ? changes.grants : NO_CHANGES

  const enterDraft = useCallback((next) => {
    setDraft({ id: next.id, name: next.name })
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
      grantChanges,
      hasChanges: grantChanges.length > 0,
      reloadChanges,
    }),
    [draft, drafts, isAdmin, loading, error, loadDrafts, enterDraft, startDraft, leaveDraft, grantChanges, reloadChanges],
  )
  return <DraftModeContext.Provider value={value}>{children}</DraftModeContext.Provider>
}
