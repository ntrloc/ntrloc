import Button from '../../controls/button/Button'
import Input from '../../controls/input/Input'
import { useChangeLogCardViewModel } from './ChangeLogCardViewModel'

const retentionText = (days) => (days == null ? 'Everything' : days === 1 ? '1 day' : `${days} days`)

// The live changelog (docs/ntrloc-system-model-notes.md §13.6): every change to users, groups,
// memberships and grants. How long it keeps entries; entries an open draft still needs are kept
// regardless.
function ChangeLogCard() {
  const vm = useChangeLogCardViewModel()

  if (vm.status !== 'ready') {
    return (
      <operations-card>
        <h3>Changelog</h3>
        <p className="operations-hint">{vm.status === 'loading' ? 'Loading…' : "The changelog settings couldn't be loaded."}</p>
      </operations-card>
    )
  }

  const { retentionDays, entries, oldest } = vm.settings
  return (
    <operations-card>
      <operations-card-header>
        <h3>Changelog</h3>
      </operations-card-header>
      <p className="operations-hint">
        Every change to users, groups, memberships and grants, with who made it and when. Drafts use it to tell what changed in
        live; entries an open draft still needs are kept whatever the setting.
      </p>
      <operations-card-fields>
        <operations-card-field>
          <Input
            name="Keep entries for (days)"
            editable={vm.editing}
            value={vm.editing ? vm.days : retentionText(retentionDays)}
            onChange={(e) => vm.setDays(e.target.value)}
            placeholder="blank keeps everything"
            inputMode="numeric"
          />
          {vm.editing && vm.daysError && <span className="operations-message">{vm.daysError}</span>}
        </operations-card-field>
        <operations-card-field>
          <Input
            name="Entries"
            editable={false}
            value={entries === 0 ? 'None' : `${entries.toLocaleString()} since ${new Date(oldest).toLocaleString()}`}
          />
        </operations-card-field>
      </operations-card-fields>
      {vm.error && <p className="operations-message operations-error">{vm.error}</p>}
      <operations-card-actions>
        {vm.editing ? (
          <>
            <Button variant="ghost-secondary" onClick={vm.cancel} disabled={vm.saving}>
              Cancel
            </Button>
            <Button variant="secondary" onClick={() => void vm.save()} disabled={vm.saving || Boolean(vm.daysError)}>
              {vm.saving ? 'Saving…' : 'Save'}
            </Button>
          </>
        ) : (
          <Button variant="ghost-secondary" icon="edit" onClick={vm.startEditing}>
            Edit
          </Button>
        )}
      </operations-card-actions>
    </operations-card>
  )
}

export default ChangeLogCard
