import { NavLink } from 'react-router-dom'
import { useSidebarViewModel } from './SidebarViewModel'
import './Sidebar.css'

function navItemClassName({ isActive }) {
  return `sidebar-nav-item${isActive ? ' active' : ''}`
}

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

// Three states: locked (default -- full width, pushes content), collapsed (narrow rail, frees up
// horizontal space for content) and floating (collapsed's rail hovered -- temporarily widens back
// out as an overlay, without reflowing content, so the user can switch views without committing
// to re-locking). Which of the three is active lives in useSidebarViewModel, not here.
function Sidebar({ extensions = [] }) {
  const { locked, collapsed, floating, toggleLocked, onMouseEnter, onMouseLeave } = useSidebarViewModel()

  return (
    <layout-sidebar
      role="complementary"
      data-collapsed={collapsed || undefined}
      data-floating={floating || undefined}
      onMouseEnter={onMouseEnter}
      onMouseLeave={onMouseLeave}
    >
      <sidebar-panel>
        <button
          type="button"
          className="sidebar-toggle"
          onClick={toggleLocked}
          title={locked ? 'Collapse' : 'Lock open'}
          aria-label={locked ? 'Collapse sidebar' : 'Lock sidebar open'}
        >
          <ChevronIcon direction={locked ? 'left' : 'right'} />
        </button>
        <nav className="sidebar-nav">
          <NavLink to="/search" className={navItemClassName}>
            <svg
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
          <NavLink to="/schema" className={navItemClassName}>
            <svg
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
          {extensions.map(({ navPath, navLabel }) => (
            <NavLink key={navPath} to={navPath} className={navItemClassName}>
              <span className="sidebar-nav-label">{navLabel}</span>
            </NavLink>
          ))}
        </nav>
      </sidebar-panel>
    </layout-sidebar>
  )
}

export default Sidebar
