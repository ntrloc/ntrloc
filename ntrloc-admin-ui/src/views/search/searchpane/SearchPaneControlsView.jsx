import Button from '../../../controls/button/Button'
import Select from '../../../controls/select/Select'
import TreeSelect from '../../../controls/tree-select/TreeSelect'
import { useSearchPaneControlsViewModel } from './SearchPaneControlsViewModel'
import './SearchPaneControlsView.css'

// Item-type select, property-selection tree (behind a Select-like popover trigger), and search
// button for now -- will eventually hold the rest of the pane's search criteria (filters, sort,
// etc.). The property tree can select whole branches, specific deep leaves, or a mix -- see
// propertyTree.js and checkboxTreeSelection.js for what a selection actually means on the wire.
function SearchPaneControlsView({ onSearch }) {
  const {
    schemaStatus,
    itemTypes,
    selectedItemTypeId,
    setSelectedItemTypeId,
    propertyTree,
    selectedPropertyKeys,
    setSelectedPropertyKeys,
    selectedPropertyPaths,
    selectedPropertySummary,
    showSchemaSpinner,
  } = useSearchPaneControlsViewModel()

  function handleSearchClick() {
    const itemType = itemTypes.find((it) => it.id === selectedItemTypeId)
    if (itemType) onSearch(itemType.name, selectedPropertyPaths)
  }

  return (
    <search-pane-controls-view>
      <Select
        name="item-type"
        value={selectedItemTypeId}
        onChange={(e) => setSelectedItemTypeId(e.target.value)}
        disabled={schemaStatus !== 'ready'}
      >
        {schemaStatus === 'loading' && <option>{showSchemaSpinner ? 'Loading…' : ''}</option>}
        {schemaStatus === 'error' && <option>Failed to load item types</option>}
        {itemTypes.map((it) => (
          <option key={it.id} value={it.id}>
            {it.name}
          </option>
        ))}
      </Select>
      <TreeSelect
        nodes={[propertyTree]}
        selectedKeys={selectedPropertyKeys}
        onChange={setSelectedPropertyKeys}
        summary={selectedPropertySummary}
        disabled={schemaStatus !== 'ready'}
      />
      <Button disabled={schemaStatus !== 'ready'} onClick={handleSearchClick}>
        Search
      </Button>
    </search-pane-controls-view>
  )
}

export default SearchPaneControlsView
