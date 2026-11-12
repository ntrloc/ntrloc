import { useState } from 'react'
import Button from '../../../controls/button/Button'
import TabView from '../../../controls/tabview/TabView'
import LiveNotices from '../LiveNotices'
import { groupClosure, subgroupsOf } from '../permissions/grantModel'
import DeletedGroupPanel from './DeletedGroupPanel'
import DeleteGroupDialog from './DeleteGroupDialog'
import PermissionsTab from '../permissions/PermissionsTab'
import { useGrantChanges } from '../permissions/useGrantChanges'
import { useGroupDetailViewModel } from './GroupDetailViewModel'
import GroupOverviewTab from './GroupOverviewTab'
import '../users/UserDetailView.css'
import './GroupDetailView.css'

// A group: its name, where it sits and who's in it, and what it's granted.
// Same header-over-tabs shape as a user's detail. Delete is offered for every group but the root
// ("everyone"), which the system keeps. A group the draft deletes shows only that, with Restore.
function GroupDetailView({ groupId, allGroups, users, onGroupsChanged, onDeleted, onCreated, initialTab }) {
  const { status, detail, reload, tabKey, setTabKey } = useGroupDetailViewModel(groupId, initialTab)
  const [deleting, setDeleting] = useState(false)
  const changes = useGrantChanges()

  if (status === 'error') return <p className="user-detail-message user-detail-error">Failed to load this group.</p>
  if (status !== 'ready') return <p className="user-detail-message">Loading…</p>

  const { group } = detail
  if (group.deleted) {
    return (
      <user-detail-view>
        <user-detail-header>
          <h2 className="group-detail-deleted-name">{group.name}</h2>
        </user-detail-header>
        <DeletedGroupPanel group={group} users={users} allGroups={allGroups} />
      </user-detail-view>
    )
  }
  // A group gets the grants of every group above it.
  const above = groupClosure(group.parentIds, allGroups)
  const sections = [
    {
      key: 'overview',
      label: 'Info / Members',
      padding: 0,
      mark: changes.tabMark({ kind: 'group', id: groupId }, 'overview'),
      view: (
        <GroupOverviewTab
          detail={detail}
          allGroups={allGroups}
          users={users}
          reload={reload}
          onGroupsChanged={onGroupsChanged}
          onCreated={onCreated}
        />
      ),
    },
    {
      key: 'permissions',
      label: 'Permission grants',
      padding: 0,
      mark: changes.tabMark({ kind: 'group', id: groupId }, 'grants'),
      view: <PermissionsTab principal={{ kind: 'group', id: group.id }} inheritedFrom={above} />,
    },
  ]

  return (
    <user-detail-view>
      <user-detail-header>
        <h2>{group.name}</h2>
        <span className="user-detail-sign-in">{detail.members.length === 1 ? '1 member' : `${detail.members.length} members`}</span>
        {group.parentIds.length > 0 && (
          <user-detail-actions>
            <Button variant="ghost-danger" icon="trash" onClick={() => setDeleting(true)}>
              Delete
            </Button>
          </user-detail-actions>
        )}
      </user-detail-header>
      <LiveNotices principal={{ kind: 'group', id: groupId }} users={users} groups={allGroups} />
      {deleting && (
        <DeleteGroupDialog
          group={group}
          subgroups={subgroupsOf(group.id, allGroups)}
          onCancel={() => setDeleting(false)}
          onDeleted={onDeleted}
        />
      )}
      <TabView sections={sections} activeKey={tabKey} onActiveKeyChange={setTabKey} borderStyle="top" />
    </user-detail-view>
  )
}

export default GroupDetailView
