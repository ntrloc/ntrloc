import Button from '../../../controls/button/Button'
import CheckboxTree from '../../../controls/checkbox-tree/CheckboxTree'
import { selectOneUpward } from '../../../controls/checkbox-tree/checkboxTreeSelection'
import IconButton from '../../../controls/icon-button/IconButton'
import Input from '../../../controls/input/Input'
import OptionList from '../../../controls/option-list/OptionList'
import PopoverButton from '../../../controls/popover-button/PopoverButton'
import { useState } from 'react'
import { useGrantChanges } from '../permissions/useGrantChanges'
import AddGroupDialog from './AddGroupDialog'
import { useGroupOverviewViewModel } from './GroupOverviewViewModel'
import ReconcileItems from './ReconcileItems'
import { useGroupReconcile } from './useGroupReconcile'
import '../AccessPanels.css'
import './GroupOverviewTab.css'

// A group's name and the group it sits under (left), and its direct members (right). 'everyone' is
// fixed: it can't be renamed or moved, and every user is in it. Add group makes a new group under this
// one. In a draft, what live has put in question shows beside the field it's about.
function GroupOverviewTab(props) {
  const vm = useGroupOverviewViewModel(props)
  const [adding, setAdding] = useState(false)
  const groupChanges = useGrantChanges().groups
  const reconcile = useGroupReconcile()
  const names = { users: props.users, groups: props.allGroups, targets: { itemTypes: [], markers: [] } }
  const items = (field) => (
    <ReconcileItems
      reasons={groupChanges.reasonsFor(vm.group.id, field)}
      working={reconcile.working}
      names={names}
      onChoose={(reason, choice) => void reconcile.chooseForGroup(vm.group.id, reason, choice)}
    />
  )
  const membershipItems = groupChanges.membershipReasons(vm.group.id)

  return (
    <group-overview>
      <group-overview-fields>
        <Input name="Name" editable={vm.editing} value={vm.name} onChange={(e) => vm.setName(e.target.value)} />
        {items('name')}
        {vm.isEveryone ? (
          <p className="access-muted">The top-level group: every user is in it, and every other group sits below it.</p>
        ) : (
          <PopoverButton name="Parent group" summary={vm.parentName} editable={vm.editing}>
            {({ close }) => (
              <CheckboxTree
                nodes={vm.parentTree}
                selectedKeys={vm.parentKeys}
                onChange={(next) => {
                  vm.setParent(next)
                  close()
                }}
                selection={selectOneUpward}
              />
            )}
          </PopoverButton>
        )}
        {items('parent')}
        {reconcile.error && <p className="group-overview-message group-overview-error">{reconcile.error}</p>}
        <group-overview-actions>
          {vm.editing && (
            <>
              <Button variant="ghost-secondary" onClick={vm.cancelEdit}>
                Cancel
              </Button>
              <Button variant="secondary" disabled={Boolean(vm.nameError)} onClick={() => void vm.save()}>
                Save
              </Button>
            </>
          )}
          {!vm.editing && vm.canEdit && (
            <Button variant="ghost-secondary" icon="edit" onClick={vm.startEdit}>
              Edit
            </Button>
          )}
          {!vm.editing && (
            <Button variant="ghost-secondary" icon="plus" onClick={() => setAdding(true)}>
              Add group
            </Button>
          )}
        </group-overview-actions>
        {adding && (
          <AddGroupDialog parent={props.detail.group} onCancel={() => setAdding(false)} onCreated={props.onCreated} />
        )}
        {vm.nameError && <p className="group-overview-message">{vm.nameError}</p>}
        {vm.error && <p className="group-overview-message group-overview-error">{vm.error}</p>}
      </group-overview-fields>

      <section className="access-panel">
        <header>
          <h3>Members</h3>
          {vm.canEdit && vm.joinableUsers.length > 0 && (
            <PopoverButton summary="Add member">
              {({ close }) => (
                <OptionList
                  options={vm.joinableUsers.map((u) => ({ value: u.id, label: u.displayName }))}
                  value={null}
                  onChange={(userId) => {
                    close()
                    void vm.addMember(userId)
                  }}
                />
              )}
            </PopoverButton>
          )}
        </header>
        {membershipItems.map(({ change, reason }) => (
          <ReconcileItems
            key={`${change.userId}-${reason.kind}`}
            reasons={[reason]}
            working={reconcile.working}
            names={names}
            onChoose={(_, choice) => void reconcile.chooseForMembership(change, choice)}
          />
        ))}
        {vm.members.length === 0 && membershipItems.length === 0 ? (
          <p className="access-muted">No members.</p>
        ) : (
          <ul className="access-rows">
            {vm.members.map((member) => (
              <li key={member.id}>
                <span className="access-row-main">
                  <span>{member.displayName}</span>
                  <span className="access-muted">{member.email}</span>
                </span>
                {vm.canEdit && (
                  <IconButton icon="x" label={`Remove ${member.displayName}`} onClick={() => void vm.removeMember(member.id)} />
                )}
              </li>
            ))}
          </ul>
        )}
      </section>
    </group-overview>
  )
}

export default GroupOverviewTab
