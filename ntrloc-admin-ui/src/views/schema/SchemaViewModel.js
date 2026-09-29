import { useState } from 'react'

// Holds which item type (if any) is selected for the detail pane in schema-view-content -- lifted
// up to here rather than into SchemaItemTypesView, since the list that sets the selection and the
// detail view that reads it are siblings, not parent/child.
export function useSchemaViewModel() {
  const [selectedItemTypeId, setSelectedItemTypeId] = useState(null)

  return { selectedItemTypeId, selectItemType: setSelectedItemTypeId }
}
