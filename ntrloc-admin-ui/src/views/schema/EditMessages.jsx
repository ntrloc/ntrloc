import ItemTypeConflictsView from './item-type/ItemTypeConflictsView'
import './EditMessages.css'

// Under an item type's, trait's or controlled list's header while editing: whatever stands in the
// way of Save -- conflicts to settle, the editor's `problem` to fix, or why the last Save failed.
// Nothing at all when there's none. `vm` is that editor's view model.
function EditMessages({ vm }) {
  if (!vm.editing) return null
  const conflicted = vm.conflicts.length > 0
  const message = !conflicted && (vm.saveError ?? vm.problem)
  if (!conflicted && !message) return null
  return (
    <schema-edit-messages>
      {conflicted && <ItemTypeConflictsView conflicts={vm.conflicts} />}
      {message && (
        <p className="schema-edit-save-error" role={vm.saveError ? 'alert' : undefined}>
          {message}
        </p>
      )}
    </schema-edit-messages>
  )
}

export default EditMessages
