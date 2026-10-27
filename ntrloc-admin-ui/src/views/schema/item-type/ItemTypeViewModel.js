import { useSyncExternalStore } from 'react'
import { useItemTypeModel } from '../../../global/model/ItemTypeModel'
import { useTraitModel } from '../../../global/model/TraitModel'
import { useLinkModel } from '../../../global/model/LinkModel'
import { reload as reloadSchema } from '../../../global/model/SchemaCache'
import { applySchemaMutations, SchemaConflictError } from '../../../global/delegate/SchemaDelegate'

const OVERVIEW_LABELS = {
  name: 'Item type name',
  description: 'Description',
  displayLabelPattern: 'Display label',
  abstractType: 'Abstract type',
  supertypeId: 'Parent type',
}

function sortByName(nodes) {
  return [...nodes].sort((a, b) => a.name.localeCompare(b.name))
}

function toggled(set, id) {
  const next = new Set(set)
  if (!next.delete(id)) next.add(id)
  return next
}

// The pending edits as one mutation batch. Deletes and trait removals go first and trait additions
// last, so a name freed up by one can be taken by another in the same save -- the server checks
// property names against traits and supertypes as each mutation is applied. UPDATE_ITEM replaces
// every Overview field at once, so it's sent whole, after EXPECT_ITEM: the server rejects the batch
// if those fields no longer hold what this editor last saw, rather than overwriting someone else's
// change made since.
function toMutations(itemTypeId, saved, fields, state) {
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
// theirs or keeps their own. Traits are kept as additions and removals rather than a whole list, so
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
    const { [key]: _dropped, ...fieldChanges } = this.#snapshot.fieldChanges
    this.#update({ fieldChanges })
  }

  // Kept as the difference from the saved traits, not the list itself.
  setTraitIds = (traitIds, savedTraitIds) =>
    this.#update({
      addedTraitIds: new Set(traitIds.filter((id) => !savedTraitIds.includes(id))),
      removedTraitIds: new Set(savedTraitIds.filter((id) => !traitIds.includes(id))),
    })

  toggleDeletedProperty = (propertyId) =>
    this.#update({ deletedPropertyIds: toggled(this.#snapshot.deletedPropertyIds, propertyId) })

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
      next.deletedLinkIds.size > 0
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
  const traitOptions = sortByName(getTraits()).map((trait) => ({ value: trait.id, label: trait.name }))

  // A field someone else saved a new value for since this edit was made, when that value isn't the
  // one entered here anyway.
  const conflicts = Object.entries(state.fieldChanges)
    .filter(([key, change]) => change.base !== saved[key] && change.value !== saved[key])
    .map(([key]) => {
      const theirs = saved[key]
      const shown =
        key === 'supertypeId' ? supertypeName(theirs) : key === 'abstractType' ? (theirs ? 'Yes' : 'No') : theirs || '(blank)'
      return {
        key,
        label: OVERVIEW_LABELS[key],
        theirs: shown,
        keepMine: () => vm.keepMine(key, saved[key]),
        useTheirs: () => vm.useTheirs(key),
      }
    })

  return {
    itemType,
    editing: state.editing,
    dirty: state.dirty,
    saving: state.saving,
    saveError: state.saveError,
    conflicts,
    startEditing: vm.startEditing,
    cancel: vm.cancel,
    save: () => vm.save(toMutations(itemType.id, saved, fields, state)),
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
    propertyTree: getItemTypePropertyTree(itemType.id),
    propertyDeletion: { isMarked: (id) => state.deletedPropertyIds.has(id), onToggle: vm.toggleDeletedProperty },
    links: sortByName(getItemTypeLinkDetails(itemTypeId)),
    linkDeletion: { isMarked: (id) => state.deletedLinkIds.has(id), onToggle: vm.toggleDeletedLink },
  }
}
