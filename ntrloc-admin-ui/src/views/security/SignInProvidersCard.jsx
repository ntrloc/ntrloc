import Button from '../../controls/button/Button'
import Input from '../../controls/input/Input'
import ModalPopupView from '../../controls/modal/ModalPopupView'
import Switch from '../../controls/switch/Switch'

const NOT_YET_SAVED = 'Shown once the provider is saved'

// One kind of sign-in provider (OIDC, SAML): a list, and a form for adding or editing one at a
// time. vm is a useSignInProvidersViewModel. fields are the editable ones ({ key, name,
// placeholder, hint, secret }); registered are the read-only values to register at the provider
// ({ name, valueOf(provider), hint }), which the server reports once the provider is saved.
// sourceColumn is the list's column saying where the provider is ({ name, valueOf }). ExtraView, if
// given, is a component rendering more of the form for a saved provider, given { provider, vm }.
function SignInProvidersCard({ title, vm, fields, registered, sourceColumn, testLabel, ExtraView }) {
  if (vm.status !== 'ready') {
    return (
      <security-card>
        <h3>{title}</h3>
        <p className="status">{vm.status === 'loading' ? 'Loading…' : `The ${title} providers couldn't be loaded.`}</p>
      </security-card>
    )
  }

  return (
    <security-card>
      <security-card-header>
        <h3>{title}</h3>
        {!vm.editing && <Button onClick={() => vm.startEditing(null)}>Add provider</Button>}
      </security-card-header>

      {vm.providers.length === 0 && !vm.editing && <p className="status">No providers yet.</p>}

      {vm.providers.length > 0 && (
        <table className="sign-in-providers">
          <thead>
            <tr>
              <th>Name</th>
              <th>Registration ID</th>
              <th>{sourceColumn.name}</th>
              <th>Status</th>
              <th aria-label="Actions" />
            </tr>
          </thead>
          <tbody>
            {vm.providers.map((provider) => (
              <tr key={provider.registrationId} data-editing={vm.editing === provider.registrationId || undefined}>
                <td>{provider.displayName}</td>
                <td>{provider.registrationId}</td>
                <td>{sourceColumn.valueOf(provider)}</td>
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

      {vm.editing && <ProviderForm vm={vm} fields={fields} registered={registered} testLabel={testLabel} ExtraView={ExtraView} />}

      <ModalPopupView
        open={vm.confirmingDelete}
        message={`Delete ${vm.draft?.displayName}? No one will be able to sign in with it.`}
        onConfirm={vm.confirmDelete}
        onCancel={vm.cancelDelete}
      />
    </security-card>
  )
}

function ProviderForm({ vm, fields, registered, testLabel, ExtraView }) {
  const { draft, editing, busy } = vm
  const isNew = editing === 'new'
  const existing = vm.providers.find((p) => p.registrationId === editing)

  // A secret is write-only: required for a new provider, and left blank to keep the stored one.
  function placeholder({ secret, placeholder }) {
    if (!secret) return placeholder
    return isNew ? 'required' : 'blank keeps the current secret'
  }

  return (
    <sign-in-provider-form>
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
            placeholder="corporate-sso"
            autoComplete="off"
            spellCheck={false}
          />
          {isNew && <span className="security-card-hint">Lowercase letters, digits and hyphens; can't be changed later</span>}
        </security-card-field>
        {fields.map((field) => (
          <security-card-field key={field.key}>
            <Input
              name={field.name}
              editing
              value={draft[field.key]}
              onChange={(e) => vm.setField(field.key, e.target.value)}
              type={field.secret ? 'password' : 'text'}
              placeholder={placeholder(field)}
              autoComplete="off"
              spellCheck={false}
            />
            {field.hint && <span className="security-card-hint">{field.hint}</span>}
          </security-card-field>
        ))}
        {registered.map(({ name, valueOf, hint }) => (
          <security-card-field key={name}>
            <Input name={name} value={existing ? valueOf(existing) : NOT_YET_SAVED} />
            <span className="security-card-hint">{hint}</span>
          </security-card-field>
        ))}
      </security-card-fields>

      {existing && ExtraView && <ExtraView provider={existing} vm={vm} />}

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
        <Button variant="secondary" onClick={vm.test} disabled={busy !== null}>
          {busy === 'testing' ? 'Testing…' : testLabel}
        </Button>
        {!isNew && (
          <Button variant="secondary" className="security-card-delete" onClick={vm.requestDelete} disabled={busy !== null}>
            Delete
          </Button>
        )}
      </security-card-actions>
    </sign-in-provider-form>
  )
}

export default SignInProvidersCard
