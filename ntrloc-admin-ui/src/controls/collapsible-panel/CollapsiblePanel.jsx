import './CollapsiblePanel.css'

function ChevronIcon({ direction }) {
  return (
    <svg
      viewBox="0 0 24 24"
      width="16"
      height="16"
      fill="none"
      stroke="currentColor"
      strokeWidth="2"
      strokeLinecap="round"
      strokeLinejoin="round"
    >
      {direction === 'left' ? <polyline points="15 18 9 12 15 6" /> : <polyline points="9 18 15 12 9 6" />}
    </svg>
  )
}

// A panel that's either locked open (full width, pushes sibling content), collapsed (a narrow
// rail), or floating (collapsed's rail hovered -- temporarily overlays back open without
// reflowing anything else) -- see useCollapsiblePanel for the state machine itself. Controlled,
// like every other control here (locked/floating/the handlers all owned by the caller via that
// hook) -- this component only ever renders what it's told.
//
// title is optional: the nav Sidebar renders no header text of its own (just the toggle, with its
// nav links as the body), while e.g. a marker list panel names itself. Either way the toggle
// button sits in the same header row, pushed to the far side when a title is present.
function CollapsiblePanel({ title, locked, collapsed, floating, onToggleLocked, onMouseEnter, onMouseLeave, children, ...rest }) {
  return (
    <collapsible-panel
      data-collapsed={collapsed || undefined}
      data-floating={floating || undefined}
      onMouseEnter={onMouseEnter}
      onMouseLeave={onMouseLeave}
      {...rest}
    >
      <collapsible-panel-content>
        <collapsible-panel-header>
          {title && <span className="collapsible-panel-title">{title}</span>}
          <button
            type="button"
            className="collapsible-panel-toggle"
            onClick={onToggleLocked}
            title={locked ? 'Collapse' : 'Lock open'}
            aria-label={locked ? 'Collapse' : 'Lock open'}
          >
            <ChevronIcon direction={locked ? 'left' : 'right'} />
          </button>
        </collapsible-panel-header>
        {children}
      </collapsible-panel-content>
    </collapsible-panel>
  )
}

export default CollapsiblePanel
