import { useEffect, useState } from 'react'
import { useSchemaStatus } from '../../../global/model/SchemaCache'
import { useItemTypeModel } from '../../../global/model/ItemTypeModel'
import { useDelayedLoading } from '../../../global/viewmodel/useDelayedLoading'
import { ALL_PROPERTIES_KEY, buildPropertyTree } from './propertyTree'

// Operator labels for FilterOperator -- wire values are PropertyValuePredicate.Operator's own enum
// names (see domain-graph-starter's Operator.java), unchanged here; only the label is for display.
// LIKE is offered literally as "matches pattern" (ILIKE server-side) rather than auto-wrapped in
// '%...%' as a "contains" operator -- the raw value is sent as-is, so a deliberate choice, not
// a missing feature; the placeholder hints at the wildcard syntax instead.
export const FILTER_OPERATORS = [
  { value: 'EQUALS', label: 'Equals' },
  { value: 'NOT_EQUALS', label: 'Not equals' },
  { value: 'LESS_THAN', label: 'Less than' },
  { value: 'LESS_THAN_OR_EQUAL', label: 'Less than or equal' },
  { value: 'GREATER_THAN', label: 'Greater than' },
  { value: 'GREATER_THAN_OR_EQUAL', label: 'Greater than or equal' },
  { value: 'LIKE', label: 'Matches pattern' },
]

export const PAGE_SIZE_OPTIONS = [10, 25, 50, 100]
const DEFAULT_PAGE_SIZE = 50

// Owns this pane's item-type, property, sort, filter, and page-size selection -- the schema itself
// comes from the shared global SchemaCache (fetched/cached/reloaded-on-change there, not here).
// Sort/filter/page-size are v1-scoped deliberately narrow: single sort field (no multi-key sort),
// single filter condition -- no AND/OR/NOT nesting even though CollectionProjectionSpec.filter
// supports a full predicate tree; see Filter's own comment in SearchPaneControlsView for why.
export function useSearchPaneControlsViewModel() {
  const schemaStatus = useSchemaStatus()
  const { getConcreteItemTypes, getProjectableProperties, getSortableFields } = useItemTypeModel()
  const [selectedItemTypeId, setSelectedItemTypeId] = useState('')
  const [selectedPropertyKeys, setSelectedPropertyKeys] = useState(() => new Set([ALL_PROPERTIES_KEY]))
  const [sortField, setSortField] = useState('')
  const [sortDirection, setSortDirection] = useState('ASC')
  const [filterProperty, setFilterProperty] = useState('')
  const [filterOperator, setFilterOperator] = useState(FILTER_OPERATORS[0].value)
  const [filterValue, setFilterValue] = useState('')
  const [pageSize, setPageSize] = useState(DEFAULT_PAGE_SIZE)
  const showSchemaSpinner = useDelayedLoading(schemaStatus === 'loading')

  const itemTypes = schemaStatus === 'ready' ? getConcreteItemTypes() : []
  const propertyNames = schemaStatus === 'ready' && selectedItemTypeId ? getProjectableProperties(selectedItemTypeId) : []
  const propertyTree = buildPropertyTree(propertyNames)
  const allSortableFields = schemaStatus === 'ready' && selectedItemTypeId ? getSortableFields(selectedItemTypeId) : []
  // Sortable but not filterable -- see ItemTypeModel.getSortableFields' own comment on why system
  // fields (itemId, createdAt, ...) can't be a filter target server-side.
  const filterableFields = allSortableFields.filter((f) => !f.system)

  // Defaults to the first item type once the schema becomes ready, without clobbering a
  // user-made selection on a later re-render (itemTypes is a fresh array each render, so this
  // guards on selectedItemTypeId rather than itemTypes identity).
  useEffect(() => {
    if (!selectedItemTypeId && itemTypes.length > 0) {
      setSelectedItemTypeId(itemTypes[0].id)
    }
  }, [itemTypes, selectedItemTypeId])

  // The property tree is a different shape per item type -- any prior selection almost certainly
  // doesn't apply to the new one, so this resets to "everything" (root checked) rather than trying
  // to carry over keys that may no longer exist.
  useEffect(() => {
    setSelectedPropertyKeys(new Set([ALL_PROPERTIES_KEY]))
  }, [selectedItemTypeId])

  // Same reasoning as the property-keys reset above -- a sort field or filter property named on
  // the *previous* item type almost certainly doesn't exist (or means something different) on the
  // new one, so these reset to "none" rather than carrying over a now-meaningless selection.
  useEffect(() => {
    setSortField('')
    setFilterProperty('')
    setFilterValue('')
  }, [selectedItemTypeId])

  // What actually gets sent to a search: root checked means "no restriction at all" (see
  // propertyTree's own comment on why that's genuinely different from listing every property visible
  // today); otherwise, the tree's own selectedKeys are already the minimal, correctly-mixed-
  // granularity request -- see checkboxTreeSelection.js -- so they're forwarded as-is.
  const selectedPropertyPaths = selectedPropertyKeys.has(ALL_PROPERTIES_KEY) ? null : Array.from(selectedPropertyKeys)

  // TreeSelect's own closed-state label -- computed here, not inside TreeSelect itself, since only
  // this view model knows what the root key/selection actually means.
  const selectedPropertySummary = selectedPropertyKeys.has(ALL_PROPERTIES_KEY)
    ? 'All properties'
    : `${selectedPropertyKeys.size} ${selectedPropertyKeys.size === 1 ? 'property' : 'properties'} selected`

  // null (not an empty-string propertyName) when no property/value is chosen -- matches
  // CollectionProjectionSpec.filter's own "null means no filter" contract, same as
  // selectedPropertyPaths above. "type" is PropertyValuePredicate's own Jackson discriminator (see
  // Predicate.java's @JsonSubTypes) -- required on the wire, not optional.
  const searchFilter =
    filterProperty && filterValue
      ? { type: 'PROPERTY_VALUE', propertyName: filterProperty, operator: filterOperator, value: filterValue }
      : null

  // Closed-state labels for the Sort/Filter PopoverButton triggers -- same reasoning as
  // selectedPropertySummary above: only this view model knows what the current selection means.
  const sortSummary = sortField ? `${sortField} ${sortDirection === 'DESC' ? '↓' : '↑'}` : 'Sort'
  const filterOperatorLabel = FILTER_OPERATORS.find((op) => op.value === filterOperator)?.label ?? filterOperator
  const filterSummary = filterProperty && filterValue ? `${filterProperty} ${filterOperatorLabel} ${filterValue}` : 'Filter'

  return {
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
    sortableFields: allSortableFields,
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
  }
}
