import TabView from '../../../controls/tabview/TabView'
import PropertyTreeView from '../PropertyTreeView'
import { useSchemaModel } from '../../../global/model/SchemaModel'
import { useItemTypeDetailViewModel } from './ItemTypeDetailViewModel'
import './ItemTypeDetailView.css'

function ItemTypeDetailView({ itemTypeId, onSelectTrait, detailTabKey, onDetailTabKeyChange }) {
  const { itemType } = useItemTypeDetailViewModel(itemTypeId)
  const { getItemTypePropertyTree } = useSchemaModel()

  if (!itemType) return <p>Item type not found.</p>

  const sections = [
    { key: 'overview', label: 'Overview', view: <p>Overview coming soon.</p> },
    {
      key: 'properties',
      label: 'Properties',
      view: <PropertyTreeView tree={getItemTypePropertyTree(itemType.id)} onSelectTrait={onSelectTrait} />,
    },
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
      {/* Not keyed on the item type -- the active tab should survive switching selection, so e.g.
          staying on "Properties" while browsing different item types. Controlled from
          SchemaViewModel (not TabView's own internal state, and shared with TraitDetailView's own
          tab set) so it also survives this whole component unmounting when the accordion switches
          to a different section and back. */}
      <TabView sections={sections} activeKey={detailTabKey} onActiveKeyChange={onDetailTabKeyChange} />
    </item-type-detail-view>
  )
}

export default ItemTypeDetailView
