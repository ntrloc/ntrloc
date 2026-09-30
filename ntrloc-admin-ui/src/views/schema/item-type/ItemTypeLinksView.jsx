import ItemTypeLinkView from './ItemTypeLinkView'
import { useItemTypeLinksViewModel } from './ItemTypeLinksViewModel'
import './ItemTypeLinksView.css'

// First pass, display only -- one ItemTypeLinkView per link, each its own boxes/arrows/labels
// diagram. Link properties (a link can carry data of its own, not just connect two item types)
// aren't shown yet; how best to fit those in is still undecided.
function ItemTypeLinksView({ itemTypeId, itemTypeName, onSelectTrait }) {
  const { links } = useItemTypeLinksViewModel(itemTypeId)

  if (links.length === 0) return <p>No links yet.</p>

  return (
    <div className="item-type-links-view">
      {links.map((link) => (
        <ItemTypeLinkView key={link.id} sourceName={itemTypeName} link={link} onSelectTrait={onSelectTrait} />
      ))}
    </div>
  )
}

export default ItemTypeLinksView
