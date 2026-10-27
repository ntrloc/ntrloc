import Button from '../../../controls/button/Button'
import CollapsibleCard from '../../../controls/collapsible-card/CollapsibleCard'
import TabView from '../../../controls/tabview/TabView'
import PropertyTreeView from '../PropertyTreeView'
import ItemTypeOverviewView from './ItemTypeOverviewView'
import ItemTypeLinksView from './ItemTypeLinksView'
import ItemTypeStateMachinesView from './ItemTypeStateMachinesView'
import AccessMarkersView from './AccessMarkersView'
import ItemTypeConflictsView from './ItemTypeConflictsView'
import PropertyEditDialog from './PropertyEditDialog'
import GroupEditDialog from './GroupEditDialog'
import { useItemTypeViewModel } from './ItemTypeViewModel'
import './ItemTypeDetailView.css'

function ItemTypeDetailView({
  itemTypeViewModels,
  itemTypeId,
  onSelectTrait,
  tabKey,
  onTabKeyChange,
  collapsedStructureCards,
  onToggleStructureCard,
}) {
  const vm = useItemTypeViewModel(itemTypeViewModels, itemTypeId)
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
      actions: editing && (
        <>
          <Button variant="ghost-secondary" icon="plus" onClick={() => vm.newGroup()}>
            New group
          </Button>
          <Button variant="ghost-secondary" icon="plus" onClick={() => vm.newProperty()}>
            New property
          </Button>
        </>
      ),
      view: (
        <PropertyTreeView
          tree={vm.propertyTree}
          onSelectTrait={onSelectTrait}
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
      // Opens the new-link dialog, once it exists.
      actions: editing && (
        <Button variant="ghost-secondary" icon="plus">
          New link
        </Button>
      ),
      view: (
        <ItemTypeLinksView
          links={vm.links}
          itemTypeName={itemType.name}
          onSelectTrait={onSelectTrait}
          deletion={editing ? vm.linkDeletion : undefined}
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
          <item-type-structure-footer>
            {vm.conflicts.length > 0 && <ItemTypeConflictsView conflicts={vm.conflicts} />}
            {vm.saveError && vm.conflicts.length === 0 && (
              <p className="item-type-save-error" role="alert">
                {vm.saveError}
              </p>
            )}
            {editing ? (
              <>
                <Button variant="ghost-secondary" onClick={vm.cancel} disabled={vm.saving}>
                  Cancel
                </Button>
                <Button variant="secondary" onClick={vm.save} disabled={vm.saving || vm.conflicts.length > 0}>
                  {vm.saving ? 'Saving…' : 'Save'}
                </Button>
              </>
            ) : (
              <Button variant="secondary" onClick={vm.startEditing}>
                Edit
              </Button>
            )}
          </item-type-structure-footer>
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
      </item-type-detail-header>
      {/* Not keyed on the item type -- the active tab and collapsed Structure cards should survive
          switching selection, so e.g. staying on "Properties" while browsing different item types.
          Controlled from SchemaViewModel (not TabView's own internal state) so they
          also survive this whole component unmounting when the sidebar accordion switches to a
          different section and back. */}
      <TabView sections={sections} activeKey={tabKey} onActiveKeyChange={onTabKeyChange} borderStyle="top" />
      {vm.groupEditor && (
        <GroupEditDialog
          group={vm.groupEditor.group}
          isNew={vm.groupEditor.isNew}
          onApply={vm.groupEditor.apply}
          onCancel={vm.groupEditor.cancel}
        />
      )}
      {vm.propertyEditor && (
        <PropertyEditDialog
          property={vm.propertyEditor.property}
          isNew={vm.propertyEditor.isNew}
          controlledLists={vm.propertyEditor.controlledLists}
          onApply={vm.propertyEditor.apply}
          onCancel={vm.propertyEditor.cancel}
        />
      )}
    </item-type-detail-view>
  )
}

export default ItemTypeDetailView
