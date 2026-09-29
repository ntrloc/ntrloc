import Accordion from '../../controls/accordion/Accordion'
import { useSchemaModel } from '../../global/model/SchemaModel'
import SchemaItemTypesView from './SchemaItemTypesView'
import SchemaTraitsView from './SchemaTraitsView'
import SchemaControlledListsView from './SchemaControlledListsView'
import './SchemaView.css'

const SECTIONS = [
  { key: 'itemTypes', label: 'Item Types', view: <SchemaItemTypesView /> },
  { key: 'traits', label: 'Traits', view: <SchemaTraitsView /> },
  { key: 'controlledLists', label: 'Controlled Lists', view: <SchemaControlledListsView /> },
]

function SchemaView() {
  const { status } = useSchemaModel()

  if (status === 'error') return <p className="schema-view-error">Failed to load schema.</p>
  if (status !== 'ready') return <p>Loading…</p>

  return (
    <schema-view>
      <schema-view-nav>
        <Accordion sections={SECTIONS} />
      </schema-view-nav>
      <schema-view-content />
    </schema-view>
  )
}

export default SchemaView
