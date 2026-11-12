import { useCallback, useEffect, useState } from 'react'
import { useAccessChanged } from '../../../global/viewmodel/useAccessChanged'
import { useDraftMode } from '../../../global/viewmodel/useDraftMode'
import { fetchGroupDetail } from '../AccessDelegate'

// One group's detail: the group, its direct members, and which tab is showing. The view is keyed on
// the group, so each selection starts fresh; reload refetches after an edit.
// `initialTab`: the tab to open on, e.g. when a draft's review dialog shows something here.
export function useGroupDetailViewModel(groupId, initialTab) {
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
    fetchGroupDetail(draftId, groupId)
      .then(apply)
      .catch(() => setStatus('error'))
  }, [draftId, draftRevision, groupId, apply])

  const reload = useCallback(() => fetchGroupDetail(draftId, groupId).then(apply), [draftId, groupId, apply])

  useAccessChanged(() => void reload().catch(() => setStatus('error')))

  return { status, detail, reload, tabKey, setTabKey }
}
