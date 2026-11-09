import TabView from '../../../controls/tabview/TabView'
import { groupClosure } from '../permissions/grantModel'
import PermissionsTab from '../permissions/PermissionsTab'
import { useUserDetailViewModel } from './UserDetailViewModel'
import UserGroupsTab from './UserGroupsTab'
import UserOverviewTab from './UserOverviewTab'
import './UserDetailView.css'

const SIGN_IN_LABELS = { local: 'Local account', ldap: 'LDAP', oidc: 'OpenID Connect', saml: 'SAML' }

// A user: who they are and their tokens, the groups they're in, and what they're granted.
function UserDetailView({ userId, allGroups, onUserChanged }) {
  const { status, detail, reload, tabKey, setTabKey } = useUserDetailViewModel(userId)

  if (status === 'error') return <p className="user-detail-message user-detail-error">Failed to load this user.</p>
  if (status !== 'ready') return <p className="user-detail-message">Loading…</p>

  const { user } = detail
  const memberOf = groupClosure(detail.groups.map((g) => g.id), allGroups)
  const sections = [
    {
      key: 'overview',
      label: 'Info / Tokens',
      padding: 0,
      view: <UserOverviewTab detail={detail} reload={reload} onUserChanged={onUserChanged} />,
    },
    {
      key: 'groups',
      label: 'Group membership',
      padding: 0,
      view: <UserGroupsTab detail={detail} allGroups={allGroups} reload={reload} />,
    },
    {
      key: 'permissions',
      label: 'Permission grants',
      padding: 0,
      view: (
        <PermissionsTab
          principal={{ kind: 'user', id: user.id }}
          inheritedFrom={memberOf}
          adminNote={
            user.isAdmin
              ? `${user.displayName} is an admin, so has every permission whatever is granted here. These grants apply if that changes.`
              : null
          }
        />
      ),
    },
  ]

  return (
    <user-detail-view>
      <user-detail-header>
        <h2>{user.displayName}</h2>
        {user.isAdmin && <span className="user-detail-badge">Admin</span>}
        <span className="user-detail-sign-in">{SIGN_IN_LABELS[user.signIn] ?? user.signIn}</span>
      </user-detail-header>
      <TabView sections={sections} activeKey={tabKey} onActiveKeyChange={setTabKey} borderStyle="top" />
    </user-detail-view>
  )
}

export default UserDetailView
