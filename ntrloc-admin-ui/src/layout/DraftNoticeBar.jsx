import { useState } from 'react'
import Button from '../controls/button/Button'
import ModifiedDot from '../controls/modified-dot/ModifiedDot'
import { useDraftMode } from '../global/viewmodel/useDraftMode'
import ReconcileDialog from './ReconcileDialog'
import './DraftNoticeBar.css'

const count = (n, one, many) => `${n} ${n === 1 ? one : many}`

// Under the header while this tab is in a draft and live has done something it should know about:
// changes that need reconciling (red), and live changes to review (blue). Review… opens both lists.
function DraftNoticeBar() {
  const { draft, notices, needsReconciling, canStartDrafts } = useDraftMode()
  const [reviewing, setReviewing] = useState(false)
  if (!draft || !canStartDrafts) return null
  // With nothing left, the bar goes; an open review stays until closed, rather than vanishing as its last
  // item is settled (or coming back unasked when a new one arrives).
  if (notices.length === 0 && needsReconciling === 0) return reviewing ? <ReconcileDialog onClose={() => setReviewing(false)} /> : null

  return (
    <draft-notice-bar role="status">
      {needsReconciling > 0 && (
        <span className="draft-notice-part">
          <ModifiedDot kind="reconcile" />
          {count(needsReconciling, 'change needs', 'changes need')} reconciling with live before this draft can be applied
        </span>
      )}
      {notices.length > 0 && (
        <span className="draft-notice-part">
          <ModifiedDot kind="notice" />
          {count(notices.length, 'live change', 'live changes')} to review
        </span>
      )}
      <Button variant="ghost-secondary" onClick={() => setReviewing(true)}>
        Review…
      </Button>
      {reviewing && <ReconcileDialog onClose={() => setReviewing(false)} />}
    </draft-notice-bar>
  )
}

export default DraftNoticeBar
