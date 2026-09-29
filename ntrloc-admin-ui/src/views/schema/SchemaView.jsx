import Accordion from '../../controls/accordion/Accordion'
import { useSchemaModel } from '../../global/model/SchemaModel'
import { useSchemaViewModel } from './SchemaViewModel'
import SchemaItemTypesView from './item-type/SchemaItemTypesView'
import ItemTypeDetailView from './item-type/ItemTypeDetailView'
import SchemaTraitsView from './trait/SchemaTraitsView'
import TraitDetailView from './trait/TraitDetailView'
import SchemaControlledListsView from './controlled-list/SchemaControlledListsView'
import './SchemaView.css'

function SchemaView() {
  const { status } = useSchemaModel()
  const {
    activeSection,
    setActiveSection,
    selectedItemTypeId,
    selectItemType,
    selectedTraitId,
    selectTrait,
    detailTabKey,
    setDetailTabKey,
  } = useSchemaViewModel()

  if (status === 'error') return <p className="schema-view-error">Failed to load schema.</p>
  if (status !== 'ready') return <p>Loading…</p>

  const sections = [
    {
      key: 'itemTypes',
      label: 'Item Types',
      view: <SchemaItemTypesView selectedItemTypeId={selectedItemTypeId} onSelectItemType={selectItemType} />,
    },
    {
      key: 'traits',
      label: 'Traits',
      view: <SchemaTraitsView selectedTraitId={selectedTraitId} onSelectTrait={selectTrait} />,
    },
    { key: 'controlledLists', label: 'Controlled Lists', view: <SchemaControlledListsView /> },
  ]

  return (
    <schema-view>
      <schema-view-nav>
        <Accordion sections={sections} activeKey={activeSection} onActiveKeyChange={setActiveSection} />
      </schema-view-nav>
      <schema-view-content>
        {activeSection === 'itemTypes' && selectedItemTypeId && (
          <ItemTypeDetailView
            itemTypeId={selectedItemTypeId}
            onSelectTrait={selectTrait}
            detailTabKey={detailTabKey}
            onDetailTabKeyChange={setDetailTabKey}
          />
        )}
        {activeSection === 'traits' && selectedTraitId && (
          <TraitDetailView
            traitId={selectedTraitId}
            onSelectTrait={selectTrait}
            detailTabKey={detailTabKey}
            onDetailTabKeyChange={setDetailTabKey}
          />
        )}
        {!(
          (activeSection === 'itemTypes' && selectedItemTypeId) ||
          (activeSection === 'traits' && selectedTraitId)
        ) && <p className="schema-view-placeholder">Select an item type, trait, or controlled list.</p>}
      </schema-view-content>
    </schema-view>
  )
}

export default SchemaView
