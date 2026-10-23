import { NavLink } from 'react-router-dom'
import CollapsiblePanel from '../controls/collapsible-panel/CollapsiblePanel'
import { useSidebarViewModel } from './SidebarViewModel'
import './Sidebar.css'

function navItemClassName({ isActive }) {
  return `sidebar-nav-item${isActive ? ' active' : ''}`
}

// The page-level nav rail -- locked/collapsed/floating interaction comes from CollapsiblePanel
// (see useSidebarViewModel for how the state itself is composed); this component only supplies
// the nav content and its own item styling.
function Sidebar({ extensions = [] }) {
  const { locked, collapsed, floating, toggleLocked, onMouseEnter, onMouseLeave, schemaPath, isSchemaActive } =
    useSidebarViewModel()

  return (
    <CollapsiblePanel
      role="complementary"
      locked={locked}
      collapsed={collapsed}
      floating={floating}
      onToggleLocked={toggleLocked}
      onMouseEnter={onMouseEnter}
      onMouseLeave={onMouseLeave}
    >
      <nav className="sidebar-nav">
        <NavLink to="/search" aria-label="Search" className={navItemClassName}>
          <svg
            aria-hidden="true"
            viewBox="0 0 24 24"
            width="18"
            height="18"
            fill="none"
            stroke="currentColor"
            strokeWidth="2"
            strokeLinecap="round"
            strokeLinejoin="round"
          >
            <circle cx="11" cy="11" r="8" />
            <line x1="21" y1="21" x2="16.65" y2="16.65" />
          </svg>
          <span className="sidebar-nav-label">Search</span>
        </NavLink>
        {/* to is a remembered path (possibly deep, e.g. /schema/item-types/<id>), not a fixed
            "/schema" -- NavLink's own active-matching is prefix-based against that same `to`
            value, so it would only highlight while sitting on that exact remembered sub-path.
            Overriding isActive with our own "any /schema/* path" check keeps the link highlighted
            correctly regardless of which schema sub-route is actually current. */}
        <NavLink to={schemaPath} aria-label="Schema" className={() => navItemClassName({ isActive: isSchemaActive })}>
          <svg
            aria-hidden="true"
            viewBox="0 0 24 24"
            width="18"
            height="18"
            fill="none"
            stroke="currentColor"
            strokeWidth="2"
            strokeLinecap="round"
            strokeLinejoin="round"
          >
            <rect x="3" y="3" width="7" height="7" />
            <rect x="14" y="3" width="7" height="7" />
            <rect x="3" y="14" width="7" height="7" />
            <rect x="14" y="14" width="7" height="7" />
          </svg>
          <span className="sidebar-nav-label">Schema</span>
        </NavLink>
        <NavLink to="/access" aria-label="Access" className={navItemClassName}>
          <svg
            aria-hidden="true"
            viewBox="0 0 24 24"
            width="18"
            height="18"
            fill="none"
            stroke="currentColor"
            strokeWidth="2"
            strokeLinecap="round"
            strokeLinejoin="round"
          >
            <rect x="3" y="11" width="18" height="11" rx="2" />
            <path d="M7 11V7a5 5 0 0 1 10 0v4" />
          </svg>
          <span className="sidebar-nav-label">Access</span>
        </NavLink>
        {extensions.map(({ navPath, navLabel }) => (
          <NavLink key={navPath} to={navPath} aria-label={navLabel} className={navItemClassName}>
            <span className="sidebar-nav-label">{navLabel}</span>
          </NavLink>
        ))}
      </nav>
      {/* A second top-level flex child of collapsible-panel-content (see CollapsiblePanel.jsx),
          not a second group inside the nav above -- margin-top: auto (see Sidebar.css) only pushes
          it to the bottom of the panel because it's a sibling flex item there, not nested inside
          sidebar-nav's own column. */}
      <nav className="sidebar-nav sidebar-nav-bottom">
        <NavLink to="/account" aria-label="Account" className={navItemClassName}>
          <svg
            aria-hidden="true"
            viewBox="0 0 24 24"
            width="18"
            height="18"
            fill="none"
            stroke="currentColor"
            strokeWidth="2"
            strokeLinecap="round"
            strokeLinejoin="round"
          >
            <path d="M20 21v-2a4 4 0 0 0-4-4H8a4 4 0 0 0-4 4v2" />
            <circle cx="12" cy="7" r="4" />
          </svg>
          <span className="sidebar-nav-label">Account</span>
        </NavLink>
      </nav>
    </CollapsiblePanel>
  )
}

export default Sidebar
