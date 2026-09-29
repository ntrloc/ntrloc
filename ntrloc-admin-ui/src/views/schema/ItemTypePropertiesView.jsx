import { useItemTypePropertiesViewModel } from './ItemTypePropertiesViewModel'
import './ItemTypePropertiesView.css'

// First pass, listing only -- property/group editing (the reason this got its own view/view-model
// rather than living inline in ItemTypeDetailView) comes later.
function ItemTypePropertiesView({ itemTypeId }) {
  const { entries } = useItemTypePropertiesViewModel(itemTypeId)

  return (
    <item-type-properties-view>
      {entries.length === 0 && <p>No properties or groups yet.</p>}
      <ul className="item-type-properties-list">
        {entries.map((entry) => (
          <li key={entry.id} className="item-type-properties-list-item">
            <span className="item-type-properties-list-item-name">{entry.name}</span>
            <span className="item-type-properties-list-item-kind">
              {entry.kind === 'group' ? 'Group' : entry.type}
            </span>
          </li>
        ))}
      </ul>
    </item-type-properties-view>
  )
}

export default ItemTypePropertiesView
