import Accordion from '../../controls/accordion/Accordion'
import { useSchemaStatus } from '../../global/model/SchemaCache'
import { useSchemaViewModel } from './SchemaViewModel'
import SchemaItemTypesView from './item-type/SchemaItemTypesView'
import ItemTypeDetailView from './item-type/ItemTypeDetailView'
import SchemaTraitsView from './trait/SchemaTraitsView'
import TraitDetailView from './trait/TraitDetailView'
import SchemaControlledListsView from './controlled-list/SchemaControlledListsView'
import './SchemaView.css'

function SchemaView() {
  const status = useSchemaStatus()
  const {
    activeSection,
    setActiveSection,
    selectedItemTypeId,
    selectItemType,
    createItemType,
    draftItemTypes,
    draftNavigation,
    selectedTraitId,
    selectTrait,
    itemTypeTabKey,
    setItemTypeTabKey,
    collapsedStructureCards,
    toggleStructureCard,
    itemTypeViewModels,
    dirtyItemTypeIds,
    detailTabKey,
    setDetailTabKey,
  } = useSchemaViewModel()

  if (status === 'error') return <p className="schema-view-error">Failed to load schema.</p>
  if (status !== 'ready') return <p>Loading…</p>

  const sections = [
    {
      key: 'itemTypes',
      label: 'Item Types',
      view: (
        <SchemaItemTypesView
          selectedItemTypeId={selectedItemTypeId}
          onSelectItemType={selectItemType}
          dirtyItemTypeIds={dirtyItemTypeIds}
          drafts={draftItemTypes}
          onCreateItemType={createItemType}
        />
      ),
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
            itemTypeViewModels={itemTypeViewModels}
            itemTypeId={selectedItemTypeId}
            onSelectTrait={selectTrait}
            onSelectItemType={selectItemType}
            draftNavigation={draftNavigation}
            tabKey={itemTypeTabKey}
            onTabKeyChange={setItemTypeTabKey}
            collapsedStructureCards={collapsedStructureCards}
            onToggleStructureCard={toggleStructureCard}
          />
        )}
        {activeSection === 'traits' && selectedTraitId && (
          <TraitDetailView
            traitId={selectedTraitId}
            onSelectItemType={selectItemType}
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
