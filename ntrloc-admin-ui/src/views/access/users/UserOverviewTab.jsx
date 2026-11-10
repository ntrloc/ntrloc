import Button from '../../../controls/button/Button'
import IconButton from '../../../controls/icon-button/IconButton'
import Input from '../../../controls/input/Input'
import ModalPopupView from '../../../controls/modal/ModalPopupView'
import Switch from '../../../controls/switch/Switch'
import { useUserOverviewViewModel } from './UserOverviewViewModel'
import NewTokenDialog from './NewTokenDialog'
import ResetPasswordDialog from './ResetPasswordDialog'
import '../AccessPanels.css'
import './UserOverviewTab.css'

const SIGN_IN_LABELS = { ldap: 'LDAP', oidc: 'OpenID Connect', saml: 'SAML' }

function formatDate(iso) {
  return new Date(iso).toLocaleDateString(undefined, { year: 'numeric', month: 'short', day: 'numeric' })
}

// Who a user is, how they sign in, and their access tokens.
function UserOverviewTab({ detail, reload, onUserChanged }) {
  const vm = useUserOverviewViewModel({ detail, reload, onUserChanged })
  const { user } = vm
  const values = vm.editing ? vm.form : user

  return (
    <user-overview>
      {vm.inDraft && (
        <p className="access-draft-note">
          You're in a draft: a user's details, password and tokens are edited live, so they're read-only
          here.
        </p>
      )}

      <user-overview-identity>
        <Input
          name="Display name"
          editable={vm.editing}
          value={values.displayName}
          onChange={(e) => vm.setField('displayName', e.target.value)}
        />
        <Input name="Email" editable={vm.editing} value={values.email} onChange={(e) => vm.setField('email', e.target.value)} />
        <Switch name="Admin" on={values.isAdmin} editable={vm.editing} onChange={(on) => vm.setField('isAdmin', on)} />
        <user-overview-identity-actions>
          {vm.editing ? (
            <>
              <Button variant="ghost-secondary" onClick={vm.cancelEdit}>
                Cancel
              </Button>
              <Button variant="secondary" disabled={Boolean(vm.formError)} onClick={() => vm.save()}>
                Save
              </Button>
            </>
          ) : (
            vm.identityEditable && (
              <Button variant="ghost-secondary" icon="edit" onClick={vm.startEdit}>
                Edit
              </Button>
            )
          )}
        </user-overview-identity-actions>
      </user-overview-identity>
      {vm.editing && vm.formError && <p className="user-dialog-message">{vm.formError}</p>}
      {vm.error && <p className="user-dialog-message user-dialog-error">{vm.error}</p>}

      <user-overview-sign-in>
        {/* signIn is null until the API says how a user signs in; a reset then applies to a local password. */}
        {user.signIn === 'local' || user.signIn == null ? (
          vm.identityEditable && (
            <Button variant="ghost-secondary" icon="key" onClick={() => vm.setResettingPassword(true)}>
              Reset password
            </Button>
          )
        ) : (
          <span className="access-muted">
            Signs in through {SIGN_IN_LABELS[user.signIn] ?? user.signIn}, so there's no password to manage here.
          </span>
        )}
      </user-overview-sign-in>

      <section className="access-panel">
        <header>
          <h3>Access tokens</h3>
          {vm.identityEditable && (
            <Button variant="ghost-secondary" icon="plus" onClick={() => vm.setCreatingToken(true)}>
              New token
            </Button>
          )}
        </header>
        {vm.tokens.length === 0 ? (
          <p className="access-muted">No tokens.</p>
        ) : (
          <ul className="access-rows">
            {vm.tokens.map((token) => (
              <li key={token.id}>
                <span className="access-row-main">
                  <span>{token.name}</span>
                  <span className="access-muted">
                    Created {formatDate(token.createdAt)} ·{' '}
                    {token.expiresAt ? `expires ${formatDate(token.expiresAt)}` : 'never expires'}
                  </span>
                </span>
                {vm.identityEditable && (
                  <IconButton icon="trash" label={`Revoke ${token.name}`} onClick={() => vm.setRevokingToken(token)} />
                )}
              </li>
            ))}
          </ul>
        )}
      </section>


      {vm.resettingPassword && <ResetPasswordDialog user={user} onClose={() => vm.setResettingPassword(false)} />}
      {vm.creatingToken && (
        <NewTokenDialog user={user} onCreated={vm.tokenCreated} onClose={() => vm.setCreatingToken(false)} />
      )}
      <ModalPopupView
        open={vm.revokingToken !== null}
        message={vm.revokingToken && `Revoke "${vm.revokingToken.name}"? Anything using it stops working at once.`}
        onConfirm={vm.revoke}
        onCancel={() => vm.setRevokingToken(null)}
      />
      <ModalPopupView
        open={vm.confirmingAdmin}
        message={
          vm.form?.isAdmin
            ? `Make ${user.displayName} an admin? Admins have every permission and see every draft.`
            : `Remove ${user.displayName}'s admin rights? Their access will come from their grants alone.`
        }
        onConfirm={() => vm.save({ confirmed: true })}
        onCancel={vm.cancelAdminChange}
      />
    </user-overview>
  )
}

export default UserOverviewTab
