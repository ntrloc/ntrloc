import Switch from '../../controls/switch/Switch'
import { useTheme } from '../../global/viewmodel/useTheme'
import './AccountView.css'

// Where the signed-in user manages their own account -- a stub for now (no fields/settings exist
// to show yet) beyond the one real preference this app has anywhere, light/dark theme (see
// useTheme). Kept as a real routed view rather than a placeholder dialog so the sidebar link (see
// Sidebar.jsx) has somewhere real to land.
function AccountView() {
  const { theme, toggleTheme } = useTheme()

  return (
    <account-view>
      <h2>Account</h2>
      <p className="status">Account settings aren't available yet.</p>

      <account-setting-row>
        <span className="account-setting-label">Dark mode</span>
        <Switch on={theme === 'dark'} onChange={toggleTheme} label="Dark mode" />
      </account-setting-row>
    </account-view>
  )
}

export default AccountView
