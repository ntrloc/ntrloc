import { NavLink } from 'react-router-dom'
import './Sidebar.css'

function navItemClassName({ isActive }) {
  return `sidebar-nav-item${isActive ? ' active' : ''}`
}

function Sidebar({ extensions = [] }) {
  return (
    <layout-sidebar role="complementary">
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
          Search
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
          Schema
        </NavLink>
        {extensions.map(({ navPath, navLabel }) => (
          <NavLink key={navPath} to={navPath} className={navItemClassName}>
            {navLabel}
          </NavLink>
        ))}
      </nav>
    </layout-sidebar>
  )
}

export default Sidebar
