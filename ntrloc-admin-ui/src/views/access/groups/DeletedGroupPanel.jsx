import { useState } from 'react'
import Button from '../../../controls/button/Button'
import { useDraftMode } from '../../../global/viewmodel/useDraftMode'
import { restoreGroup } from '../AccessDelegate'
import { useGrantChanges } from '../permissions/useGrantChanges'
import ReconcileItems from './ReconcileItems'
import { useGroupReconcile } from './useGroupReconcile'
import '../AccessPanels.css'
import './DeletedGroupPanel.css'

// A group the draft deletes: that, with Restore, and what live has done to it since (§13.4.1), each with
// its choices. Live still has it until the draft is applied.
function DeletedGroupPanel({ group, users, allGroups }) {
  const { draft, draftEdited } = useDraftMode()
  const reconcile = useGroupReconcile()
  const reasons = useGrantChanges().groups.reasonsFor(group.id, 'deleted')
  const [error, setError] = useState(null)
  const [working, setWorking] = useState(false)

  async function restore() {
    setWorking(true)
    setError(null)
    try {
      await restoreGroup(draft.id, group.id)
      draftEdited()
    } catch (e) {
      setError(e.message)
    }
    setWorking(false)
  }

  return (
    <deleted-group-panel>
      <p className="access-draft-note">
        Deleted in this draft. Live keeps it, with its members and grants, until the draft is applied.
      </p>
      <ReconcileItems
        reasons={reasons}
        working={reconcile.working || working}
        names={{ users, groups: allGroups, targets: { itemTypes: [], markers: [] } }}
        onChoose={(reason, choice) => void reconcile.chooseForGroup(group.id, reason, choice)}
      />
      {reasons.length === 0 && (
        <div>
          <Button variant="ghost-secondary" disabled={working} onClick={() => void restore()}>
            Restore
          </Button>
        </div>
      )}
      {(error || reconcile.error) && <p className="deleted-group-error">{error || reconcile.error}</p>}
    </deleted-group-panel>
  )
}

export default DeletedGroupPanel
