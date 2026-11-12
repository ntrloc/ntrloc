import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { fetchMe } from '../delegate/AccountDelegate'
import { acknowledgeLiveChange, createDraft, fetchDraftChanges, fetchDrafts } from '../delegate/DraftDelegate'
import { DraftModeContext } from './useDraftMode'
import { useAccessChanged } from './useAccessChanged'

// Whether this tab is working live or in a draft, and which draft. The mode belongs to the tab, not
// the user or the server (docs/ntrloc-system-model-notes.md §11): one tab can stay live while another
// works in a draft. It's held in memory only, so a reload returns the tab to live.
// In draft mode, requests for what a draft can hold address the draft (/api/drafts/{id}/...); each
// view takes the draft's id from here and passes it to its delegate.
//
// What the draft changes (for an admin) is kept here too, for the modified dots that lead to each
// change; a view that changes something in the draft calls reloadChanges, or draftEdited if other views
// showing the draft's groups should read them again (draftRevision counts these).
//
// Who sees drafts at all: admins always, and may start them; a non-admin only while invited to at
// least one, and never starts one.
const NO_CHANGES = []

// Only the latest request's answer is kept, so quick changes can't leave an older answer showing. On
// failure the dots stay as they were; a draft that has gone sends the tab back to live elsewhere.
function loadChanges(draftId, latestRequest, setChanges) {
  const request = ++latestRequest.current
  fetchDraftChanges(draftId)
    .then(({ groups, memberships, grants, notices }) => {
      if (request === latestRequest.current) setChanges({ draftId, groups, memberships, grants, notices })
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
  const [changes, setChanges] = useState({ draftId: null, groups: [], memberships: [], grants: [], notices: [] })
  const [draftRevision, setDraftRevision] = useState(0)
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

  // Live can change underneath the draft at any time: the server says so (accessChanged), and the
  // changes are read again then, as well as when the draft menu opens or a Permission grants tab loads.
  useEffect(reloadChanges, [reloadChanges])
  useAccessChanged(reloadChanges)

  const current = changes.draftId === draftId
  const grantChanges = current ? changes.grants : NO_CHANGES
  const groupChanges = current ? changes.groups : NO_CHANGES
  const membershipChanges = current ? changes.memberships : NO_CHANGES
  const notices = current ? changes.notices : NO_CHANGES
  // Each reason is an item to settle; a grant change counts once, however many reasons it has.
  const needsReconciling =
    grantChanges.filter((c) => c.reconcile.length > 0).length +
    [...groupChanges, ...membershipChanges].reduce((n, c) => n + c.reconcile.length, 0)
  const anyChanges = grantChanges.length + groupChanges.length + membershipChanges.length > 0

  // After a change to the draft's groups made where other views can't see it (e.g. the review dialog).
  const draftEdited = useCallback(() => {
    setDraftRevision((n) => n + 1)
    reloadChanges()
  }, [reloadChanges])

  // Acknowledges one live change; the notices are then read again.
  const acknowledgeNotice = useCallback(
    async (notice) => {
      await acknowledgeLiveChange(draftId, notice.latest.elementKey, notice.latest.seq)
      reloadChanges()
    },
    [draftId, reloadChanges],
  )

  const enterDraft = useCallback((next) => {
    setDraft({ id: next.id, name: next.name })
  }, [])

  // Starts a draft and switches this tab into it. Throws if it can't be created, for the caller to
  // show.
  const startDraft = useCallback(
    async (name) => {
      const created = await createDraft({ name })
      // Listed at once, not only once the list is next fetched.
      setDrafts((current) => (current.some((d) => d.id === created.id) ? current : [...current, created]))
      enterDraft(created)
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
      groupChanges,
      membershipChanges,
      // The sidebar's mark: 'reconcile' if live has moved underneath any change, 'modified' if there are any.
      changesMark: needsReconciling > 0 ? 'reconcile' : notices.length > 0 ? 'notice' : anyChanges ? 'modified' : null,
      notices,
      acknowledgeNotice,
      needsReconciling,
      reloadChanges,
      draftEdited,
      draftRevision,
    }),
    [draft, drafts, isAdmin, loading, error, loadDrafts, enterDraft, startDraft, leaveDraft, grantChanges, groupChanges, membershipChanges,
      notices, needsReconciling, anyChanges, reloadChanges, draftEdited, draftRevision, acknowledgeNotice],
  )
  return <DraftModeContext.Provider value={value}>{children}</DraftModeContext.Provider>
}
