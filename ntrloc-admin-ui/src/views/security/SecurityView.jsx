import LdapConfigView from './LdapConfigView'
import './SecurityView.css'

// Where an admin manages how people sign in to ntrloc. Only LDAP so far.
function SecurityView() {
  return (
    <security-view>
      <h2>Security</h2>
      <LdapConfigView />
    </security-view>
  )
}

export default SecurityView
