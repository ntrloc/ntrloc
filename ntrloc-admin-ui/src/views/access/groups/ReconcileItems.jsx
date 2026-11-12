import Button from '../../../controls/button/Button'
import ModifiedDot from '../../../controls/modified-dot/ModifiedDot'
import { describeNotice, describeNoticeOrigin } from '../permissions/grantModel'
import { CHOICE_LABELS, describeGroupReason } from './groupChangesModel'
import './ReconcileItems.css'

// Items to settle with live (docs/ntrloc-system-model-notes.md §13.4.1), shown beside what they're about:
// each in words with both sides, the live changes behind it, and its choices. `onChoose(reason,
// resolution)` settles one; editing the field is the other way, which the words say where it applies.
// `names`: { users, groups, targets }, for the live changes.
function ReconcileItems({ reasons, onChoose, working, names }) {
  if (reasons.length === 0) return null
  return (
    <reconcile-items>
      {reasons.map((reason) => (
        <reconcile-item key={`${reason.kind}-${reason.field}`} role="group" aria-label="Needs reconciling with live">
          <ModifiedDot kind="reconcile" />
          <span className="reconcile-item-main">
            <span>{describeGroupReason(reason)}</span>
            {reason.liveChanges.map((notice) => (
              <span key={notice.latest.elementKey} className="reconcile-item-origin">
                {describeNotice(notice, names)} — {describeNoticeOrigin(notice)}
              </span>
            ))}
            <reconcile-item-actions>
              {reason.choices.map((choice) => (
                <Button key={choice} variant="ghost-secondary" disabled={working} onClick={() => onChoose(reason, choice)}>
                  {CHOICE_LABELS[choice]}
                </Button>
              ))}
            </reconcile-item-actions>
          </span>
        </reconcile-item>
      ))}
    </reconcile-items>
  )
}

export default ReconcileItems
