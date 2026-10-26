import {
  createOidcProvider,
  deleteOidcProvider,
  fetchOidcProviders,
  testOidcIssuer,
  updateOidcProvider,
} from './SecurityDelegate'
import { useSignInProvidersViewModel } from './SignInProvidersViewModel'

// The draft's client secret starts blank: required for a new provider, and a blank one keeps the
// stored secret on an existing one.
const OIDC_API = {
  fetchAll: fetchOidcProviders,
  create: createOidcProvider,
  update: updateOidcProvider,
  remove: deleteOidcProvider,
  test: (draft) => testOidcIssuer(draft.issuerUri),
  newDraft: {
    registrationId: '',
    displayName: '',
    issuerUri: '',
    clientId: '',
    clientSecret: '',
    scopes: 'openid profile email',
    enabled: false,
  },
  draftOf: (provider) => ({ ...provider, clientSecret: '' }),
}

export function useOidcProvidersViewModel() {
  return useSignInProvidersViewModel(OIDC_API)
}
