import CheckboxTree from '../../../controls/checkbox-tree/CheckboxTree'
import { selectUpward } from '../../../controls/checkbox-tree/checkboxTreeSelection'
import { useUserGroupsViewModel } from './UserGroupsViewModel'
import './UserGroupsTab.css'

// Which groups a user is in: tick a group to add them, untick to remove them. Groups they're in
// through another group show ticked but dimmed.
function UserGroupsTab(props) {
  const vm = useUserGroupsViewModel(props)
  return (
    <user-groups-tab>
      {vm.error && <p className="user-dialog-message user-dialog-error">{vm.error}</p>}
      <CheckboxTree nodes={vm.groupTree} selectedKeys={vm.directGroupKeys} onChange={vm.setDirectGroups} selection={selectUpward} />
    </user-groups-tab>
  )
}

export default UserGroupsTab
