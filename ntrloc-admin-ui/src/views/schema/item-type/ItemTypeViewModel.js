import { useSyncExternalStore } from 'react'
import { useItemTypeModel } from '../../../global/model/ItemTypeModel'
import { useTraitModel } from '../../../global/model/TraitModel'
import { useLinkModel } from '../../../global/model/LinkModel'
import { useControlledListModel } from '../../../global/model/ControlledListModel'
import { getRawSchema, reload as reloadSchema } from '../../../global/model/SchemaCache'
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

// What the link editor can change on each direction of a link (a perspective).
function perspectiveFields(perspective) {
  return {
    name: perspective.name,
    description: perspective.description ?? '',
    minCardinality: perspective.cardinality.min,
    maxCardinality: perspective.cardinality.max ?? null,
  }
}

const samePerspective = (a, b) => Object.keys(a).every((key) => a[key] === b[key])

// A perspective as it should be shown, with any pending edits.
function withPerspectiveChange(perspective, change) {
  if (!change) return perspective
  const { name, description, minCardinality, maxCardinality } = change.values
  return { ...perspective, name, description, cardinality: { min: minCardinality, max: maxCardinality }, edited: true }
}

// The properties an item type declares itself, by id -- the ones it can edit or delete. Inherited
// ones, and everything under an inherited group, belong to a trait or supertype.
function ownProperties(tree, byId = new Map()) {
  tree.properties.filter((p) => !p.inherited).forEach((p) => byId.set(p.id, p))
  tree.groups.filter((g) => !g.inherited).forEach((g) => ownProperties(g, byId))
  return byId
}

// The groups an item type declares itself, by id -- the ones it can rename or delete.
function ownGroups(tree, byId = new Map()) {
  tree.groups
    .filter((g) => !g.inherited)
    .forEach((g) => {
      byId.set(g.id, g)
      ownGroups(g, byId)
    })
  return byId
}

const groupFields = (group) => ({ name: group.name, description: group.description ?? '' })

// The tree as it should be shown: each property or group with pending edits carries them, flagged
// `edited`.
function withPendingEdits(tree, propertyChanges, groupChanges) {
  return {
    ...tree,
    properties: tree.properties.map((p) =>
      propertyChanges[p.id] ? { ...p, ...propertyChanges[p.id].values, edited: true } : p,
    ),
    groups: tree.groups.map((g) => {
      const edited = withPendingEdits(g, propertyChanges, groupChanges)
      return groupChanges[g.id] ? { ...edited, ...groupChanges[g.id], edited: true } : edited
    }),
  }
}

// Everything saved inside the groups marked for deletion, and those groups themselves. The server
// only deletes an empty group, so groups come innermost first, after the properties in them.
function deletedGroupContents(tree, deletedGroupIds, inside = false) {
  const contents = { propertyIds: [], groupIds: [] }
  for (const group of tree.groups) {
    const deleted = inside || deletedGroupIds.has(group.id)
    const nested = deletedGroupContents(group, deletedGroupIds, deleted)
    contents.propertyIds.push(...nested.propertyIds)
    contents.groupIds.push(...nested.groupIds)
    if (deleted) {
      contents.propertyIds.push(...group.properties.map((p) => p.id))
      contents.groupIds.push(group.id)
    }
  }
  return contents
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
const NEW_LINK_PREFIX = 'new-link-'
const NEW_ITEM_TYPE_PREFIX = 'new-item-type-'
const isNewProperty = (id) => id.startsWith(NEW_PROPERTY_PREFIX)
const isNewGroup = (id) => id.startsWith(NEW_GROUP_PREFIX)
const isNewLink = (id) => id.startsWith(NEW_LINK_PREFIX)

// One direction of a new link, as the server's create payload.
const perspectiveCreateSpec = (itemId, values) => ({
  itemId,
  name: values.name,
  description: values.description || null,
  minCardinality: values.minCardinality,
  maxCardinality: values.maxCardinality,
})

// A new link, with anything new on it, as one mutation. `owner` names this item type: { itemId },
// or { itemRefId } while it's still being created in the same batch.
function linkCreateMutation(owner, link, state) {
  return {
    type: 'CREATE_LINK',
    perspectives: [
      { ...perspectiveCreateSpec(null, link.forward), ...owner },
      perspectiveCreateSpec(link.targetId, link.reverse),
    ],
    properties: state.newProperties.filter((p) => p.parentId === link.id).map((p) => propertyCreateSpec(p.values)),
    groups: state.newGroups
      .filter((g) => g.parentId === link.id)
      .map((g) => groupCreateSpec(g, state.newGroups, state.newProperties)),
  }
}

// A new item type, not yet saved, as the batch that creates it: everything on it goes out in its
// CREATE_ITEM, apart from new links, which refer to it by refId -- its temporary id.
function createMutations(draftId, fields, state) {
  return [
    {
      type: 'CREATE_ITEM',
      refId: draftId,
      name: fields.name,
      description: fields.description || null,
      supertypeId: fields.supertypeId,
      abstractType: fields.abstractType,
      displayLabelPattern: fields.displayLabelPattern || null,
      traitIds: fields.traitIds,
      properties: state.newProperties.filter((p) => p.parentId === null).map((p) => propertyCreateSpec(p.values)),
      groups: state.newGroups
        .filter((g) => g.parentId === null)
        .map((g) => groupCreateSpec(g, state.newGroups, state.newProperties)),
    },
    ...state.newLinks.map((link) => linkCreateMutation({ itemRefId: draftId }, link, state)),
  ]
}

// A new link shaped like ItemTypeModel's saved ones, so it shows in the list the same way. It uses
// its temporary id as both its perspective id and its link id.
function newLinkDetails(link, target) {
  const shown = (id, values) => ({
    id,
    name: values.name,
    description: values.description,
    cardinality: { min: values.minCardinality, max: values.maxCardinality },
    edited: true,
  })
  return {
    ...shown(link.id, link.forward),
    linkId: link.id,
    target: { name: target?.name ?? 'Unknown', kind: target?.kind ?? 'item' },
    reverse: shown(null, link.reverse),
    inherited: false,
    source: null,
    isNew: true,
  }
}

// Where something new is added: the item type itself (parentId null), one of its links (the link's
// id), or a saved group.
function savedParent(itemTypeId, parentId, linkIds) {
  if (parentId === null) return { parentKind: 'ITEM', parentId: itemTypeId }
  return { parentKind: linkIds.has(parentId) ? 'LINK' : 'GROUP', parentId }
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
// dropped. Deleting a group deletes everything saved inside it first, since the server only deletes
// an empty one; anything new added inside it is dropped. New properties and groups come after
// deletes and edits, so they can take a name those free up; anything new inside a new group goes
// out as part of that group's own mutation, and anything new on a new link as part of its
// CREATE_LINK. Property edits and additions on a link being deleted are dropped along with it.
function toMutations(itemTypeId, saved, fields, state, savedTree, linkTrees, savedPerspectives) {
  const keptLinks = linkTrees.filter(({ linkId }) => !state.deletedLinkIds.has(linkId))
  const keptLinkIds = new Set(keptLinks.map(({ linkId }) => linkId))
  const savedProperties = new Map()
  const savedGroups = new Map()
  const deletedContents = { propertyIds: [], groupIds: [] }
  for (const tree of [savedTree, ...keptLinks.map((link) => link.tree)]) {
    ownProperties(tree, savedProperties)
    ownGroups(tree, savedGroups)
    const contents = deletedGroupContents(tree, state.deletedGroupIds)
    deletedContents.propertyIds.push(...contents.propertyIds)
    deletedContents.groupIds.push(...contents.groupIds)
  }
  const deletedPropertyIds = new Set(
    [...state.deletedPropertyIds, ...deletedContents.propertyIds].filter((id) => savedProperties.has(id)),
  )
  const deletedGroupIds = new Set(deletedContents.groupIds)
  const addedUnder = (parentId) =>
    parentId === null || keptLinkIds.has(parentId) || (savedGroups.has(parentId) && !deletedGroupIds.has(parentId))
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
    ...[...deletedPropertyIds].map((id) => ({ type: 'DELETE_PROPERTY', id })),
    ...deletedContents.groupIds.map((id) => ({ type: 'DELETE_PROPERTY_GROUP', id })),
    ...[...state.deletedLinkIds].map((id) => ({ type: 'DELETE_LINK', id })),
    ...Object.entries(state.propertyChanges)
      .filter(([id]) => savedProperties.has(id) && !deletedPropertyIds.has(id))
      .flatMap(([id, change]) => propertyMutations(savedProperties.get(id), change.values)),
    ...Object.entries(state.perspectiveChanges)
      .filter(([id]) => savedPerspectives.has(id) && !state.deletedLinkIds.has(savedPerspectives.get(id).linkId))
      .map(([id, { values }]) => ({ type: 'UPDATE_PERSPECTIVE', id, ...values, description: values.description || null })),
    ...Object.entries(state.groupChanges)
      .filter(([id]) => savedGroups.has(id) && !deletedGroupIds.has(id))
      .map(([id, values]) => ({ type: 'UPDATE_PROPERTY_GROUP', id, name: values.name, description: values.description || null })),
    ...state.newProperties
      .filter((p) => addedUnder(p.parentId))
      .map((p) => ({ type: 'ADD_PROPERTY', ...savedParent(itemTypeId, p.parentId, keptLinkIds), ...propertyCreateSpec(p.values) })),
    ...state.newGroups
      .filter((g) => addedUnder(g.parentId))
      .map((g) => ({
        type: 'ADD_PROPERTY_GROUP',
        ...savedParent(itemTypeId, g.parentId, keptLinkIds),
        ...groupCreateSpec(g, state.newGroups, state.newProperties),
      })),
    ...state.newLinks.map((link) => linkCreateMutation({ itemId: itemTypeId }, link, state)),
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
    // A saved group's new name and description, by id. Unlike a property's, not checked for
    // someone else's change made meanwhile.
    groupChanges: {},
    deletedGroupIds: new Set(),
    // Each direction of a link (a perspective) with pending edits, by perspective id, kept like a
    // property's: { values, base }.
    perspectiveChanges: {},
    newProperties: [],
    newGroups: [],
    // New links: { id, targetId, forward, reverse }, each direction's values as the link editor's.
    newLinks: [],
    // The link editor, while open: { mode: 'create' }, or { id } of the link perspective it was
    // opened from.
    linkEditor: null,
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

  openGroupEditor = (id) => this.#update({ groupEditor: { mode: 'edit', id } })

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
    this.#update(this.#withoutNew(id))
  }

  // The new properties and groups left once something new -- a property, a group, or a link -- is
  // dropped, along with everything new inside it.
  #withoutNew(id) {
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
    return {
      newGroups: this.#snapshot.newGroups.filter((g) => !removed.has(g.id)),
      newProperties: this.#snapshot.newProperties.filter((p) => !removed.has(p.id) && !removed.has(p.parentId)),
    }
  }

  openLinkEditor = (perspectiveId) => this.#update({ linkEditor: { id: perspectiveId } })

  openNewLinkEditor = () => this.#update({ linkEditor: { mode: 'create' } })

  addLink = (targetId, forward, reverse) =>
    this.#update({
      newLinks: [...this.#snapshot.newLinks, { id: `${NEW_LINK_PREFIX}${nextNewId++}`, targetId, forward, reverse }],
      linkEditor: null,
    })

  updateNewLink = (id, targetId, forward, reverse) =>
    this.#update({
      newLinks: this.#snapshot.newLinks.map((link) => (link.id === id ? { ...link, targetId, forward, reverse } : link)),
      linkEditor: null,
    })

  closeLinkEditor = () => this.#update({ linkEditor: null })

  // Applies the link editor's values: [{ id, values, savedValues }], one per direction. As with
  // setProperty, values matching what's saved drop the change.
  setPerspectives = (edits) => {
    const perspectiveChanges = { ...this.#snapshot.perspectiveChanges }
    for (const { id, values, savedValues } of edits) {
      if (samePerspective(values, savedValues)) delete perspectiveChanges[id]
      else perspectiveChanges[id] = { values, base: perspectiveChanges[id]?.base ?? savedValues }
    }
    this.#update({ perspectiveChanges, linkEditor: null })
  }

  keepMyPerspective = (id, savedValues) => {
    const change = this.#snapshot.perspectiveChanges[id]
    this.#update({ perspectiveChanges: { ...this.#snapshot.perspectiveChanges, [id]: { ...change, base: savedValues } } })
  }

  useTheirPerspective = (id) => {
    const perspectiveChanges = { ...this.#snapshot.perspectiveChanges }
    delete perspectiveChanges[id]
    this.#update({ perspectiveChanges })
  }

  // A saved group's trash button: marks it, and everything inside it, for deletion.
  toggleDeletedGroup = (id) => this.#update({ deletedGroupIds: toggled(this.#snapshot.deletedGroupIds, id) })

  // Applies the group editor's values to a saved group; values matching what's saved drop the change.
  setGroup = (groupId, values, savedValues) => {
    const groupChanges = { ...this.#snapshot.groupChanges }
    if (values.name === savedValues.name && values.description === savedValues.description) delete groupChanges[groupId]
    else groupChanges[groupId] = values
    this.#update({ groupChanges, groupEditor: null })
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

  // A link's trash button. A new link is dropped outright, with anything new on it; a saved one is
  // marked for deletion.
  toggleDeletedLink = (linkId) => {
    if (!isNewLink(linkId)) {
      this.#update({ deletedLinkIds: toggled(this.#snapshot.deletedLinkIds, linkId) })
      return
    }
    this.#update({ ...this.#withoutNew(linkId), newLinks: this.#snapshot.newLinks.filter((link) => link.id !== linkId) })
  }

  // Sends everything as one batch the server applies all-or-nothing. On success, waits for the
  // reloaded schema before dropping the edits, so the saved values replace them without a flash of
  // the old ones. On failure every edit is kept; a conflict also reloads the schema, so whatever
  // someone else changed shows up as conflicts to resolve.
  save = async (mutations) => {
    if (this.#snapshot.saving) return false
    this.#update({ saving: true, saveError: null })
    try {
      if (mutations.length > 0) {
        await applySchemaMutations(mutations)
        await reloadSchema()
      }
      this.#reset()
      return true
    } catch (error) {
      if (error instanceof SchemaConflictError) await reloadSchema().catch(() => {})
      this.#update({ saving: false, saveError: error.message })
      return false
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
      groupChanges: {},
      deletedGroupIds: new Set(),
      perspectiveChanges: {},
      newProperties: [],
      newGroups: [],
      newLinks: [],
      linkEditor: null,
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
      Object.keys(next.groupChanges).length > 0 ||
      next.deletedGroupIds.size > 0 ||
      Object.keys(next.perspectiveChanges).length > 0 ||
      next.newProperties.length > 0 ||
      next.newGroups.length > 0 ||
      next.newLinks.length > 0
    this.#snapshot = next
    this.#onChange()
  }
}

// Every item type's ItemTypeViewModel, created on first use. One subscription covers them all, and
// getDirtyIds gives the item types with unsaved changes (for the sidebar list's marker).
//
// Also the new item types not yet saved (drafts): each has a temporary id and starts out being
// edited. A draft is nothing but unsaved changes, so it always counts as dirty; Cancel discards it,
// and Save creates it.
export class ItemTypeViewModels {
  #byId = new Map()
  #listeners = new Set()
  #dirtyIds = new Set()
  #drafts = []
  #nextDraftId = 1

  createDraft = (name) => {
    const id = `${NEW_ITEM_TYPE_PREFIX}${this.#nextDraftId++}`
    this.#drafts = [...this.#drafts, { id, name }]
    this.get(id).startEditing()
    return id
  }

  getDraft = (id) => this.#drafts.find((draft) => draft.id === id)

  getDrafts = () => this.#drafts

  discardDraft = (id) => {
    this.#drafts = this.#drafts.filter((draft) => draft.id !== id)
    this.#byId.delete(id)
    this.#changed()
  }

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
    this.#dirtyIds = new Set([
      ...[...this.#byId].filter(([, vm]) => vm.getSnapshot().dirty).map(([id]) => id),
      ...this.#drafts.map((draft) => draft.id),
    ])
    this.#listeners.forEach((listener) => listener())
  }
}

// A property someone else changed or deleted since it was edited here, unless they made the same
// change.
function propertyConflictsFor(vm, state, savedProperties) {
  return Object.entries(state.propertyChanges).flatMap(([id, change]) => {
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
}

// The same for each direction of a link.
function perspectiveConflictsFor(vm, state, savedPerspectives) {
  return Object.entries(state.perspectiveChanges).flatMap(([id, change]) => {
    const perspective = savedPerspectives.get(id)
    const label = `Link ${change.base.name}`
    if (!perspective) {
      return [{ key: id, label, note: 'was deleted by someone else', useTheirs: () => vm.useTheirPerspective(id) }]
    }
    if (samePerspective(change.base, perspective.fields) || samePerspective(change.values, perspective.fields)) return []
    return [
      {
        key: id,
        label,
        note: 'was changed by someone else',
        keepMine: () => vm.keepMyPerspective(id, perspective.fields),
        useTheirs: () => vm.useTheirPerspective(id),
      },
    ]
  })
}

const BLANK_PERSPECTIVE = { name: '', description: '', minCardinality: 0, maxCardinality: null }

// The link editor's starting point, or null when it's closed: a fresh link, a new one already
// added, or both directions of a saved one with any pending edits. Only a new link's target can be
// chosen, from `targets`.
function linkEditorFor(vm, state, itemType, links, targets) {
  if (!state.linkEditor) return null
  const targetName = (id) => targets.find((t) => t.id === id)?.name ?? 'Unknown'
  const addedLink = state.newLinks.find((link) => link.id === state.linkEditor.id)
  if (state.linkEditor.mode === 'create' || addedLink) {
    return {
      isNew: true,
      sourceName: itemType.name,
      targetOptions: targets.map((t) => ({ value: t.id, label: t.kind === 'trait' ? `${t.name} (trait)` : t.name })),
      targetId: addedLink?.targetId ?? null,
      targetName,
      forward: addedLink?.forward ?? BLANK_PERSPECTIVE,
      reverse: addedLink?.reverse ?? BLANK_PERSPECTIVE,
      apply: (forward, reverse, targetId) =>
        addedLink ? vm.updateNewLink(addedLink.id, targetId, forward, reverse) : vm.addLink(targetId, forward, reverse),
      cancel: vm.closeLinkEditor,
    }
  }
  const editedLink = links.find((link) => link.id === state.linkEditor.id && !link.inherited)
  if (!editedLink) return null
  const shownFields = (perspective) => ({ ...perspectiveFields(perspective), ...state.perspectiveChanges[perspective.id]?.values })
  const directions = [editedLink, editedLink.reverse].filter(Boolean)
  return {
    isNew: false,
    sourceName: itemType.name,
    targetName: () => editedLink.target?.name ?? 'Unknown',
    forward: shownFields(editedLink),
    reverse: editedLink.reverse && shownFields(editedLink.reverse),
    apply: (forward, reverse) =>
      vm.setPerspectives(
        directions.map((perspective, i) => ({
          id: perspective.id,
          values: i === 0 ? forward : reverse,
          savedValues: perspectiveFields(perspective),
        })),
      ),
    cancel: vm.closeLinkEditor,
  }
}

const BLANK_PROPERTY = { name: '', description: '', type: 'STRING', cardinality: 'SINGLE', usage: 'OPTIONAL', controlledListId: null }

// The property editor's starting point, or null when it's closed: a fresh property, a new one
// already added, or a saved one with any pending edits. Only a property not yet saved can have its
// type chosen.
function propertyEditorFor(vm, state, savedProperties) {
  const editor = state.propertyEditor
  if (!editor) return null
  if (editor.mode === 'create') {
    return { isNew: true, property: BLANK_PROPERTY, apply: (values) => vm.addProperty(editor.parentId, values) }
  }
  const added = state.newProperties.find((p) => p.id === editor.id)
  if (added) return { isNew: true, property: added.values, apply: (values) => vm.updateNewProperty(added.id, values) }
  const property = savedProperties.get(editor.id)
  if (!property) return null
  return {
    isNew: false,
    property: { ...property, ...propertyFields(property), ...state.propertyChanges[property.id]?.values },
    apply: (values) => vm.setProperty(property.id, values, propertyFields(property)),
  }
}

// The group editor's starting point, or null when it's closed: a fresh group, a new one already
// added, or a saved one with any pending edits.
function groupEditorFor(vm, state, savedGroups) {
  const editor = state.groupEditor
  if (!editor) return null
  if (editor.mode === 'create') {
    return { isNew: true, group: { name: '', description: '' }, apply: (values) => vm.addGroup(editor.parentId, values) }
  }
  const added = state.newGroups.find((g) => g.id === editor.id)
  if (added) return { isNew: true, group: added.values, apply: (values) => vm.updateNewGroup(added.id, values) }
  const group = savedGroups.get(editor.id)
  if (!group) return null
  return {
    isNew: false,
    group: { ...groupFields(group), ...state.groupChanges[group.id] },
    apply: (values) => vm.setGroup(group.id, values, groupFields(group)),
  }
}

// The item type as it should currently be shown: saved data from the schema models with this item
// type's pending edits laid over it, plus everything its views need to make more.
// A draft as the saved item type it will become, with nothing set yet but its name.
const draftItemType = (draft) => ({
  id: draft.id,
  name: draft.name,
  description: '',
  abstractType: false,
  supertype: null,
  traits: [],
  displayLabelPattern: '',
})

// `navigation` ({ selectItemType, closeItemType }) moves on once a draft is saved (to the item type
// it became) or discarded.
export function useItemTypeViewModel(itemTypeViewModels, itemTypeId, navigation) {
  const vm = itemTypeViewModels.get(itemTypeId)
  const state = useSyncExternalStore(itemTypeViewModels.subscribe, vm.getSnapshot)
  const { getItemType, getItemTypes, getSupertypeCandidates, getItemTypePropertyTree } = useItemTypeModel()
  const { getTraits } = useTraitModel()
  const { getItemTypeLinkDetails, getLinkPropertyTree } = useLinkModel()
  const { getControlledLists } = useControlledListModel()

  const draft = itemTypeViewModels.getDraft(itemTypeId)
  const itemType = draft ? draftItemType(draft) : getItemType(itemTypeId)
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

  // A draft has nothing saved -- whatever it inherits from a parent type or traits shows once it's
  // saved.
  const savedTree = draft ? { properties: [], groups: [] } : getItemTypePropertyTree(itemType.id)
  const links = draft ? [] : sortByName(getItemTypeLinkDetails(itemTypeId))
  // The property trees of the links this item type declares itself, which it edits along with its
  // own. Something new at a link's top level has the link's id as its parentId.
  const linkTrees = links.filter((link) => !link.inherited).map((link) => ({ linkId: link.linkId, tree: getLinkPropertyTree(link.linkId) }))
  const savedProperties = ownProperties(savedTree)
  const savedGroups = ownGroups(savedTree)
  for (const { tree } of linkTrees) {
    ownProperties(tree, savedProperties)
    ownGroups(tree, savedGroups)
  }
  // Both directions of every link this item type declares itself, by perspective id -- the reverse
  // direction belongs to the target, but is edited from here along with its link.
  const savedPerspectives = new Map()
  for (const link of links.filter((l) => !l.inherited)) {
    savedPerspectives.set(link.id, { linkId: link.linkId, fields: perspectiveFields(link) })
    if (link.reverse) savedPerspectives.set(link.reverse.id, { linkId: link.linkId, fields: perspectiveFields(link.reverse) })
  }
  // A property tree as it should be shown, pending edits and additions included.
  const shownTree = (tree, parentId) =>
    withNewNodes(withPendingEdits(tree, state.propertyChanges, state.groupChanges), parentId, state.newGroups, state.newProperties)

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
  const propertyConflicts = propertyConflictsFor(vm, state, savedProperties)
  const perspectiveConflicts = perspectiveConflictsFor(vm, state, savedPerspectives)

  // Where a link can go: any item type, this one included, or a trait, linking to every item type
  // that implements it.
  const targets = [
    ...sortByName(getItemTypes()).map((it) => ({ id: it.id, name: it.name, kind: 'item' })),
    ...sortByName(getTraits()).map((trait) => ({ id: trait.id, name: trait.name, kind: 'trait' })),
  ]
  const targetNamed = (id) => targets.find((t) => t.id === id)
  const linkEditor = linkEditorFor(vm, state, itemType, links, targets)
  const propertyEditor = propertyEditorFor(vm, state, savedProperties)
  const groupEditor = groupEditorFor(vm, state, savedGroups)
  const controlledLists = getControlledLists()

  return {
    itemType,
    editing: state.editing,
    dirty: state.dirty,
    saving: state.saving,
    saveError: state.saveError,
    conflicts: [...fieldConflicts, ...propertyConflicts, ...perspectiveConflicts],
    startEditing: vm.startEditing,
    isDraft: Boolean(draft),
    cancel: draft
      ? () => {
          navigation.closeItemType()
          itemTypeViewModels.discardDraft(itemTypeId)
        }
      : vm.cancel,
    save: draft
      ? async () => {
          if (!(await vm.save(createMutations(itemTypeId, fields, state)))) return
          const created = getRawSchema()?.items.find((item) => item.name === fields.name)
          if (created) navigation.selectItemType(created.id)
          else navigation.closeItemType()
          itemTypeViewModels.discardDraft(itemTypeId)
        }
      : () => vm.save(toMutations(itemType.id, saved, fields, state, savedTree, linkTrees, savedPerspectives)),
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
    propertyTree: shownTree(savedTree, null),
    propertyDeletion: {
      isMarked: (id) => state.deletedPropertyIds.has(id) || state.deletedGroupIds.has(id),
      onToggle: (id) => (savedGroups.has(id) ? vm.toggleDeletedGroup(id) : vm.toggleDeleted(id)),
    },
    editRow: (id) => (savedGroups.has(id) ? vm.openGroupEditor(id) : vm.openEditor(id)),
    // New properties and groups, at the top level (no argument) or inside a group (its id).
    newProperty: vm.openNewPropertyEditor,
    newGroup: vm.openNewGroupEditor,
    // The editors while they're open, or null.
    propertyEditor: propertyEditor && { ...propertyEditor, controlledLists, cancel: vm.closePropertyEditor },
    groupEditor: groupEditor && { ...groupEditor, cancel: vm.closeGroupEditor },
    // Each with its property tree. The same propertyDeletion, editRow, newProperty, and newGroup work
    // on a link's properties as on the item type's own.
    links: sortByName([
      ...links.map((link) => ({
        ...withPerspectiveChange(link, state.perspectiveChanges[link.id]),
        reverse: link.reverse && withPerspectiveChange(link.reverse, state.perspectiveChanges[link.reverse.id]),
        propertyTree: shownTree(getLinkPropertyTree(link.linkId), link.linkId),
      })),
      ...state.newLinks.map((link) => ({
        ...newLinkDetails(link, targetNamed(link.targetId)),
        propertyTree: shownTree({ properties: [], groups: [] }, link.id),
      })),
    ]),
    editLink: vm.openLinkEditor,
    newLink: vm.openNewLinkEditor,
    linkEditor,
    linkDeletion: { isMarked: (id) => state.deletedLinkIds.has(id), onToggle: vm.toggleDeletedLink },
  }
}
