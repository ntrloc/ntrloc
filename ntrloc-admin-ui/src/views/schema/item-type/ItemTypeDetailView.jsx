import { useState } from 'react'
import Filter from '../../../controls/filter/Filter'
import IconButton from '../../../controls/icon-button/IconButton'
import CollapsibleCard from '../../../controls/collapsible-card/CollapsibleCard'
import DefinitionActions from '../DefinitionActions'
import TabView from '../../../controls/tabview/TabView'
import PropertyTreeView from '../PropertyTreeView'
import ItemTypeOverviewView from './ItemTypeOverviewView'
import ItemTypeLinksView from './ItemTypeLinksView'
import ItemTypeStateMachinesView from './ItemTypeStateMachinesView'
import AccessMarkersView from './AccessMarkersView'
import EditMessages from '../EditMessages'
import StructureDialogs from './StructureDialogs'
import { useItemTypeViewModel } from './ItemTypeViewModel'
import './ItemTypeDetailView.css'

function ItemTypeDetailView({
  itemTypeViewModels,
  itemTypeId,
  onSelectTrait,
  onSelectItemType,
  draftNavigation,
  tabKey,
  onTabKeyChange,
  collapsedStructureCards,
  onToggleStructureCard,
}) {
  const vm = useItemTypeViewModel(itemTypeViewModels, itemTypeId, draftNavigation)
  // Like the active tab, kept while browsing other item types.
  const [propertyFilter, setPropertyFilter] = useState('')
  const { itemType, editing } = vm

  if (!itemType) return <p>Item type not found.</p>

  // The item type's own shape -- everything schema editing will cover -- grouped under one tab,
  // apart from State Machines and Access Markers, which are edited separately.
  const structureCards = [
    {
      key: 'overview',
      label: 'Overview',
      view: <ItemTypeOverviewView overview={vm.overview} editable={editing} />,
    },
    {
      key: 'properties',
      label: 'Properties',
      // Only while the card is open -- there's nothing to filter in a collapsed one.
      actions: !collapsedStructureCards.has('properties') && (
        <Filter name="property-filter" value={propertyFilter} onChange={setPropertyFilter} placeholder="Filter properties" />
      ),
      view: (
        <PropertyTreeView
          tree={vm.propertyTree}
          filter={propertyFilter}
          onSelectTrait={onSelectTrait}
          onSelectItemType={onSelectItemType}
          deletion={editing ? vm.propertyDeletion : undefined}
          onEdit={editing ? vm.editRow : undefined}
          onAddProperty={editing ? vm.newProperty : undefined}
          onAddGroup={editing ? vm.newGroup : undefined}
        />
      ),
    },
    {
      key: 'links',
      label: 'Links',
      actions: editing && <IconButton icon="plus" label="New link" variant="secondary" onClick={vm.newLink} />,
      view: (
        <ItemTypeLinksView
          links={vm.links}
          collapsed={collapsedStructureCards}
          onToggleCollapsed={onToggleStructureCard}
          onSelectTrait={onSelectTrait}
          onSelectItemType={onSelectItemType}
          deletion={editing ? vm.linkDeletion : undefined}
          onEdit={editing ? vm.editLink : undefined}
          propertyEditing={
            editing
              ? { deletion: vm.propertyDeletion, onEdit: vm.editRow, onAddProperty: vm.newProperty, onAddGroup: vm.newGroup }
              : undefined
          }
        />
      ),
    },
  ]

  const sections = [
    {
      key: 'structure',
      label: 'Structure',
      view: (
        <item-type-structure>
          <item-type-structure-cards>
            {structureCards.map((card) => (
              <CollapsibleCard
                key={card.key}
                title={card.label}
                actions={card.actions}
                expanded={!collapsedStructureCards.has(card.key)}
                onToggle={() => onToggleStructureCard(card.key)}
              >
                {card.view}
              </CollapsibleCard>
            ))}
          </item-type-structure-cards>
        </item-type-structure>
      ),
      padding: 0,
    },
    { key: 'stateMachines', label: 'State Machines', view: <ItemTypeStateMachinesView itemTypeId={itemType.id} /> },
    {
      key: 'accessMarkers',
      label: 'Access Markers',
      view: <AccessMarkersView itemTypeId={itemType.id} itemTypeName={itemType.name} />,
      padding: 0,
    },
  ]

  return (
    <item-type-detail-view>
      <item-type-detail-header>
        <h2>{itemType.name}</h2>
        {itemType.description && <p className="item-type-detail-description">{itemType.description}</p>}
        {/* Editing covers the Structure tab, so Edit brings it up. */}
        <DefinitionActions
          vm={vm}
          name={itemType.name}
          onEdit={() => {
            onTabKeyChange('structure')
            vm.startEditing()
          }}
        />
      </item-type-detail-header>
      <EditMessages vm={vm} />
      {/* Not keyed on the item type -- the active tab and collapsed Structure cards should survive
          switching selection, so e.g. staying on "Properties" while browsing different item types.
          Controlled from SchemaViewModel (not TabView's own internal state) so they
          also survive this whole component unmounting when the sidebar accordion switches to a
          different section and back. */}
      <TabView sections={sections} activeKey={tabKey} onActiveKeyChange={onTabKeyChange} borderStyle="top" />
      <StructureDialogs vm={vm} />
    </item-type-detail-view>
  )
}

export default ItemTypeDetailView
