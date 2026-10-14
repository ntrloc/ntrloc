import Button from '../../../controls/button/Button'
import OptionList from '../../../controls/option-list/OptionList'
import PopoverButton from '../../../controls/popover-button/PopoverButton'
import Select from '../../../controls/select/Select'
import TreeSelect from '../../../controls/tree-select/TreeSelect'
import { FILTER_OPERATORS, PAGE_SIZE_OPTIONS, useSearchPaneControlsViewModel } from './SearchPaneControlsViewModel'
import './SearchPaneControlsView.css'

// Item-type select, property-selection tree, sort, and filter are each a Select-like PopoverButton
// trigger (collapsed behind a closed, compact button until clicked -- a "Sort"/"Filter" button
// that reveals its own options panel, same shape TreeSelect's "Properties" trigger already uses),
// plus page size and the search button inline. Item type is a PopoverButton + OptionList rather
// than a native <select>, specifically so its popup always opens below the trigger like the
// others -- a native <select>'s own popup position is browser/OS-controlled (it tends to align
// the selected option with the trigger instead) and can't be forced via CSS; closing it on pick
// (rather than leaving it open until an outside click, the way Sort/Filter's multi-field panels
// do) is handled via PopoverButton's render-children form, since there's only one thing to set.
// Sort's own field/direction pickers are PopoverButton+OptionList too (nested one level inside
// Sort's own popover), for the same reason and the same close-on-pick behavior -- Filter's
// property/operator stay native <select>s for now since they weren't asked to change.
// Sort is one property + direction; filter is a single condition -- property + operator + value,
// no AND/OR/NOT nesting even though CollectionProjectionSpec.filter supports a full predicate
// tree; a v1-scoped deliberate choice, not a missing feature. The property tree can select whole
// branches, specific deep leaves, or a mix -- see propertyTree.js and checkboxTreeSelection.js for
// what a selection actually means on the wire.
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
    sortableFields,
    sortField,
    setSortField,
    sortDirection,
    setSortDirection,
    filterableFields,
    filterProperty,
    setFilterProperty,
    filterOperator,
    setFilterOperator,
    filterValue,
    setFilterValue,
    searchFilter,
    sortSummary,
    filterSummary,
    pageSize,
    setPageSize,
  } = useSearchPaneControlsViewModel()

  function handleSearchClick() {
    const itemType = itemTypes.find((it) => it.id === selectedItemTypeId)
    if (itemType) {
      onSearch(itemType.name, selectedPropertyPaths, {
        sortField: sortField || null,
        sortDirection,
        filter: searchFilter,
        limit: pageSize,
      })
    }
  }

  const disabled = schemaStatus !== 'ready'

  const selectedItemType = itemTypes.find((it) => it.id === selectedItemTypeId)
  const itemTypeSummary =
    schemaStatus === 'loading'
      ? showSchemaSpinner
        ? 'Loading…'
        : ''
      : schemaStatus === 'error'
        ? 'Failed to load item types'
        : selectedItemType?.name || 'Select item type'
  const itemTypeOptions = itemTypes.map((it) => ({ value: it.id, label: it.name }))

  return (
    <search-pane-controls-view>
      <PopoverButton summary={itemTypeSummary} disabled={disabled}>
        {({ close }) => (
          <OptionList
            options={itemTypeOptions}
            value={selectedItemTypeId}
            onChange={(id) => {
              setSelectedItemTypeId(id)
              close()
            }}
          />
        )}
      </PopoverButton>
      <TreeSelect
        nodes={[propertyTree]}
        selectedKeys={selectedPropertyKeys}
        onChange={setSelectedPropertyKeys}
        summary={selectedPropertySummary}
        disabled={disabled}
      />
      <PopoverButton summary={sortSummary} disabled={disabled}>
        <PopoverButton summary={sortField || 'None'} disabled={disabled}>
          {({ close }) => (
            <OptionList
              options={[{ value: '', label: 'None' }, ...sortableFields.map((f) => ({ value: f.name, label: f.name }))]}
              value={sortField}
              onChange={(value) => {
                setSortField(value)
                close()
              }}
            />
          )}
        </PopoverButton>
        <PopoverButton summary={sortDirection === 'DESC' ? 'Descending' : 'Ascending'} disabled={disabled || !sortField}>
          {({ close }) => (
            <OptionList
              options={[
                { value: 'ASC', label: 'Ascending' },
                { value: 'DESC', label: 'Descending' },
              ]}
              value={sortDirection}
              onChange={(value) => {
                setSortDirection(value)
                close()
              }}
            />
          )}
        </PopoverButton>
      </PopoverButton>
      <PopoverButton summary={filterSummary} disabled={disabled}>
        <Select
          name="filter-property"
          value={filterProperty}
          onChange={(e) => setFilterProperty(e.target.value)}
          disabled={disabled}
        >
          <option value="">None</option>
          {filterableFields.map((f) => (
            <option key={f.name} value={f.name}>
              {f.name}
            </option>
          ))}
        </Select>
        <Select
          name="filter-operator"
          value={filterOperator}
          onChange={(e) => setFilterOperator(e.target.value)}
          disabled={disabled || !filterProperty}
        >
          {FILTER_OPERATORS.map((op) => (
            <option key={op.value} value={op.value}>
              {op.label}
            </option>
          ))}
        </Select>
        <input
          type="text"
          className="search-pane-filter-value"
          value={filterValue}
          onChange={(e) => setFilterValue(e.target.value)}
          placeholder={filterOperator === 'LIKE' ? '%pattern%' : 'Value'}
          disabled={disabled || !filterProperty}
        />
      </PopoverButton>
      <Select
        name="page-size"
        value={pageSize}
        onChange={(e) => setPageSize(Number(e.target.value))}
        disabled={disabled}
        aria-label="Page size"
      >
        {PAGE_SIZE_OPTIONS.map((size) => (
          <option key={size} value={size}>
            {size} / page
          </option>
        ))}
      </Select>
      <Button disabled={disabled} onClick={handleSearchClick}>
        Search
      </Button>
    </search-pane-controls-view>
  )
}

export default SearchPaneControlsView
