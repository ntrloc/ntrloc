import TabView from '../../controls/tabview/TabView'
import { useDraftMode } from '../../global/viewmodel/useDraftMode'
import { useAccessViewModel } from './AccessViewModel'
import AccessList from './AccessList'
import GroupDetailView from './groups/GroupDetailView'
import { groupHierarchy } from './permissions/grantModel'
import { useGrantChanges } from './permissions/useGrantChanges'
import UserDetailView from './users/UserDetailView'
import './AccessView.css'

// The group hierarchy as list entries, each indented under its parent; groups the draft deletes struck
// through where they were.
function groupItems(groups) {
  const flatten = (nodes, depth) =>
    nodes.flatMap((n) => [{ id: n.key, label: n.label, depth, deleted: n.deleted }, ...flatten(n.children, depth + 1)])
  return flatten(groupHierarchy(groups, (g) => ({ deleted: Boolean(g.deleted) })), 0)
}

// Who can do what: users, groups (each a list beside the selected one's detail), and the same
// grants seen from the schema's side.
function AccessView() {
  const {
    status,
    reloadUsers,
    reloadGroups,
    activeSection,
    setActiveSection,
    users,
    groups,
    listedGroups,
    selectedUserId,
    selectUser,
    selectedGroupId,
    selectGroup,
    focusTab,
  } = useAccessViewModel()
  const changes = useGrantChanges()
  const { inDraft } = useDraftMode()

  if (status === 'error') return <p className="access-view-error">Failed to load users and groups.</p>
  if (status !== 'ready') return <p className="access-view-loading">Loading…</p>

  const placeholder = (text) => <p className="access-view-placeholder">{text}</p>
  const sections = [
    {
      key: 'users',
      label: 'Users',
      padding: 0,
      mark: changes.kindMark('user'),
      view: (
        <access-view-split>
          <access-view-nav>
            <AccessList
              addLabel="Add user"
              items={users.map((u) => ({
                id: u.id,
                label: u.displayName,
                admin: u.isAdmin,
                mark: changes.principalMark({ kind: 'user', id: u.id }),
              }))}
              selectedId={selectedUserId}
              onSelect={selectUser}
            />
          </access-view-nav>
          <access-view-content>
            {selectedUserId ? (
              <UserDetailView
                key={`${selectedUserId}-${focusTab ?? ''}`}
                userId={selectedUserId}
                allGroups={groups}
                users={users}
                onUserChanged={reloadUsers}
                initialTab={focusTab}
              />
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
      mark: changes.kindMark('group'),
      view: (
        <access-view-split>
          <access-view-nav>
            <AccessList
              items={groupItems(listedGroups).map((g) => ({ ...g, mark: changes.principalMark({ kind: 'group', id: g.id }) }))}
              selectedId={selectedGroupId}
              onSelect={selectGroup}
            />
          </access-view-nav>
          <access-view-content>
            {selectedGroupId ? (
              <GroupDetailView
                key={`${selectedGroupId}-${focusTab ?? ''}`}
                groupId={selectedGroupId}
                allGroups={groups}
                users={users}
                onGroupsChanged={reloadGroups}
                onDeleted={() => {
                  // In a draft it stays selected, struck through, with Restore.
                  if (!inDraft) selectGroup(null)
                  void reloadGroups()
                }}
                initialTab={focusTab}
                onCreated={(newGroupId) => {
                  void reloadGroups().then(() => selectGroup(newGroupId))
                }}
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
