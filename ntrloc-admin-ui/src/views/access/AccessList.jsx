import Button from '../../controls/button/Button'
import ModifiedDot from '../../controls/modified-dot/ModifiedDot'
import './AccessList.css'

// A selectable list with an add action above it -- the users list and the groups list. `items` are
// { id, label, admin?, depth?, modified? }; an entry with a change in the current draft is marked, and an admin, since admins have every permission regardless of
// grants, and depth indents an entry under the one above (the group hierarchy).
function AccessList({ addLabel, items, selectedId, onSelect }) {
  return (
    <access-list>
      {/* Not wired yet: how users and groups get added is still to be designed. */}
      <Button variant="ghost-secondary" icon="plus" disabled>
        {addLabel}
      </Button>
      <ul className="access-list-items">
        {items.map((item) => (
          <li key={`${item.id}-${item.depth ?? 0}`}>
            <button
              type="button"
              className="access-list-item"
              aria-current={item.id === selectedId || undefined}
              style={{ '--depth': item.depth ?? 0 }}
              onClick={() => onSelect(item.id)}
            >
              <span className="access-list-label">{item.label}</span>
              {item.modified && <ModifiedDot />}
              {item.admin && <span className="access-list-badge">Admin</span>}
            </button>
          </li>
        ))}
      </ul>
    </access-list>
  )
}

export default AccessList
