import { useCallback, useEffect, useState } from 'react'
import { useAccessChanged } from '../../../global/viewmodel/useAccessChanged'
import { fetchUserDetail } from '../AccessDelegate'

// One user's detail: the user, their tokens and their direct groups, and which tab is showing.
// The view is keyed on the user, so each selection starts fresh; reload refetches after an edit.
// `initialTab`: the tab to open on, e.g. when a draft's review dialog shows something here.
export function useUserDetailViewModel(userId, initialTab) {
  const [detail, setDetail] = useState(null)
  const [status, setStatus] = useState('loading')
  const [tabKey, setTabKey] = useState(initialTab ?? 'overview')

  const apply = useCallback((loaded) => {
    setDetail(loaded)
    setStatus('ready')
  }, [])

  useEffect(() => {
    fetchUserDetail(userId)
      .then(apply)
      .catch(() => setStatus('error'))
  }, [userId, apply])

  const reload = useCallback(() => fetchUserDetail(userId).then(apply), [userId, apply])

  useAccessChanged(() => void reload().catch(() => setStatus('error')))

  return { status, detail, reload, tabKey, setTabKey }
}
