import { useCallback, useEffect, useState } from 'react'
import { useAccessChanged } from '../../../global/viewmodel/useAccessChanged'
import { fetchGroupDetail } from '../AccessDelegate'

// One group's detail: the group, its direct members, and which tab is showing. The view is keyed on
// the group, so each selection starts fresh; reload refetches after an edit.
// `initialTab`: the tab to open on, e.g. when a draft's review dialog shows something here.
export function useGroupDetailViewModel(groupId, initialTab) {
  const [detail, setDetail] = useState(null)
  const [status, setStatus] = useState('loading')
  const [tabKey, setTabKey] = useState(initialTab ?? 'overview')

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

  useAccessChanged(() => void reload().catch(() => setStatus('error')))

  return { status, detail, reload, tabKey, setTabKey }
}
