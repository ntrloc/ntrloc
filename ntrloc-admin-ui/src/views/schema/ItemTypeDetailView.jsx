import TabView from '../../controls/tabview/TabView'
import ItemTypePropertiesView from './ItemTypePropertiesView'
import { useItemTypeDetailViewModel } from './ItemTypeDetailViewModel'
import './ItemTypeDetailView.css'

function ItemTypeDetailView({ itemTypeId }) {
  const { itemType } = useItemTypeDetailViewModel(itemTypeId)

  if (!itemType) return <p>Item type not found.</p>

  const sections = [
    { key: 'overview', label: 'Overview', view: <p>Overview coming soon.</p> },
    { key: 'properties', label: 'Properties', view: <ItemTypePropertiesView itemTypeId={itemType.id} /> },
    { key: 'links', label: 'Links', view: <p>Links coming soon.</p> },
    { key: 'stateMachines', label: 'State Machines', view: <p>State machines coming soon.</p> },
    { key: 'accessControl', label: 'Access Control', view: <p>Access control coming soon.</p> },
  ]

  return (
    <item-type-detail-view>
      <item-type-detail-header>
        <h2>{itemType.name}</h2>
        {itemType.description && <p className="item-type-detail-description">{itemType.description}</p>}
      </item-type-detail-header>
      {/* Not keyed on the item type -- TabView's own active-tab state should survive switching
          selection, so e.g. staying on "Properties" while browsing different item types. */}
      <TabView sections={sections} />
    </item-type-detail-view>
  )
}

export default ItemTypeDetailView
