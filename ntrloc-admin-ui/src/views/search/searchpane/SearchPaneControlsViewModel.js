import { useEffect, useState } from 'react'
import { useSchemaStatus } from '../../../global/model/SchemaCache'
import { useItemTypeModel } from '../../../global/model/ItemTypeModel'
import { useDelayedLoading } from '../../../global/viewmodel/useDelayedLoading'
import { ALL_PROPERTIES_KEY, buildPropertyTree } from './propertyTree'

// Owns this pane's item-type and property selection -- the schema itself comes from the shared
// global SchemaCache (fetched/cached/reloaded-on-change there, not here).
export function useSearchPaneControlsViewModel() {
  const schemaStatus = useSchemaStatus()
  const { getConcreteItemTypes, getProjectableProperties } = useItemTypeModel()
  const [selectedItemTypeId, setSelectedItemTypeId] = useState('')
  const [selectedPropertyKeys, setSelectedPropertyKeys] = useState(() => new Set([ALL_PROPERTIES_KEY]))
  const showSchemaSpinner = useDelayedLoading(schemaStatus === 'loading')

  const itemTypes = schemaStatus === 'ready' ? getConcreteItemTypes() : []
  const propertyNames = schemaStatus === 'ready' && selectedItemTypeId ? getProjectableProperties(selectedItemTypeId) : []
  const propertyTree = buildPropertyTree(propertyNames)

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
  }
}
