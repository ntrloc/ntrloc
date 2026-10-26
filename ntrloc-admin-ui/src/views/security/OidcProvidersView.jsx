import SignInProvidersCard from './SignInProvidersCard'
import { useOidcProvidersViewModel } from './OidcProvidersViewModel'

const FIELDS = [
  { key: 'displayName', name: 'Display name', placeholder: 'Corporate SSO', hint: 'The label on its sign-in button' },
  { key: 'issuerUri', name: 'Issuer URI', placeholder: 'https://idp.example.com' },
  { key: 'clientId', name: 'Client ID', placeholder: 'ntrloc' },
  { key: 'clientSecret', name: 'Client secret', secret: true },
  { key: 'scopes', name: 'Scopes', placeholder: 'openid profile email', hint: 'Must include openid and email' },
]

const REGISTERED = [
  { name: 'Redirect URI', valueOf: (p) => p.redirectUri, hint: 'Register this at the provider' },
  {
    name: 'Post-logout redirect URI',
    valueOf: (p) => p.postLogoutRedirectUri,
    hint: 'Register this too: signing out of ntrloc signs out of the provider, which then returns here',
  },
]

const SOURCE = { name: 'Issuer', valueOf: (p) => p.issuerUri }

// The OpenID Connect providers people can sign in with.
function OidcProvidersView() {
  const vm = useOidcProvidersViewModel()
  return (
    <SignInProvidersCard
      title="OpenID Connect"
      vm={vm}
      fields={FIELDS}
      registered={REGISTERED}
      sourceColumn={SOURCE}
      testLabel="Test issuer"
    />
  )
}

export default OidcProvidersView
