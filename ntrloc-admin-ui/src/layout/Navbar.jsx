import { useDraftMode } from '../global/viewmodel/useDraftMode'
import DraftModeSwitch from './DraftModeSwitch'
import './Navbar.css'

function Navbar() {
  const { inDraft } = useDraftMode()
  return (
    <layout-navbar role="banner" data-draft={inDraft || undefined}>
      <span className="navbar-brand">ntrloc Admin</span>
      <navbar-actions>
        <DraftModeSwitch />
      </navbar-actions>
    </layout-navbar>
  )
}

export default Navbar
