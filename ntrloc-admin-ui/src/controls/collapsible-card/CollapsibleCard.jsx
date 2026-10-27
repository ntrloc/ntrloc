import './CollapsibleCard.css'

function TriangleIcon({ expanded }) {
  return (
    <svg aria-hidden="true" className="collapsible-card-icon" viewBox="0 0 12 10" width="12" height="10" fill="currentColor">
      {expanded ? <polygon points="6 0 12 10 0 10" /> : <polygon points="0 0 12 0 6 10" />}
    </svg>
  )
}

// A bordered card with a title bar that collapses it down to just that bar. Unlike Accordion, each
// card expands and collapses on its own, so several can be open at once. Controlled: the caller
// owns expanded and onToggle. `actions` (e.g. a "New" button) sit at the far end of the title bar,
// outside the toggle, so clicking one doesn't also collapse the card.
function CollapsibleCard({ title, expanded, onToggle, actions, children }) {
  return (
    <collapsible-card data-expanded={expanded || undefined}>
      <collapsible-card-header>
        <button type="button" className="collapsible-card-toggle" aria-expanded={expanded} onClick={onToggle}>
          <TriangleIcon expanded={expanded} />
          <span className="collapsible-card-title">{title}</span>
        </button>
        {actions && <collapsible-card-actions>{actions}</collapsible-card-actions>}
      </collapsible-card-header>
      {expanded && <collapsible-card-body>{children}</collapsible-card-body>}
    </collapsible-card>
  )
}

export default CollapsibleCard
