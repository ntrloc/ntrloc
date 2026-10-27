import Button from '../../../controls/button/Button'
import './ItemTypeConflictsView.css'

// Fields and properties someone else changed while they were being edited here. Each has to be
// settled, one way or the other, before the item type can be saved. A field shows the value it now
// has; a property, just what happened to it. "Keep mine" isn't offered for a property that's gone.
function ItemTypeConflictsView({ conflicts }) {
  return (
    <item-type-conflicts role="alert">
      <p>Someone else changed {conflicts.length === 1 ? 'a field' : 'some fields'} you edited.</p>
      <ul>
        {conflicts.map((conflict) => (
          <li key={conflict.key} data-field={conflict.label}>
            <span>
              {conflict.label}{' '}
              {conflict.note ?? (
                <>
                  is now <q>{conflict.theirs}</q>
                </>
              )}
            </span>
            {conflict.keepMine && (
              <Button variant="ghost-secondary" onClick={conflict.keepMine}>
                Keep mine
              </Button>
            )}
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
