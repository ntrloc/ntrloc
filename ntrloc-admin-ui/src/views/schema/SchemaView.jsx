import Accordion from '../../controls/accordion/Accordion'
import { useSchemaModel } from '../../global/model/SchemaModel'
import { useSchemaViewModel } from './SchemaViewModel'
import SchemaItemTypesView from './SchemaItemTypesView'
import SchemaTraitsView from './SchemaTraitsView'
import SchemaControlledListsView from './SchemaControlledListsView'
import ItemTypeDetailView from './ItemTypeDetailView'
import './SchemaView.css'

function SchemaView() {
  const { status } = useSchemaModel()
  const { selectedItemTypeId, selectItemType } = useSchemaViewModel()

  if (status === 'error') return <p className="schema-view-error">Failed to load schema.</p>
  if (status !== 'ready') return <p>Loading…</p>

  const sections = [
    {
      key: 'itemTypes',
      label: 'Item Types',
      view: <SchemaItemTypesView selectedItemTypeId={selectedItemTypeId} onSelectItemType={selectItemType} />,
    },
    { key: 'traits', label: 'Traits', view: <SchemaTraitsView /> },
    { key: 'controlledLists', label: 'Controlled Lists', view: <SchemaControlledListsView /> },
  ]

  return (
    <schema-view>
      <schema-view-nav>
        <Accordion sections={sections} />
      </schema-view-nav>
      <schema-view-content>
        {selectedItemTypeId ? (
          <ItemTypeDetailView itemTypeId={selectedItemTypeId} />
        ) : (
          <p className="schema-view-placeholder">Select an item type, trait, or controlled list.</p>
        )}
      </schema-view-content>
    </schema-view>
  )
}

export default SchemaView
