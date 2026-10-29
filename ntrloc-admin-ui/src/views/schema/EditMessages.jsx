import ItemTypeConflictsView from './item-type/ItemTypeConflictsView'
import './EditMessages.css'

// Under an item type's, trait's or controlled list's header while editing: conflicts to settle
// before Save, or why the last Save failed. Nothing at all when there's neither. (What's wrong with
// a field as entered is shown by the field itself.) `vm` is that editor's view model.
function EditMessages({ vm }) {
  if (!vm.editing) return null
  const conflicted = vm.conflicts.length > 0
  const message = !conflicted && vm.saveError
  if (!conflicted && !message) return null
  return (
    <schema-edit-messages>
      {conflicted && <ItemTypeConflictsView conflicts={vm.conflicts} />}
      {message && (
        <p className="schema-edit-save-error" role="alert">
          {message}
        </p>
      )}
    </schema-edit-messages>
  )
}

export default EditMessages
