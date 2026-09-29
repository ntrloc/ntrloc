import TabView from '../../../controls/tabview/TabView'
import PropertyTreeView from '../PropertyTreeView'
import { useSchemaModel } from '../../../global/model/SchemaModel'
import { useTraitDetailViewModel } from './TraitDetailViewModel'
import './TraitDetailView.css'

// Same shape as ItemTypeDetailView -- header plus a TabView, the Properties tab being the exact
// same shared PropertyTreeView an item type uses, fed this trait's own tree -- just with the
// subset of tabs a trait actually has. State Machines/Access Control are item-type-only concerns:
// a trait doesn't get instantiated on its own, so neither applies here.
function TraitDetailView({ traitId, onSelectTrait, detailTabKey, onDetailTabKeyChange }) {
  const { trait } = useTraitDetailViewModel(traitId)
  const { getTraitPropertyTree } = useSchemaModel()

  if (!trait) return <p>Trait not found.</p>

  const sections = [
    { key: 'overview', label: 'Overview', view: <p>Overview coming soon.</p> },
    {
      key: 'properties',
      label: 'Properties',
      view: <PropertyTreeView tree={getTraitPropertyTree(trait.id)} onSelectTrait={onSelectTrait} />,
    },
    { key: 'links', label: 'Links', view: <p>Links coming soon.</p> },
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
