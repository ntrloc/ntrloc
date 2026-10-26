import SamlKeysView from './SamlKeysView'
import SignInProvidersCard from './SignInProvidersCard'
import { useSamlProvidersViewModel } from './SamlProvidersViewModel'

const FIELDS = [
  { key: 'displayName', name: 'Display name', placeholder: 'Corporate SSO', hint: 'The label on its sign-in button' },
  { key: 'metadataUri', name: 'IdP metadata URL', placeholder: 'https://idp.example.com/saml/metadata' },
  {
    key: 'emailAttribute',
    name: 'Email attribute',
    placeholder: 'email',
    hint: "Leave blank if the IdP's NameID is the email",
  },
  { key: 'displayNameAttribute', name: 'Display name attribute', placeholder: 'displayName', hint: 'Optional' },
]

// ntrloc's entity ID is its metadata URL; most IdPs can import everything from it, certificate included.
const REGISTERED = [
  {
    name: 'ntrloc metadata URL (entity ID)',
    valueOf: (p) => p.entityId,
    hint: 'Give this to the IdP: it carries the entity ID, the URLs below, and the certificate',
  },
  { name: 'ACS URL', valueOf: (p) => p.acsUrl, hint: 'Where the IdP sends people back after signing in' },
  {
    name: 'Single logout URL',
    valueOf: (p) => p.sloUrl,
    hint: 'Signing out of ntrloc signs out of the IdP too, and the IdP can sign people out of ntrloc here',
  },
]

const SOURCE = { name: 'Metadata', valueOf: (p) => p.metadataUri }

// The SAML 2.0 identity providers people can sign in with.
function SamlProvidersView() {
  const vm = useSamlProvidersViewModel()
  return (
    <SignInProvidersCard
      title="SAML"
      vm={vm}
      fields={FIELDS}
      registered={REGISTERED}
      sourceColumn={SOURCE}
      testLabel="Test metadata"
      extra={(provider) => <SamlKeysView provider={provider} onChanged={vm.providerChanged} />}
    />
  )
}

export default SamlProvidersView
