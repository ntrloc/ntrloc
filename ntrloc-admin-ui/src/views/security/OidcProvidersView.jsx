import Button from '../../controls/button/Button'
import Input from '../../controls/input/Input'
import ModalPopupView from '../../controls/modal/ModalPopupView'
import Switch from '../../controls/switch/Switch'
import { useOidcProvidersViewModel } from './OidcProvidersViewModel'

const FIELDS = [
  { key: 'displayName', name: 'Display name', placeholder: 'Keycloak', hint: 'The label on its sign-in button' },
  { key: 'issuerUri', name: 'Issuer URI', placeholder: 'https://idp.example.com/realms/acme' },
  { key: 'clientId', name: 'Client ID', placeholder: 'ntrloc' },
  { key: 'clientSecret', name: 'Client secret' },
  { key: 'scopes', name: 'Scopes', placeholder: 'openid profile email', hint: 'Must include openid and email' },
]

// The OpenID Connect providers people can sign in with: a list, and a form for adding or editing
// one at a time. The redirect URI is what to register at the provider; the server only knows it
// once the registration ID is saved.
function OidcProvidersView() {
  const vm = useOidcProvidersViewModel()

  if (vm.status !== 'ready') {
    return (
      <security-card>
        <h3>OpenID Connect</h3>
        <p className="status">{vm.status === 'loading' ? 'Loading…' : "The OIDC providers couldn't be loaded."}</p>
      </security-card>
    )
  }

  return (
    <security-card>
      <security-card-header>
        <h3>OpenID Connect</h3>
        {!vm.editing && <Button onClick={() => vm.startEditing(null)}>Add provider</Button>}
      </security-card-header>

      {vm.providers.length === 0 && !vm.editing && <p className="status">No providers yet.</p>}

      {vm.providers.length > 0 && (
        <table className="oidc-providers">
          <thead>
            <tr>
              <th>Name</th>
              <th>Registration ID</th>
              <th>Issuer</th>
              <th>Status</th>
              <th aria-label="Actions" />
            </tr>
          </thead>
          <tbody>
            {vm.providers.map((provider) => (
              <tr key={provider.registrationId} data-editing={vm.editing === provider.registrationId || undefined}>
                <td>{provider.displayName}</td>
                <td>{provider.registrationId}</td>
                <td>{provider.issuerUri}</td>
                <td>{provider.enabled ? 'Enabled' : 'Disabled'}</td>
                <td>
                  {!vm.editing && (
                    <Button variant="secondary" onClick={() => vm.startEditing(provider)}>
                      Edit
                    </Button>
                  )}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}

      {vm.editing && <OidcProviderForm vm={vm} />}

      <ModalPopupView
        open={vm.confirmingDelete}
        message={`Delete ${vm.draft?.displayName}? No one will be able to sign in with it.`}
        onConfirm={vm.confirmDelete}
        onCancel={vm.cancelDelete}
      />
    </security-card>
  )
}

function OidcProviderForm({ vm }) {
  const { draft, editing, busy } = vm
  const isNew = editing === 'new'
  const existing = vm.providers.find((p) => p.registrationId === editing)

  function placeholder({ key, placeholder }) {
    if (key !== 'clientSecret') return placeholder
    return isNew ? 'required' : 'blank keeps the current secret'
  }

  return (
    <oidc-provider-form>
      <security-card-header>
        <h4>{isNew ? 'New provider' : draft.displayName}</h4>
        <span className="security-card-enabled-label">{draft.enabled ? 'Enabled' : 'Disabled'}</span>
        <Switch on={draft.enabled} onChange={(on) => vm.setField('enabled', on)} label="Provider enabled" />
      </security-card-header>

      <security-card-fields>
        <security-card-field>
          <Input
            name="Registration ID"
            editing={isNew}
            value={draft.registrationId}
            onChange={(e) => vm.setField('registrationId', e.target.value)}
            placeholder="keycloak"
            autoComplete="off"
            spellCheck={false}
          />
          {isNew && <span className="security-card-hint">Lowercase letters, digits and hyphens; can't be changed later</span>}
        </security-card-field>
        {FIELDS.map((field) => (
          <security-card-field key={field.key}>
            <Input
              name={field.name}
              editing
              value={draft[field.key]}
              onChange={(e) => vm.setField(field.key, e.target.value)}
              type={field.key === 'clientSecret' ? 'password' : 'text'}
              placeholder={placeholder(field)}
              autoComplete="off"
              spellCheck={false}
            />
            {field.hint && <span className="security-card-hint">{field.hint}</span>}
          </security-card-field>
        ))}
        <security-card-field>
          <Input name="Redirect URI" value={existing ? existing.redirectUri : 'Shown once the provider is saved'} />
          <span className="security-card-hint">Register this at the provider</span>
        </security-card-field>
      </security-card-fields>

      {vm.error && <p className="security-card-error">{vm.error}</p>}

      {vm.testResult && (
        <p className="security-card-test-result" data-ok={vm.testResult.ok || undefined}>
          {vm.testResult.message}
        </p>
      )}

      <security-card-actions>
        <Button onClick={vm.save} disabled={busy !== null}>
          {busy === 'saving' ? 'Saving…' : 'Save'}
        </Button>
        <Button variant="secondary" onClick={vm.stopEditing} disabled={busy !== null}>
          Cancel
        </Button>
        <Button variant="secondary" onClick={vm.testIssuer} disabled={busy !== null}>
          {busy === 'testing' ? 'Testing…' : 'Test issuer'}
        </Button>
        {!isNew && (
          <Button variant="secondary" className="security-card-delete" onClick={vm.requestDelete} disabled={busy !== null}>
            Delete
          </Button>
        )}
      </security-card-actions>
    </oidc-provider-form>
  )
}

export default OidcProvidersView
