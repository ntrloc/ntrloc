import { useState } from 'react'
import { reconcileGroupChange, reconcileMembershipChange } from '../../../global/delegate/DraftDelegate'
import { useDraftMode } from '../../../global/viewmodel/useDraftMode'

// Settling group and membership items from wherever they're shown; every view showing the draft's groups
// then reads them again (draftEdited).
export function useGroupReconcile() {
  const { draft, draftEdited } = useDraftMode()
  const [working, setWorking] = useState(false)
  const [error, setError] = useState(null)

  async function run(action) {
    setWorking(true)
    setError(null)
    try {
      await action()
      draftEdited()
    } catch (e) {
      setError(e.message)
    }
    setWorking(false)
  }

  return {
    working,
    error,
    chooseForGroup: (groupId, reason, resolution) => run(() => reconcileGroupChange(draft.id, groupId, reason.field, resolution)),
    chooseForMembership: (change, resolution) => run(() => reconcileMembershipChange(draft.id, change.groupId, change.userId, resolution)),
  }
}
