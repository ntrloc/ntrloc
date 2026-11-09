import TabView from '../../../controls/tabview/TabView'
import { groupClosure } from '../permissions/grantModel'
import PermissionsTab from '../permissions/PermissionsTab'
import { useGroupDetailViewModel } from './GroupDetailViewModel'
import GroupOverviewTab from './GroupOverviewTab'
import '../users/UserDetailView.css'

// A group: its name, where it sits and who's in it, and what it's granted.
// Same header-over-tabs shape as a user's detail.
function GroupDetailView({ groupId, allGroups, users, onGroupsChanged }) {
  const { status, detail, reload, tabKey, setTabKey } = useGroupDetailViewModel(groupId)

  if (status === 'error') return <p className="user-detail-message user-detail-error">Failed to load this group.</p>
  if (status !== 'ready') return <p className="user-detail-message">Loading…</p>

  const { group } = detail
  // A group gets the grants of every group above it.
  const above = groupClosure(group.parentIds, allGroups)
  const sections = [
    {
      key: 'overview',
      label: 'Info / Members',
      padding: 0,
      view: <GroupOverviewTab detail={detail} allGroups={allGroups} users={users} reload={reload} onGroupsChanged={onGroupsChanged} />,
    },
    {
      key: 'permissions',
      label: 'Permission grants',
      padding: 0,
      view: <PermissionsTab principal={{ kind: 'group', id: group.id }} inheritedFrom={above} />,
    },
  ]

  return (
    <user-detail-view>
      <user-detail-header>
        <h2>{group.name}</h2>
        <span className="user-detail-sign-in">{detail.members.length === 1 ? '1 member' : `${detail.members.length} members`}</span>
      </user-detail-header>
      <TabView sections={sections} activeKey={tabKey} onActiveKeyChange={setTabKey} borderStyle="top" />
    </user-detail-view>
  )
}

export default GroupDetailView
