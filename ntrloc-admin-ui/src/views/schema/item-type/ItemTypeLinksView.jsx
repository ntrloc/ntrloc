import ItemTypeLinkView from './ItemTypeLinkView'
import './ItemTypeLinksView.css'

// One ItemTypeLinkView per link, each its own boxes/arrows/labels diagram. `deletion`
// ({ isMarked, onToggle }), when given, gives each of this item type's own links a delete/undo toggle,
// and `propertyEditing` makes their properties editable (see ItemTypeLinkView).
function ItemTypeLinksView({ links, itemTypeName, onSelectTrait, deletion, propertyEditing }) {
  if (links.length === 0) return <p>No links yet.</p>

  return (
    <div className="item-type-links-view">
      {links.map((link) => (
        <ItemTypeLinkView
          key={link.id}
          sourceName={itemTypeName}
          link={link}
          onSelectTrait={onSelectTrait}
          markedForDelete={deletion?.isMarked(link.linkId)}
          onToggleDelete={deletion && !link.inherited ? () => deletion.onToggle(link.linkId) : undefined}
          propertyEditing={link.inherited ? undefined : propertyEditing}
        />
      ))}
    </div>
  )
}

export default ItemTypeLinksView
