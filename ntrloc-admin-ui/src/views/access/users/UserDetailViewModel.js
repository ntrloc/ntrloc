import { useCallback, useEffect, useState } from 'react'
import { fetchUserDetail } from '../AccessDelegate'

// One user's detail: the user, their tokens and their direct groups, and which tab is showing.
// The view is keyed on the user, so each selection starts fresh; reload refetches after an edit.
export function useUserDetailViewModel(userId) {
  const [detail, setDetail] = useState(null)
  const [status, setStatus] = useState('loading')
  const [tabKey, setTabKey] = useState('overview')

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

  return { status, detail, reload, tabKey, setTabKey }
}
