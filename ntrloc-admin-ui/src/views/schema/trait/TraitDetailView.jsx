import { useState } from 'react'
import CollapsibleCard from '../../../controls/collapsible-card/CollapsibleCard'
import DefinitionActions from '../DefinitionActions'
import Filter from '../../../controls/filter/Filter'
import IconButton from '../../../controls/icon-button/IconButton'
import PropertyTreeView from '../PropertyTreeView'
import ItemTypeLinksView from '../item-type/ItemTypeLinksView'
import EditMessages from '../EditMessages'
import StructureDialogs from '../item-type/StructureDialogs'
import TraitOverviewView from './TraitOverviewView'
import { useTraitDetailViewModel } from './TraitDetailViewModel'
import './TraitDetailView.css'

// A trait's own shape as the same cards an item type's Structure tab shows -- Overview (with the
// item types that implement it), Properties and Links -- edited the same way: Edit opens every card
// for changes, which are held until Save or Cancel. Which cards (and links) are collapsed is kept by
// the caller, as an item type's are, under keys of the trait's own so the two don't fold each other.
function TraitDetailView({
  traitViewModels,
  traitId,
  onSelectItemType,
  onSelectTrait,
  draftNavigation,
  collapsed,
  onToggleCollapsed,
}) {
  const vm = useTraitDetailViewModel(traitViewModels, traitId, draftNavigation)
  // Like the cards' collapsed state, kept while browsing other traits.
  const [propertyFilter, setPropertyFilter] = useState('')
  const { trait, editing } = vm

  if (!trait) return <p>Trait not found.</p>

  const cardKey = (key) => `trait-${key}`
  const cards = [
    {
      key: 'overview',
      label: 'Overview',
      view: <TraitOverviewView overview={vm.overview} editable={editing} onSelectItemType={onSelectItemType} />,
    },
    {
      key: 'properties',
      label: 'Properties',
      actions: !collapsed.has(cardKey('properties')) && (
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
          collapsed={collapsed}
          onToggleCollapsed={onToggleCollapsed}
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

  return (
    <trait-detail-view>
      <trait-detail-header>
        <h2>{trait.name}</h2>
        {trait.description && <p className="trait-detail-description">{trait.description}</p>}
        <DefinitionActions vm={vm} name={trait.name} />
      </trait-detail-header>
      <EditMessages vm={vm} />
      <trait-detail-cards>
        {cards.map((card) => (
          <CollapsibleCard
            key={card.key}
            title={card.label}
            actions={card.actions}
            expanded={!collapsed.has(cardKey(card.key))}
            onToggle={() => onToggleCollapsed(cardKey(card.key))}
          >
            {card.view}
          </CollapsibleCard>
        ))}
      </trait-detail-cards>
      <StructureDialogs vm={vm} />
    </trait-detail-view>
  )
}

export default TraitDetailView
