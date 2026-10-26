import Button from '../../controls/button/Button'
import Input from '../../controls/input/Input'
import Switch from '../../controls/switch/Switch'
import { useLdapConfigViewModel } from './LdapConfigViewModel'
import './LdapConfigView.css'

// Laid out in pairs that belong together: where the directory is, how ntrloc signs in to search
// it, how a login finds a person, and what's read from their entry.
const FIELDS = [
  { key: 'url', name: 'URL', placeholder: 'ldap://ldap.example.com:389' },
  { key: 'baseDn', name: 'Base DN', placeholder: 'dc=example,dc=com' },
  { key: 'bindDn', name: 'Bind DN', placeholder: 'blank for anonymous search' },
  { key: 'password', name: 'Password' },
  { key: 'userSearchBase', name: 'User search base', placeholder: 'ou=people' },
  { key: 'userSearchFilter', name: 'User search filter', placeholder: '(uid={0})', hint: '{0} is what the person types at login' },
  { key: 'emailAttribute', name: 'Email attribute', placeholder: 'mail' },
  { key: 'displayNameAttribute', name: 'Display name attribute', placeholder: 'displayName' },
]

function LdapConfigView() {
  const { status, values, passwordSet, editing, busy, testResult, error, startEditing, cancelEditing, setField, testConnection, save } =
    useLdapConfigViewModel()

  if (status !== 'ready') {
    return (
      <ldap-config-view>
        <h3>LDAP</h3>
        <p className="status">{status === 'loading' ? 'Loading…' : "The LDAP configuration couldn't be loaded."}</p>
      </ldap-config-view>
    )
  }

  // The password is write-only: view mode says whether one is set, edit mode starts blank.
  function displayValue({ key }) {
    if (key === 'password') return passwordSet ? '•••••••• (set)' : 'not set'
    return values[key] || '—'
  }

  return (
    <ldap-config-view>
      <ldap-config-header>
        <h3>LDAP</h3>
        <span className="ldap-config-enabled-label">{values.enabled ? 'Enabled' : 'Disabled'}</span>
        <Switch on={values.enabled} onChange={(on) => setField('enabled', on)} label="LDAP enabled" disabled={!editing} />
      </ldap-config-header>

      <ldap-config-fields>
        {FIELDS.map((field) => (
          <ldap-config-field key={field.key}>
            <Input
              name={field.name}
              editing={editing}
              value={editing ? values[field.key] : displayValue(field)}
              onChange={(e) => setField(field.key, e.target.value)}
              type={field.key === 'password' ? 'password' : 'text'}
              placeholder={field.key === 'password' ? (passwordSet ? 'blank keeps the current password' : '') : field.placeholder}
              autoComplete="off"
              spellCheck={false}
            />
            {editing && field.hint && <span className="ldap-config-hint">{field.hint}</span>}
          </ldap-config-field>
        ))}
      </ldap-config-fields>

      {error && <p className="ldap-config-error">{error}</p>}

      {testResult && (
        <p className="ldap-config-test-result" data-ok={testResult.ok || undefined}>
          {testResult.message}
        </p>
      )}

      <ldap-config-actions>
        {editing ? (
          <>
            <Button onClick={save} disabled={busy !== null}>
              {busy === 'saving' ? 'Saving…' : 'Save'}
            </Button>
            <Button variant="secondary" onClick={cancelEditing} disabled={busy !== null}>
              Cancel
            </Button>
            <Button variant="secondary" onClick={testConnection} disabled={busy !== null}>
              {busy === 'testing' ? 'Testing…' : 'Test connection'}
            </Button>
          </>
        ) : (
          <Button onClick={startEditing}>Edit</Button>
        )}
      </ldap-config-actions>
    </ldap-config-view>
  )
}

export default LdapConfigView
