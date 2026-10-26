import LdapConfigView from './LdapConfigView'
import OidcProvidersView from './OidcProvidersView'
import './SecurityView.css'

// Where an admin manages how people sign in to ntrloc: LDAP and OpenID Connect providers.
function SecurityView() {
  return (
    <security-view>
      <LdapConfigView />
      <OidcProvidersView />
    </security-view>
  )
}

export default SecurityView
