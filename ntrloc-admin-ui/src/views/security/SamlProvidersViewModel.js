import {
  createSamlProvider,
  deleteSamlProvider,
  fetchSamlProviders,
  testSamlMetadata,
  updateSamlProvider,
} from './SecurityDelegate'
import { useSignInProvidersViewModel } from './SignInProvidersViewModel'

const SAML_API = {
  fetchAll: fetchSamlProviders,
  create: createSamlProvider,
  update: updateSamlProvider,
  remove: deleteSamlProvider,
  test: (draft) => testSamlMetadata(draft.metadataUri),
  newDraft: {
    registrationId: '',
    displayName: '',
    metadataUri: '',
    emailAttribute: '',
    displayNameAttribute: '',
    enabled: false,
  },
  draftOf: (provider) => ({ ...provider }),
}

export function useSamlProvidersViewModel() {
  return useSignInProvidersViewModel(SAML_API)
}
