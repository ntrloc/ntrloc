import Button from '../../../controls/button/Button'
import CheckboxTree from '../../../controls/checkbox-tree/CheckboxTree'
import { selectOneUpward } from '../../../controls/checkbox-tree/checkboxTreeSelection'
import IconButton from '../../../controls/icon-button/IconButton'
import Input from '../../../controls/input/Input'
import OptionList from '../../../controls/option-list/OptionList'
import PopoverButton from '../../../controls/popover-button/PopoverButton'
import { useState } from 'react'
import AddGroupDialog from './AddGroupDialog'
import { useGroupOverviewViewModel } from './GroupOverviewViewModel'
import '../AccessPanels.css'
import './GroupOverviewTab.css'

// A group's name and the group it sits under (left), and its direct members (right). 'everyone' is
// fixed: it can't be renamed or moved, and every user is in it. Add group makes a new group under this
// one (live only, like every group edit).
function GroupOverviewTab(props) {
  const vm = useGroupOverviewViewModel(props)
  const [adding, setAdding] = useState(false)

  return (
    <group-overview>
      {vm.inDraft && <p className="access-draft-note">You're in a draft: groups and their members aren't drafted yet, so they're read-only here.</p>}
      <group-overview-fields>
        <Input name="Name" editable={vm.editing} value={vm.name} onChange={(e) => vm.setName(e.target.value)} />
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
        {!vm.inDraft && (
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
        )}
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
        {vm.members.length === 0 ? (
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
