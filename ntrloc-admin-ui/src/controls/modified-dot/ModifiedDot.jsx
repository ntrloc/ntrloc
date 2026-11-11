import './ModifiedDot.css'

export const MODIFIED_LABEL = 'Changed in this draft'
export const RECONCILE_LABEL = 'Needs reconciling with live'
export const NOTICE_LABEL = 'Changed in live'

const LABELS = { modified: MODIFIED_LABEL, reconcile: RECONCILE_LABEL, notice: NOTICE_LABEL }

// A small dot beside anything that leads to (or is) a change the current draft makes, so an admin can
// follow the dots from anywhere down to what changed. `kind`: "modified" (amber), a change the draft
// makes; "notice" (blue), live changed something here since the draft started; "reconcile" (red), live
// has moved underneath a change the draft makes. Where several apply, the strongest shows (strongestMark).
function ModifiedDot({ kind = 'modified' }) {
  const label = LABELS[kind] ?? MODIFIED_LABEL
  return <span className="modified-dot" data-kind={kind} role="img" aria-label={label} title={label} />
}

export default ModifiedDot
