import { useSyncExternalStore } from 'react'
import { useControlledListModel, useControlledListValues } from '../../../global/model/ControlledListModel'
import { getRawSchema } from '../../../global/model/SchemaCache'
import { deleteDefinition, sameValues } from '../SchemaEditing'

const TYPE_LABELS = { STRING: 'String', INT: 'Integer', LONG: 'Long' }
const TYPE_OPTIONS = Object.entries(TYPE_LABELS).map(([value, label]) => ({ value, label }))
const OVERVIEW_LABELS = { name: 'Name', valueType: 'Value type' }
const WHOLE_NUMBER = /^-?\d+$/

// Keys for value rows added here, until they're saved.
let nextNewValue = 1

// A draft as the saved list it will become, with nothing set yet but its name.
const draftList = (draft) => ({ id: draft.id, name: draft.name, valueType: 'STRING', usedBy: [] })

const asEntries = (rows) =>
  rows.filter((row) => !row.removed).map((row) => ({ value: row.value.trim(), label: row.label.trim() || null }))

// The first thing wrong with the list as edited, or null: Save waits until it's fixed. The server
// holds a list's values to the same rules -- distinct values, and labels on all of them or none.
function problemWith(fields, rows, nameTaken) {
  const name = fields.name.trim()
  if (name === '') return 'A controlled list needs a name.'
  if (nameTaken(name)) return `There's already a controlled list named ${name}.`
  const kept = rows.filter((row) => !row.removed)
  const values = new Set()
  const labels = new Set()
  for (const [index, row] of kept.entries()) {
    const value = row.value.trim()
    const label = row.label.trim()
    if (value === '') return `Value ${index + 1} is blank.`
    if (fields.valueType !== 'STRING' && !WHOLE_NUMBER.test(value)) return `${value} isn't a whole number.`
    if (values.has(value)) return `${value} is in the list twice.`
    values.add(value)
    if (label !== '' && labels.has(label)) return `The label ${label} is used twice.`
    labels.add(label)
  }
  const labeled = kept.filter((row) => row.label.trim() !== '').length
  if (labeled > 0 && labeled < kept.length) return 'Give every value a label, or none of them.'
  return null
}

// A controlled list as its detail view shows and edits it: what the schema says about it, its values
// (loaded on their own, as they aren't part of the schema), and the properties that use it. Its pending
// edits live in `controlledListViewModels` (one per list, kept by SchemaViewModel), held until Save as
// an item type's are. `navigation` ({ selectControlledList, closeControlledList }) moves on once a
// draft is saved or discarded, or once a list is deleted.
export function useControlledListDetailViewModel(controlledListViewModels, listId, navigation) {
  const vm = controlledListViewModels.get(listId)
  const state = useSyncExternalStore(controlledListViewModels.subscribe, vm.getSnapshot)
  const { getControlledList, getControlledLists } = useControlledListModel()
  const draft = controlledListViewModels.getDraft(listId)
  const list = draft ? draftList(draft) : getControlledList(listId)
  const values = useControlledListValues(list && !draft ? listId : null)
  if (!list) return { list: null }

  const saved = { name: list.name, valueType: list.valueType }
  const fields = { ...saved }
  for (const [key, change] of Object.entries(state.fieldChanges)) fields[key] = change.value
  const setField = (key) => (value) => vm.setField(key, value, saved[key])

  // The values as saved now, as editable rows -- null until they've loaded.
  let savedRows = null
  if (draft) savedRows = []
  else if (values.status === 'ready') {
    savedRows = values.values.map((entry, index) => ({
      key: `saved-${index}`,
      value: String(entry.value),
      label: entry.label ?? '',
    }))
  }
  const rows = state.valuesChange?.rows ?? savedRows ?? []
  const setRows = (next) => vm.setValues(next, savedRows)

  const nameTaken = (name) =>
    [...getControlledLists(), ...controlledListViewModels.getDrafts()].some(
      (other) => other.id !== listId && other.name.toLowerCase() === name.toLowerCase(),
    )

  const fieldConflicts = Object.entries(state.fieldChanges)
    .filter(([key, change]) => change.base !== saved[key] && change.value !== saved[key])
    .map(([key]) => ({
      key,
      label: OVERVIEW_LABELS[key],
      theirs: key === 'valueType' ? TYPE_LABELS[saved[key]] : saved[key],
      keepMine: () => vm.keepMine(key, saved[key]),
      useTheirs: () => vm.useTheirs(key),
    }))
  // As for a field: the values someone else saved since this edit started, unless they're the ones
  // entered here anyway.
  const change = state.valuesChange
  const valuesConflict =
    change && savedRows && !sameValues(change.base, savedRows) && !sameValues(change.rows, savedRows)
      ? [
          {
            key: 'values',
            label: 'Values',
            note: 'were changed by someone else',
            keepMine: () => vm.keepMyValues(savedRows),
            useTheirs: vm.useTheirValues,
          },
        ]
      : []

  function saveMutations() {
    const mutations = []
    const nameChanged = 'name' in state.fieldChanges
    if (!nameChanged && !change) return mutations
    mutations.push({ type: 'EXPECT_CONTROLLED_LIST', listId, name: saved.name, values: change ? asEntries(savedRows) : null })
    mutations.push({
      type: 'UPDATE_CONTROLLED_LIST',
      listId,
      name: nameChanged ? fields.name.trim() : null,
      values: change ? asEntries(rows) : null,
    })
    return mutations
  }

  async function save() {
    if (draft) {
      const created = {
        type: 'CREATE_CONTROLLED_LIST',
        refId: listId,
        name: fields.name.trim(),
        valueType: fields.valueType,
        values: asEntries(rows),
      }
      if (!(await vm.save([created]))) return
      const stored = getRawSchema()?.controlledLists.find((other) => other.name === created.name)
      if (stored) navigation.selectControlledList(stored.id)
      else navigation.closeControlledList()
      controlledListViewModels.discard(listId)
      return
    }
    await vm.save(saveMutations())
  }

  const usedBy = [...list.usedBy].sort(
    (a, b) => a.ownerLabel.localeCompare(b.ownerLabel) || a.propertyName.localeCompare(b.propertyName),
  )
  const detached = usedBy.map((usage) => `${usage.ownerLabel} · ${usage.propertyName}`)
  return {
    list,
    editing: state.editing,
    dirty: state.dirty,
    saving: state.saving,
    saveError: state.saveError,
    conflicts: [...fieldConflicts, ...valuesConflict],
    problem: problemWith(fields, rows, nameTaken),
    startEditing: vm.startEditing,
    isDraft: Boolean(draft),
    noun: 'controlled list',
    deletion: draft
      ? null
      : {
          consequence: detached.length > 0 ? `Properties using it stop using it: ${detached.join(', ')}.` : null,
          confirm: async () => {
            await deleteDefinition({ type: 'DELETE_CONTROLLED_LIST', listId })
            navigation.closeControlledList()
            controlledListViewModels.discard(listId)
          },
        },
    cancel: draft
      ? () => {
          navigation.closeControlledList()
          controlledListViewModels.discard(listId)
        }
      : vm.cancel,
    save,
    overview: {
      fields,
      setName: setField('name'),
      // Only a new list's type can be chosen; a saved one's values are already of it.
      typeEditable: Boolean(draft),
      typeOptions: TYPE_OPTIONS,
      typeLabel: TYPE_LABELS[fields.valueType] ?? fields.valueType,
      setValueType: setField('valueType'),
      usedBy,
    },
    values: {
      status: draft ? 'ready' : values.status,
      rows,
      editable: state.editing && savedRows !== null,
      setValue: (key, field, text) => setRows(rows.map((row) => (row.key === key ? { ...row, [field]: text } : row))),
      // A saved value is struck through until Save, so it can be kept after all; a new one just goes.
      toggleRemove: (key) => {
        const row = rows.find((candidate) => candidate.key === key)
        setRows(
          row.isNew
            ? rows.filter((other) => other.key !== key)
            : rows.map((other) => (other.key === key ? { ...other, removed: !other.removed } : other)),
        )
      },
      add: () => setRows([...rows, { key: `new-value-${nextNewValue++}`, value: '', label: '', isNew: true }]),
    },
  }
}
