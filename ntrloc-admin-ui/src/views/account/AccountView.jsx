import { useTheme } from '../../global/viewmodel/useTheme'
import './AccountView.css'

// Where the signed-in user manages their own account -- a stub for now (no fields/settings exist
// to show yet) beyond the one real preference this app has anywhere, light/dark theme (see
// useTheme). Kept as a real routed view rather than a placeholder dialog so the sidebar link (see
// Sidebar.jsx) has somewhere real to land.
function AccountView() {
  const { theme, toggleTheme } = useTheme()
  const isDark = theme === 'dark'

  return (
    <account-view>
      <h2>Account</h2>
      <p className="status">Account settings aren't available yet.</p>

      <account-setting-row>
        <span className="account-setting-label">Dark mode</span>
        {/* role="switch" + aria-checked, not a disguised checkbox input -- matches how a toggle's
            own state should be exposed to assistive tech. data-on (not a className) mirrors the
            same boolean-attribute convention this app's other controls use (e.g.
            CollapsiblePanel's data-collapsed) for driving pure-CSS state. */}
        <button type="button" role="switch" aria-checked={isDark} aria-label="Dark mode" className="theme-switch" data-on={isDark || undefined} onClick={toggleTheme}>
          <span className="theme-switch-thumb" />
        </button>
      </account-setting-row>
    </account-view>
  )
}

export default AccountView
