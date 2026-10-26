import LdapConfigView from './LdapConfigView'
import './SecurityView.css'

// Where an admin manages how people sign in to ntrloc. Only LDAP so far.
function SecurityView() {
  return (
    <security-view>
      <LdapConfigView />
    </security-view>
  )
}

export default SecurityView
