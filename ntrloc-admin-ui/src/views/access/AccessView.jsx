import TabView from '../../controls/tabview/TabView'
import { useAccessViewModel } from './AccessViewModel'
import AccessList from './AccessList'
import GroupDetailView from './groups/GroupDetailView'
import { groupHierarchy } from './permissions/grantModel'
import UserDetailView from './users/UserDetailView'
import './AccessView.css'

// The group hierarchy as list entries, each indented under its parent.
function groupItems(groups) {
  const flatten = (nodes, depth) => nodes.flatMap((n) => [{ id: n.key, label: n.label, depth }, ...flatten(n.children, depth + 1)])
  return flatten(groupHierarchy(groups), 0)
}

// Who can do what: users, groups (each a list beside the selected one's detail), and the same
// grants seen from the schema's side. Prototype -- runs on mock data for now (see AccessDelegate).
function AccessView() {
  const {
    status,
    reloadUsers,
    reloadGroups,
    activeSection,
    setActiveSection,
    users,
    groups,
    selectedUserId,
    selectUser,
    selectedGroupId,
    selectGroup,
  } = useAccessViewModel()

  if (status === 'error') return <p className="access-view-error">Failed to load users and groups.</p>
  if (status !== 'ready') return <p className="access-view-loading">Loading…</p>

  const placeholder = (text) => <p className="access-view-placeholder">{text}</p>
  const sections = [
    {
      key: 'users',
      label: 'Users',
      padding: 0,
      view: (
        <access-view-split>
          <access-view-nav>
            <AccessList
              addLabel="Add user"
              items={users.map((u) => ({ id: u.id, label: u.displayName, admin: u.isAdmin }))}
              selectedId={selectedUserId}
              onSelect={selectUser}
            />
          </access-view-nav>
          <access-view-content>
            {selectedUserId ? (
              <UserDetailView key={selectedUserId} userId={selectedUserId} allGroups={groups} onUserChanged={reloadUsers} />
            ) : (
              placeholder('Select a user.')
            )}
          </access-view-content>
        </access-view-split>
      ),
    },
    {
      key: 'groups',
      label: 'Groups',
      padding: 0,
      view: (
        <access-view-split>
          <access-view-nav>
            <AccessList addLabel="Add group" items={groupItems(groups)} selectedId={selectedGroupId} onSelect={selectGroup} />
          </access-view-nav>
          <access-view-content>
            {selectedGroupId ? (
              <GroupDetailView
                key={selectedGroupId}
                groupId={selectedGroupId}
                allGroups={groups}
                users={users}
                onGroupsChanged={reloadGroups}
              />
            ) : (
              placeholder('Select a group.')
            )}
          </access-view-content>
        </access-view-split>
      ),
    },
    { key: 'permissions', label: 'Permissions', view: placeholder('Not designed yet.') },
  ]

  return (
    <access-view>
      <TabView sections={sections} activeKey={activeSection} onActiveKeyChange={setActiveSection} borderStyle="top" />
    </access-view>
  )
}

export default AccessView
