import { useMemo } from 'react'
import { useDraftMode } from '../../../global/viewmodel/useDraftMode'
import { indexGroupChanges } from '../groups/groupChangesModel'
import { indexGrantChanges, indexNotices, strongestMark } from './grantModel'

// The current draft's grant, group and membership changes and live-change notices, merged for the trail
// of marks: each place gets the strongest mark that applies (strongestMark). None while live.
export function useGrantChanges() {
  const { grantChanges, groupChanges, membershipChanges, notices } = useDraftMode()
  return useMemo(() => {
    const changes = indexGrantChanges(grantChanges)
    const groups = indexGroupChanges(groupChanges, membershipChanges)
    const live = indexNotices(notices)
    return {
      kindMark: (kind) => strongestMark(changes.kindMark(kind), groups.kindMark(kind), live.kindMark(kind)),
      principalMark: (principal) => strongestMark(changes.principalMark(principal), groups.principalMark(principal), live.principalMark(principal)),
      // A user's or group's tab: 'overview', 'membership' or 'grants'.
      tabMark: (principal, area) =>
        area === 'grants'
          ? strongestMark(changes.principalMark(principal), live.areaMark(principal, 'grants'))
          : strongestMark(groups.areaMark(principal, area), live.areaMark(principal, area)),
      changesOf: changes.changesOf,
      noticesFor: live.noticesFor,
      live,
      groups,
    }
  }, [grantChanges, groupChanges, membershipChanges, notices])
}
