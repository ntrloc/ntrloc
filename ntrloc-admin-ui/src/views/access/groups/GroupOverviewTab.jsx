import Button from '../../../controls/button/Button'
import CheckboxTree from '../../../controls/checkbox-tree/CheckboxTree'
import { selectOneUpward } from '../../../controls/checkbox-tree/checkboxTreeSelection'
import IconButton from '../../../controls/icon-button/IconButton'
import Input from '../../../controls/input/Input'
import OptionList from '../../../controls/option-list/OptionList'
import PopoverButton from '../../../controls/popover-button/PopoverButton'
import { useGroupOverviewViewModel } from './GroupOverviewViewModel'
import '../AccessPanels.css'
import './GroupOverviewTab.css'

// A group's name and the group it sits under (left), and its direct members (right). 'everyone' is
// fixed: it can't be renamed or moved, and every user is in it.
function GroupOverviewTab(props) {
  const vm = useGroupOverviewViewModel(props)

  return (
    <group-overview>
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
        {!vm.isEveryone && (
          <group-overview-actions>
            {vm.editing ? (
              <>
                <Button variant="ghost-secondary" onClick={vm.cancelEdit}>
                  Cancel
                </Button>
                <Button variant="secondary" disabled={Boolean(vm.nameError)} onClick={() => void vm.save()}>
                  Save
                </Button>
              </>
            ) : (
              <Button variant="ghost-secondary" icon="edit" onClick={vm.startEdit}>
                Edit
              </Button>
            )}
          </group-overview-actions>
        )}
        {vm.nameError && <p className="group-overview-message">{vm.nameError}</p>}
        {vm.error && <p className="group-overview-message group-overview-error">{vm.error}</p>}
      </group-overview-fields>

      <section className="access-panel">
        <header>
          <h3>Members</h3>
          {!vm.isEveryone && vm.joinableUsers.length > 0 && (
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
                {!vm.isEveryone && (
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
