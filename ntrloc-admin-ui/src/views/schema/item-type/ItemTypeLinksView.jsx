import ItemTypeLinkView from './ItemTypeLinkView'
import './ItemTypeLinksView.css'

const linkKey = (link) => `link-toggled:${link.id}`
const linkPropertiesKey = (link) => `link-properties:${link.id}`

// One ItemTypeLinkView per link. Which links, and which links' properties, are folded is kept by
// the caller in `collapsed` (keys from linkKey/linkPropertiesKey), toggled with onToggleCollapsed,
// so it lasts while browsing. Saved links start folded, down to just their arrows, so an item
// type's links read at a glance -- so for them, linkKey's presence means unfolded. A new one starts
// unfolded, ready for its properties. `deletion` ({ isMarked, onToggle }), when given, gives each of this
// item type's own links a delete/undo toggle, `onEdit` an edit button, and `propertyEditing` makes
// their properties editable (see ItemTypeLinkView).
function ItemTypeLinksView({
  links,
  collapsed,
  onToggleCollapsed,
  onSelectTrait,
  onSelectItemType,
  deletion,
  onEdit,
  propertyEditing,
}) {
  if (links.length === 0) return <p>No links yet.</p>

  return (
    <div className="item-type-links-view">
      {links.map((link) => (
        <ItemTypeLinkView
          key={link.id}
          link={link}
          expanded={collapsed.has(linkKey(link)) !== Boolean(link.isNew)}
          onToggleExpanded={() => onToggleCollapsed(linkKey(link))}
          propertiesExpanded={!collapsed.has(linkPropertiesKey(link))}
          onTogglePropertiesExpanded={() => onToggleCollapsed(linkPropertiesKey(link))}
          onSelectTrait={onSelectTrait}
          onSelectItemType={onSelectItemType}
          markedForDelete={deletion?.isMarked(link.linkId)}
          onToggleDelete={deletion && !link.inherited ? () => deletion.onToggle(link.linkId) : undefined}
          onEdit={onEdit && !link.inherited ? () => onEdit(link.id) : undefined}
          propertyEditing={link.inherited ? undefined : propertyEditing}
        />
      ))}
    </div>
  )
}

export default ItemTypeLinksView
