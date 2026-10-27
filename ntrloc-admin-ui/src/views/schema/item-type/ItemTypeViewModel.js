import { useSyncExternalStore } from 'react'
import { useItemTypeModel } from '../../../global/model/ItemTypeModel'
import { useTraitModel } from '../../../global/model/TraitModel'
import { useLinkModel } from '../../../global/model/LinkModel'
import { useControlledListModel } from '../../../global/model/ControlledListModel'
import { reload as reloadSchema } from '../../../global/model/SchemaCache'
import { applySchemaMutations, SchemaConflictError } from '../../../global/delegate/SchemaDelegate'

const OVERVIEW_LABELS = {
  name: 'Item type name',
  description: 'Description',
  displayLabelPattern: 'Display label',
  abstractType: 'Abstract type',
  supertypeId: 'Parent type',
}

// What the property editor can change.
const PROPERTY_FIELDS = ['name', 'description', 'cardinality', 'usage', 'controlledListId']

function propertyFields(property) {
  return {
    name: property.name,
    description: property.description ?? '',
    cardinality: property.cardinality,
    usage: property.usage,
    controlledListId: property.controlledListId ?? null,
  }
}

function sameProperty(a, b) {
  return PROPERTY_FIELDS.every((key) => a[key] === b[key])
}

// The properties an item type declares itself, by id -- the ones it can edit or delete. Inherited
// ones, and everything under an inherited group, belong to a trait or supertype.
function ownProperties(tree, byId = new Map()) {
  tree.properties.filter((p) => !p.inherited).forEach((p) => byId.set(p.id, p))
  tree.groups.filter((g) => !g.inherited).forEach((g) => ownProperties(g, byId))
  return byId
}

// The tree as it should be shown: each property with pending edits carries them, flagged `edited`.
function withPropertyChanges(tree, propertyChanges) {
  return {
    ...tree,
    properties: tree.properties.map((p) =>
      propertyChanges[p.id] ? { ...p, ...propertyChanges[p.id].values, edited: true } : p,
    ),
    groups: tree.groups.map((g) => withPropertyChanges(g, propertyChanges)),
  }
}

// New properties and groups not yet saved. Each has a parentId: null for the item type's top level,
// otherwise the id of a saved group or of another new one.
function newChildren(parentId, newGroups, newProperties) {
  return {
    properties: newProperties
      .filter((p) => p.parentId === parentId)
      .map(({ id, values }) => ({ kind: 'property', id, ...values, edited: true })),
    groups: newGroups
      .filter((g) => g.parentId === parentId)
      .map(({ id, values }) => ({ kind: 'group', id, ...values, isNew: true, ...newChildren(id, newGroups, newProperties) })),
  }
}

// The tree with every new property and group shown under its parent.
function withNewNodes(container, parentId, newGroups, newProperties) {
  const added = newChildren(parentId, newGroups, newProperties)
  return {
    ...container,
    properties: [...container.properties, ...added.properties],
    groups: [...container.groups.map((g) => withNewNodes(g, g.id, newGroups, newProperties)), ...added.groups],
  }
}

// A new property as the server's create payload, whether on its own or inside a new group.
function propertyCreateSpec(values) {
  return {
    name: values.name,
    description: values.description || null,
    propertyType: values.type,
    cardinality: values.cardinality,
    usage: values.usage,
    facetable: false,
    controlledListId: values.controlledListId,
  }
}

// A new group with everything new inside it, created in one mutation.
function groupCreateSpec(group, newGroups, newProperties) {
  return {
    name: group.values.name,
    description: group.values.description || null,
    properties: newProperties.filter((p) => p.parentId === group.id).map((p) => propertyCreateSpec(p.values)),
    groups: newGroups.filter((g) => g.parentId === group.id).map((g) => groupCreateSpec(g, newGroups, newProperties)),
  }
}

// Ids for new properties and groups until the server gives them real ones -- never sent to it.
let nextNewId = 1
const NEW_PROPERTY_PREFIX = 'new-property-'
const NEW_GROUP_PREFIX = 'new-group-'
const isNewProperty = (id) => id.startsWith(NEW_PROPERTY_PREFIX)
const isNewGroup = (id) => id.startsWith(NEW_GROUP_PREFIX)

// Where something new is added: the item type itself (parentId null) or a saved group.
function savedParent(itemTypeId, parentId) {
  return parentId === null ? { parentKind: 'ITEM', parentId: itemTypeId } : { parentKind: 'GROUP', parentId }
}

function sortByName(nodes) {
  return [...nodes].sort((a, b) => a.name.localeCompare(b.name))
}

function toggled(set, id) {
  const next = new Set(set)
  if (!next.delete(id)) next.add(id)
  return next
}

// A property's pending edits as mutations. UPDATE_PROPERTY replaces every field at once, so the type
// and facetable flag it doesn't edit go along unchanged; the controlled list is set separately, and
// only when it changed.
function propertyMutations(property, values) {
  const mutations = [
    {
      type: 'UPDATE_PROPERTY',
      id: property.id,
      name: values.name,
      description: values.description || null,
      propertyType: property.type,
      cardinality: values.cardinality,
      usage: values.usage,
      facetable: property.facetable,
    },
  ]
  if (values.controlledListId !== (property.controlledListId ?? null)) {
    mutations.push({
      type: 'SET_PROPERTY_CONTROLLED_LIST',
      propertyId: property.id,
      list: values.controlledListId ? { type: 'EXISTING', listId: values.controlledListId } : null,
    })
  }
  return mutations
}

// The pending edits as one mutation batch. Deletes and trait removals go first and trait additions
// last, so a name freed up by one can be taken by another in the same save -- the server checks
// property names against traits and supertypes as each mutation is applied. UPDATE_ITEM replaces
// every Overview field at once, so it's sent whole, after EXPECT_ITEM: the server rejects the batch
// if those fields no longer hold what this editor last saw, rather than overwriting someone else's
// change made since. Edits to a property that's since been deleted, or is being deleted here, are
// dropped. New properties and groups come after deletes and edits, so they can take a name those
// free up; anything new inside a new group goes out as part of that group's own mutation.
function toMutations(itemTypeId, saved, fields, state, savedProperties) {
  const overview = {
    name: fields.name,
    description: fields.description,
    supertypeId: fields.supertypeId,
    abstractType: fields.abstractType,
    displayLabelPattern: fields.displayLabelPattern || null,
  }
  const expected = {
    name: saved.name,
    description: saved.description,
    supertypeId: saved.supertypeId,
    abstractType: saved.abstractType,
    displayLabelPattern: saved.displayLabelPattern || null,
  }
  const overviewChanged = Object.keys(state.fieldChanges).length > 0
  return [
    ...[...state.deletedPropertyIds].map((id) => ({ type: 'DELETE_PROPERTY', id })),
    ...[...state.deletedLinkIds].map((id) => ({ type: 'DELETE_LINK', id })),
    ...Object.entries(state.propertyChanges)
      .filter(([id]) => savedProperties.has(id) && !state.deletedPropertyIds.has(id))
      .flatMap(([id, change]) => propertyMutations(savedProperties.get(id), change.values)),
    ...state.newProperties
      .filter((p) => p.parentId === null || !isNewGroup(p.parentId))
      .map((p) => ({ type: 'ADD_PROPERTY', ...savedParent(itemTypeId, p.parentId), ...propertyCreateSpec(p.values) })),
    ...state.newGroups
      .filter((g) => g.parentId === null || !isNewGroup(g.parentId))
      .map((g) => ({
        type: 'ADD_PROPERTY_GROUP',
        ...savedParent(itemTypeId, g.parentId),
        ...groupCreateSpec(g, state.newGroups, state.newProperties),
      })),
    ...[...state.removedTraitIds]
      .filter((traitId) => saved.traitIds.includes(traitId))
      .map((traitId) => ({ type: 'REMOVE_TRAIT', itemId: itemTypeId, traitId })),
    ...(overviewChanged
      ? [
          { type: 'EXPECT_ITEM', id: itemTypeId, ...expected },
          { type: 'UPDATE_ITEM', id: itemTypeId, ...overview },
        ]
      : []),
    ...[...state.addedTraitIds]
      .filter((traitId) => !saved.traitIds.includes(traitId))
      .map((traitId) => ({ type: 'IMPLEMENT_TRAIT', itemId: itemTypeId, traitId })),
  ]
}

// One item type's pending edits, held until Save or Cancel. Records only what changed on top of the
// saved item type (see useItemTypeViewModel, which lays these over ItemTypeModel's data), so a live
// schema update from elsewhere still shows through anything not edited here.
//
// Each Overview change keeps the saved value it was made against (`base`). If someone else then
// saves a different value for that same field, the field is in conflict until the user picks
// theirs or keeps their own. A property's edits work the same way, as a whole: a conflict if
// someone else changed that property at all. Traits are kept as additions and removals rather than a whole list, so
// they merge with anyone else's trait changes instead of conflicting.
//
// A plain class rather than hook state so SchemaViewModel can keep one per item type for as long as
// the Schema view is mounted -- edits survive browsing other item types and coming back. Methods are
// arrow class fields so they can be handed around detached, same as SearchModel's.
export class ItemTypeViewModel {
  #snapshot = {
    editing: false,
    fieldChanges: {},
    addedTraitIds: new Set(),
    removedTraitIds: new Set(),
    deletedPropertyIds: new Set(),
    deletedLinkIds: new Set(),
    propertyChanges: {},
    newProperties: [],
    newGroups: [],
    // What the property and group editors are open on: null, { mode: 'create', parentId }, or
    // { mode: 'edit', id }.
    propertyEditor: null,
    groupEditor: null,
    dirty: false,
    saving: false,
    saveError: null,
  }
  #onChange

  constructor(onChange) {
    this.#onChange = onChange
  }

  getSnapshot = () => this.#snapshot

  startEditing = () => this.#update({ editing: true })

  cancel = () => this.#reset()

  // savedValue is what the field holds on the server right now -- setting a field back to it drops
  // the change rather than keeping a no-op edit around.
  setField = (key, value, savedValue) => {
    const fieldChanges = { ...this.#snapshot.fieldChanges }
    if (value === savedValue) delete fieldChanges[key]
    else fieldChanges[key] = { value, base: fieldChanges[key]?.base ?? savedValue }
    this.#update({ fieldChanges })
  }

  // Resolves a conflict in the user's favour: their value stays, now made against the latest save.
  keepMine = (key, savedValue) => {
    const change = this.#snapshot.fieldChanges[key]
    this.#update({ fieldChanges: { ...this.#snapshot.fieldChanges, [key]: { ...change, base: savedValue } } })
  }

  useTheirs = (key) => {
    const fieldChanges = { ...this.#snapshot.fieldChanges }
    delete fieldChanges[key]
    this.#update({ fieldChanges })
  }

  // Kept as the difference from the saved traits, not the list itself.
  setTraitIds = (traitIds, savedTraitIds) =>
    this.#update({
      addedTraitIds: new Set(traitIds.filter((id) => !savedTraitIds.includes(id))),
      removedTraitIds: new Set(savedTraitIds.filter((id) => !traitIds.includes(id))),
    })

  // A row's edit button: a new group opens the group editor, anything else the property editor.
  openEditor = (id) =>
    this.#update(isNewGroup(id) ? { groupEditor: { mode: 'edit', id } } : { propertyEditor: { mode: 'edit', id } })

  openNewPropertyEditor = (parentId = null) => this.#update({ propertyEditor: { mode: 'create', parentId } })

  closePropertyEditor = () => this.#update({ propertyEditor: null })

  openNewGroupEditor = (parentId = null) => this.#update({ groupEditor: { mode: 'create', parentId } })

  closeGroupEditor = () => this.#update({ groupEditor: null })

  addProperty = (parentId, values) =>
    this.#update({
      newProperties: [...this.#snapshot.newProperties, { id: `${NEW_PROPERTY_PREFIX}${nextNewId++}`, parentId, values }],
      propertyEditor: null,
    })

  updateNewProperty = (id, values) =>
    this.#update({
      newProperties: this.#snapshot.newProperties.map((p) => (p.id === id ? { ...p, values } : p)),
      propertyEditor: null,
    })

  addGroup = (parentId, values) =>
    this.#update({
      newGroups: [...this.#snapshot.newGroups, { id: `${NEW_GROUP_PREFIX}${nextNewId++}`, parentId, values }],
      groupEditor: null,
    })

  updateNewGroup = (id, values) =>
    this.#update({
      newGroups: this.#snapshot.newGroups.map((g) => (g.id === id ? { ...g, values } : g)),
      groupEditor: null,
    })

  // A row's trash button. Something new is dropped outright -- a new group with everything new
  // inside it -- since there's nothing saved to mark; a saved property is marked for deletion.
  toggleDeleted = (id) => {
    if (!isNewProperty(id) && !isNewGroup(id)) {
      this.#update({ deletedPropertyIds: toggled(this.#snapshot.deletedPropertyIds, id) })
      return
    }
    const removed = new Set([id])
    for (let grew = true; grew; ) {
      grew = false
      for (const g of this.#snapshot.newGroups) {
        if (removed.has(g.parentId) && !removed.has(g.id)) {
          removed.add(g.id)
          grew = true
        }
      }
    }
    this.#update({
      newGroups: this.#snapshot.newGroups.filter((g) => !removed.has(g.id)),
      newProperties: this.#snapshot.newProperties.filter((p) => !removed.has(p.id) && !removed.has(p.parentId)),
    })
  }

  // Applies the property editor's values. As with setField, values matching what's saved drop the
  // change; `base` is the saved property the first edit was made against.
  setProperty = (propertyId, values, savedValues) => {
    const propertyChanges = { ...this.#snapshot.propertyChanges }
    if (sameProperty(values, savedValues)) delete propertyChanges[propertyId]
    else propertyChanges[propertyId] = { values, base: propertyChanges[propertyId]?.base ?? savedValues }
    this.#update({ propertyChanges, propertyEditor: null })
  }

  keepMyProperty = (propertyId, savedValues) => {
    const change = this.#snapshot.propertyChanges[propertyId]
    this.#update({
      propertyChanges: { ...this.#snapshot.propertyChanges, [propertyId]: { ...change, base: savedValues } },
    })
  }

  useTheirProperty = (propertyId) => {
    const propertyChanges = { ...this.#snapshot.propertyChanges }
    delete propertyChanges[propertyId]
    this.#update({ propertyChanges })
  }

  toggleDeletedLink = (linkId) => this.#update({ deletedLinkIds: toggled(this.#snapshot.deletedLinkIds, linkId) })

  // Sends everything as one batch the server applies all-or-nothing. On success, waits for the
  // reloaded schema before dropping the edits, so the saved values replace them without a flash of
  // the old ones. On failure every edit is kept; a conflict also reloads the schema, so whatever
  // someone else changed shows up as conflicts to resolve.
  save = async (mutations) => {
    if (this.#snapshot.saving) return
    this.#update({ saving: true, saveError: null })
    try {
      if (mutations.length > 0) {
        await applySchemaMutations(mutations)
        await reloadSchema()
      }
      this.#reset()
    } catch (error) {
      if (error instanceof SchemaConflictError) await reloadSchema().catch(() => {})
      this.#update({ saving: false, saveError: error.message })
    }
  }

  #reset() {
    this.#update({
      editing: false,
      fieldChanges: {},
      addedTraitIds: new Set(),
      removedTraitIds: new Set(),
      deletedPropertyIds: new Set(),
      deletedLinkIds: new Set(),
      propertyChanges: {},
      newProperties: [],
      newGroups: [],
      propertyEditor: null,
      groupEditor: null,
      saving: false,
      saveError: null,
    })
  }

  #update(changes) {
    const next = { ...this.#snapshot, ...changes }
    next.dirty =
      Object.keys(next.fieldChanges).length > 0 ||
      next.addedTraitIds.size > 0 ||
      next.removedTraitIds.size > 0 ||
      next.deletedPropertyIds.size > 0 ||
      next.deletedLinkIds.size > 0 ||
      Object.keys(next.propertyChanges).length > 0 ||
      next.newProperties.length > 0 ||
      next.newGroups.length > 0
    this.#snapshot = next
    this.#onChange()
  }
}

// Every item type's ItemTypeViewModel, created on first use. One subscription covers them all, and
// getDirtyIds gives the item types with unsaved changes (for the sidebar list's marker).
export class ItemTypeViewModels {
  #byId = new Map()
  #listeners = new Set()
  #dirtyIds = new Set()

  get = (itemTypeId) => {
    if (!this.#byId.has(itemTypeId)) this.#byId.set(itemTypeId, new ItemTypeViewModel(this.#changed))
    return this.#byId.get(itemTypeId)
  }

  subscribe = (listener) => {
    this.#listeners.add(listener)
    return () => this.#listeners.delete(listener)
  }

  getDirtyIds = () => this.#dirtyIds

  #changed = () => {
    this.#dirtyIds = new Set([...this.#byId].filter(([, vm]) => vm.getSnapshot().dirty).map(([id]) => id))
    this.#listeners.forEach((listener) => listener())
  }
}

// The item type as it should currently be shown: saved data from the schema models with this item
// type's pending edits laid over it, plus everything its views need to make more.
export function useItemTypeViewModel(itemTypeViewModels, itemTypeId) {
  const vm = itemTypeViewModels.get(itemTypeId)
  const state = useSyncExternalStore(itemTypeViewModels.subscribe, vm.getSnapshot)
  const { getItemType, getSupertypeCandidates, getItemTypePropertyTree } = useItemTypeModel()
  const { getTraits } = useTraitModel()
  const { getItemTypeLinkDetails } = useLinkModel()
  const { getControlledLists } = useControlledListModel()

  const itemType = getItemType(itemTypeId)
  if (!itemType) return { itemType: null }

  const saved = {
    name: itemType.name,
    description: itemType.description ?? '',
    abstractType: itemType.abstractType,
    supertypeId: itemType.supertype?.id ?? null,
    traitIds: itemType.traits.map((trait) => trait.id),
    displayLabelPattern: itemType.displayLabelPattern ?? '',
  }
  const fields = { ...saved }
  for (const [key, change] of Object.entries(state.fieldChanges)) fields[key] = change.value
  fields.traitIds = [
    ...saved.traitIds.filter((id) => !state.removedTraitIds.has(id)),
    ...[...state.addedTraitIds].filter((id) => !saved.traitIds.includes(id)),
  ]
  const setField = (key) => (value) => vm.setField(key, value, saved[key])

  const supertypeOptions = getSupertypeCandidates(itemType.id).map((candidate) => ({ value: candidate.id, label: candidate.name }))
  const supertypeName = (id) => supertypeOptions.find((opt) => opt.value === id)?.label ?? 'None'
  // How a saved Overview value reads in a conflict message.
  const shownValue = (key, value) => {
    if (key === 'supertypeId') return supertypeName(value)
    if (key === 'abstractType') return value ? 'Yes' : 'No'
    return value || '(blank)'
  }
  const traitOptions = sortByName(getTraits()).map((trait) => ({ value: trait.id, label: trait.name }))

  const savedTree = getItemTypePropertyTree(itemType.id)
  const savedProperties = ownProperties(savedTree)

  // A field someone else saved a new value for since this edit was made, when that value isn't the
  // one entered here anyway. `theirs` is that value; a property conflict has a `note` instead.
  const fieldConflicts = Object.entries(state.fieldChanges)
    .filter(([key, change]) => change.base !== saved[key] && change.value !== saved[key])
    .map(([key]) => ({
      key,
      label: OVERVIEW_LABELS[key],
      theirs: shownValue(key, saved[key]),
      keepMine: () => vm.keepMine(key, saved[key]),
      useTheirs: () => vm.useTheirs(key),
    }))
  const propertyConflicts = Object.entries(state.propertyChanges).flatMap(([id, change]) => {
    const property = savedProperties.get(id)
    const label = `Property ${change.base.name}`
    if (!property) {
      return [{ key: id, label, note: 'was deleted by someone else', useTheirs: () => vm.useTheirProperty(id) }]
    }
    const savedValues = propertyFields(property)
    if (sameProperty(change.base, savedValues) || sameProperty(change.values, savedValues)) return []
    return [
      {
        key: id,
        label,
        note: 'was changed by someone else',
        keepMine: () => vm.keepMyProperty(id, savedValues),
        useTheirs: () => vm.useTheirProperty(id),
      },
    ]
  })

  // The property editor's starting point: a fresh property, a new one already added, or a saved one
  // with any pending edits. Only a property not yet saved can have its type chosen.
  const controlledLists = getControlledLists()
  let propertyEditor = null
  const editor = state.propertyEditor
  if (editor?.mode === 'create') {
    propertyEditor = {
      isNew: true,
      property: { name: '', description: '', type: 'STRING', cardinality: 'SINGLE', usage: 'OPTIONAL', controlledListId: null },
      apply: (values) => vm.addProperty(editor.parentId, values),
    }
  } else if (editor && isNewProperty(editor.id)) {
    const added = state.newProperties.find((p) => p.id === editor.id)
    if (added) propertyEditor = { isNew: true, property: added.values, apply: (values) => vm.updateNewProperty(added.id, values) }
  } else if (editor) {
    const property = savedProperties.get(editor.id)
    if (property) {
      propertyEditor = {
        isNew: false,
        property: { ...property, ...propertyFields(property), ...state.propertyChanges[property.id]?.values },
        apply: (values) => vm.setProperty(property.id, values, propertyFields(property)),
      }
    }
  }

  // The group editor's starting point: a fresh group, or a new one already added. Saved groups
  // aren't edited here yet.
  let groupEditor = null
  if (state.groupEditor?.mode === 'create') {
    const parentId = state.groupEditor.parentId
    groupEditor = { isNew: true, group: { name: '', description: '' }, apply: (values) => vm.addGroup(parentId, values) }
  } else if (state.groupEditor) {
    const added = state.newGroups.find((g) => g.id === state.groupEditor.id)
    if (added) groupEditor = { isNew: true, group: added.values, apply: (values) => vm.updateNewGroup(added.id, values) }
  }

  return {
    itemType,
    editing: state.editing,
    dirty: state.dirty,
    saving: state.saving,
    saveError: state.saveError,
    conflicts: [...fieldConflicts, ...propertyConflicts],
    startEditing: vm.startEditing,
    cancel: vm.cancel,
    save: () => vm.save(toMutations(itemType.id, saved, fields, state, savedProperties)),
    overview: {
      fields,
      setName: setField('name'),
      setDescription: setField('description'),
      setAbstractType: setField('abstractType'),
      setTraitIds: (traitIds) => vm.setTraitIds(traitIds, saved.traitIds),
      setDisplayLabelPattern: setField('displayLabelPattern'),
      // OptionList keys its rows by value, so "None" is '' there rather than null.
      supertypeValue: fields.supertypeId ?? '',
      setSupertypeValue: (value) => setField('supertypeId')(value || null),
      supertypeOptions: [{ value: '', label: 'None' }, ...supertypeOptions],
      supertypeSummary: supertypeName(fields.supertypeId),
      traitOptions,
      traitSummary:
        traitOptions
          .filter((opt) => fields.traitIds.includes(opt.value))
          .map((opt) => opt.label)
          .join(', ') || 'None',
    },
    propertyTree: withNewNodes(
      withPropertyChanges(savedTree, state.propertyChanges),
      null,
      state.newGroups,
      state.newProperties,
    ),
    propertyDeletion: { isMarked: (id) => state.deletedPropertyIds.has(id), onToggle: vm.toggleDeleted },
    editRow: vm.openEditor,
    // New properties and groups, at the top level (no argument) or inside a group (its id).
    newProperty: vm.openNewPropertyEditor,
    newGroup: vm.openNewGroupEditor,
    // The editors while they're open, or null.
    propertyEditor: propertyEditor && { ...propertyEditor, controlledLists, cancel: vm.closePropertyEditor },
    groupEditor: groupEditor && { ...groupEditor, cancel: vm.closeGroupEditor },
    links: sortByName(getItemTypeLinkDetails(itemTypeId)),
    linkDeletion: { isMarked: (id) => state.deletedLinkIds.has(id), onToggle: vm.toggleDeletedLink },
  }
}
