import Button from '../../controls/button/Button'
import Input from '../../controls/input/Input'
import Switch from '../../controls/switch/Switch'
import { useLdapConfigViewModel } from './LdapConfigViewModel'

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
      <security-card>
        <h3>LDAP</h3>
        <p className="status">{status === 'loading' ? 'Loading…' : "The LDAP configuration couldn't be loaded."}</p>
      </security-card>
    )
  }

  // The password is write-only: view mode says whether one is set, edit mode starts blank.
  function displayValue({ key }) {
    if (key === 'password') return passwordSet ? '•••••••• (set)' : 'not set'
    return values[key] || '—'
  }

  function placeholder({ key, placeholder }) {
    if (key !== 'password') return placeholder
    return passwordSet ? 'blank keeps the current password' : ''
  }

  return (
    <security-card>
      <security-card-header>
        <h3>LDAP</h3>
        <span className="security-card-enabled-label">{values.enabled ? 'Enabled' : 'Disabled'}</span>
        <Switch on={values.enabled} onChange={(on) => setField('enabled', on)} label="LDAP enabled" disabled={!editing} />
      </security-card-header>

      <security-card-fields>
        {FIELDS.map((field) => (
          <security-card-field key={field.key}>
            <Input
              name={field.name}
              editing={editing}
              value={editing ? values[field.key] : displayValue(field)}
              onChange={(e) => setField(field.key, e.target.value)}
              type={field.key === 'password' ? 'password' : 'text'}
              placeholder={placeholder(field)}
              autoComplete="off"
              spellCheck={false}
            />
            {editing && field.hint && <span className="security-card-hint">{field.hint}</span>}
          </security-card-field>
        ))}
      </security-card-fields>

      {error && <p className="security-card-error">{error}</p>}

      {testResult && (
        <p className="security-card-test-result" data-ok={testResult.ok || undefined}>
          {testResult.message}
        </p>
      )}

      <security-card-actions>
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
      </security-card-actions>
    </security-card>
  )
}

export default LdapConfigView
