import { useCallback, useEffect, useState } from 'react'
import { useAccessChanged } from '../../../global/viewmodel/useAccessChanged'
import { useDraftMode } from '../../../global/viewmodel/useDraftMode'
import { fetchUserDetail } from '../AccessDelegate'

// One user's detail: the user, their tokens and their direct groups, and which tab is showing.
// The view is keyed on the user, so each selection starts fresh; reload refetches after an edit.
// `initialTab`: the tab to open on, e.g. when a draft's review dialog shows something here.
export function useUserDetailViewModel(userId, initialTab) {
  const [detail, setDetail] = useState(null)
  const [status, setStatus] = useState('loading')
  const [tabKey, setTabKey] = useState(initialTab ?? 'overview')
  const { draft, draftRevision } = useDraftMode()
  const draftId = draft?.id ?? null

  const apply = useCallback((loaded) => {
    setDetail(loaded)
    setStatus('ready')
  }, [])

  useEffect(() => {
    fetchUserDetail(draftId, userId)
      .then(apply)
      .catch(() => setStatus('error'))
  }, [draftId, draftRevision, userId, apply])

  const reload = useCallback(() => fetchUserDetail(draftId, userId).then(apply), [draftId, userId, apply])

  useAccessChanged(() => void reload().catch(() => setStatus('error')))

  return { status, detail, reload, tabKey, setTabKey }
}
