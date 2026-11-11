import './ModifiedDot.css'

export const MODIFIED_LABEL = 'Changed in this draft'

// A small dot in the draft colour, beside anything that leads to (or is) a change the current draft
// makes, so an admin can follow the dots from anywhere down to what changed.
function ModifiedDot() {
  return <span className="modified-dot" role="img" aria-label={MODIFIED_LABEL} title={MODIFIED_LABEL} />
}

export default ModifiedDot
