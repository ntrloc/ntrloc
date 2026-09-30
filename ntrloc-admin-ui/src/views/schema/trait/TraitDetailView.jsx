import TabView from '../../../controls/tabview/TabView'
import PropertyTreeView from '../PropertyTreeView'
import LinkListView from '../LinkListView'
import TraitOverviewView from './TraitOverviewView'
import { useTraitModel } from '../../../global/model/TraitModel'
import { useTraitDetailViewModel } from './TraitDetailViewModel'
import './TraitDetailView.css'

// Same shape as ItemTypeDetailView -- header plus a TabView, the Properties/Links tabs being the
// exact same shared components an item type uses, fed this trait's own data -- just with the
// subset of tabs a trait actually has. State Machines/Access Control are item-type-only concerns:
// a trait doesn't get instantiated on its own, so neither applies here.
function TraitDetailView({ traitId, onSelectItemType, onSelectTrait, detailTabKey, onDetailTabKeyChange }) {
  const { trait } = useTraitDetailViewModel(traitId)
  const { getTraitPropertyTree, getTraitLinks } = useTraitModel()

  if (!trait) return <p>Trait not found.</p>

  const sections = [
    {
      key: 'overview',
      label: 'Overview',
      view: <TraitOverviewView trait={trait} onSelectItemType={onSelectItemType} />,
    },
    {
      key: 'properties',
      label: 'Properties',
      view: <PropertyTreeView tree={getTraitPropertyTree(trait.id)} onSelectTrait={onSelectTrait} />,
    },
    { key: 'links', label: 'Links', view: <LinkListView links={getTraitLinks(trait.id)} /> },
  ]

  return (
    <trait-detail-view>
      <trait-detail-header>
        <h2>{trait.name}</h2>
        {trait.description && <p className="trait-detail-description">{trait.description}</p>}
      </trait-detail-header>
      {/* Controlled from SchemaViewModel and shared with ItemTypeDetailView's own tab state -- see
          that component's own comment on why. */}
      <TabView sections={sections} activeKey={detailTabKey} onActiveKeyChange={onDetailTabKeyChange} />
    </trait-detail-view>
  )
}

export default TraitDetailView
