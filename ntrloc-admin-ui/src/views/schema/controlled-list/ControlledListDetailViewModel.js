import { useControlledListModel, useControlledListValues } from '../../../global/model/ControlledListModel'

const TYPE_LABELS = { STRING: 'String', INT: 'Integer', LONG: 'Long' }

// A controlled list as its detail view shows it: what the schema says about it, its values (loaded
// on their own, as they aren't part of the schema), and the properties that use it, by owner name.
export function useControlledListDetailViewModel(listId) {
  const { getControlledList } = useControlledListModel()
  const list = getControlledList(listId)
  const values = useControlledListValues(list ? listId : null)
  if (!list) return { list: null }

  const usedBy = [...list.usedBy].sort(
    (a, b) => a.ownerLabel.localeCompare(b.ownerLabel) || a.propertyName.localeCompare(b.propertyName),
  )
  return {
    list,
    typeLabel: TYPE_LABELS[list.valueType] ?? list.valueType,
    values,
    usedBy,
  }
}
