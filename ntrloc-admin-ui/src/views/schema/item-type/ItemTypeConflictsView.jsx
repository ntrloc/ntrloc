import Button from '../../../controls/button/Button'
import './ItemTypeConflictsView.css'

// Fields someone else changed while they were being edited here. Each has to be settled, one way or
// the other, before the item type can be saved.
function ItemTypeConflictsView({ conflicts }) {
  return (
    <item-type-conflicts role="alert">
      <p>Someone else changed {conflicts.length === 1 ? 'a field' : 'some fields'} you edited.</p>
      <ul>
        {conflicts.map((conflict) => (
          <li key={conflict.key} data-field={conflict.label}>
            <span>
              {conflict.label} is now <q>{conflict.theirs}</q>
            </span>
            <Button variant="ghost-secondary" onClick={conflict.keepMine}>
              Keep mine
            </Button>
            <Button variant="ghost-secondary" onClick={conflict.useTheirs}>
              Use theirs
            </Button>
          </li>
        ))}
      </ul>
    </item-type-conflicts>
  )
}

export default ItemTypeConflictsView
