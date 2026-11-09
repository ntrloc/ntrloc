import { useCallback, useEffect, useState } from 'react'
import { fetchGroupDetail } from '../AccessDelegate'

// One group's detail: the group, its direct members, and which tab is showing. The view is keyed on
// the group, so each selection starts fresh; reload refetches after an edit.
export function useGroupDetailViewModel(groupId) {
  const [detail, setDetail] = useState(null)
  const [status, setStatus] = useState('loading')
  const [tabKey, setTabKey] = useState('overview')

  const apply = useCallback((loaded) => {
    setDetail(loaded)
    setStatus('ready')
  }, [])

  useEffect(() => {
    fetchGroupDetail(groupId)
      .then(apply)
      .catch(() => setStatus('error'))
  }, [groupId, apply])

  const reload = useCallback(() => fetchGroupDetail(groupId).then(apply), [groupId, apply])

  return { status, detail, reload, tabKey, setTabKey }
}
