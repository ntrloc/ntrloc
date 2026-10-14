import { useAccessViewModel } from './AccessViewModel'
import './AccessView.css'

// Group/User permissions management -- a stub for now, same reasoning as AccountView: no content
// is wired up yet, but it's a real routed view (not a placeholder dialog) so Sidebar's own nav
// link (see Sidebar.jsx) has somewhere real to land.
function AccessView() {
  useAccessViewModel()

  return (
    <access-view>
      <h2>Access</h2>
      <p className="status">Access management isn't available yet.</p>
    </access-view>
  )
}

export default AccessView
