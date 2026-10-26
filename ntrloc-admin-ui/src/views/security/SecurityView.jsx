import LdapConfigView from './LdapConfigView'
import OidcProvidersView from './OidcProvidersView'
import SamlProvidersView from './SamlProvidersView'
import './SecurityView.css'

// Where an admin manages how people sign in to ntrloc: LDAP, OpenID Connect and SAML providers.
function SecurityView() {
  return (
    <security-view>
      <LdapConfigView />
      <OidcProvidersView />
      <SamlProvidersView />
    </security-view>
  )
}

export default SecurityView
