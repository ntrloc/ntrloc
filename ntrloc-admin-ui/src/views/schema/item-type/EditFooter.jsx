import Button from '../../../controls/button/Button'
import ItemTypeConflictsView from './ItemTypeConflictsView'
import './EditFooter.css'

// Below an item type's or a trait's cards: Edit, or Cancel and Save while editing, with whatever
// conflicts or save error stand in the way. While editing a saved one, Delete marks it to be deleted
// on Save, along with its links, which are named here. `vm` is that editor's view model.
function EditFooter({ vm }) {
  const { deletion } = vm
  return (
    <schema-edit-footer>
      {vm.editing && deletion?.marked && (
        <p className="schema-edit-delete-notice">
          This {vm.noun} will be deleted when you save.
          {deletion.links.length > 0 && ` Its links go with it: ${deletion.links.join(', ')}.`}
        </p>
      )}
      {vm.conflicts.length > 0 && <ItemTypeConflictsView conflicts={vm.conflicts} />}
      {vm.saveError && vm.conflicts.length === 0 && (
        <p className="schema-edit-save-error" role="alert">
          {vm.saveError}
        </p>
      )}
      {vm.editing ? (
        <>
          {deletion && (
            <Button variant="ghost-secondary" onClick={deletion.toggle} disabled={vm.saving}>
              {deletion.marked ? `Keep ${vm.noun}` : `Delete ${vm.noun}`}
            </Button>
          )}
          <Button variant="ghost-secondary" onClick={vm.cancel} disabled={vm.saving}>
            Cancel
          </Button>
          <Button variant="secondary" onClick={vm.save} disabled={vm.saving || vm.conflicts.length > 0}>
            {vm.saving ? 'Saving…' : 'Save'}
          </Button>
        </>
      ) : (
        <Button variant="secondary" onClick={vm.startEditing}>
          Edit
        </Button>
      )}
    </schema-edit-footer>
  )
}

export default EditFooter
