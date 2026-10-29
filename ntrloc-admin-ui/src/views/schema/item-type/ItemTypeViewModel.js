import { useSyncExternalStore } from 'react'
import { useItemTypeModel } from '../../../global/model/ItemTypeModel'
import { useTraitModel } from '../../../global/model/TraitModel'
import { useLinkModel } from '../../../global/model/LinkModel'
import { useControlledListModel } from '../../../global/model/ControlledListModel'
import { getRawSchema } from '../../../global/model/SchemaCache'
import { deleteDefinition, linksDeletedWith, groupCreateSpec, linkCreateMutation, propertyCreateSpec, sortByName, structureEditing } from '../SchemaEditing'

const OVERVIEW_LABELS = {
  name: 'Item type name',
  description: 'Description',
  displayLabelPattern: 'Display label',
  abstractType: 'Abstract type',
  supertypeId: 'Parent type',
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


// The item type's own fields and traits as mutations, to follow its structure's (see
// contentMutations). Trait removals go before and additions after, so a name freed up by one can be
// taken by another in the same save. UPDATE_ITEM replaces every Overview field at once, so it's sent
// whole, after EXPECT_ITEM: the server rejects the batch if those fields no longer hold what this
// editor last saw, rather than overwriting someone else's change made since.
function overviewMutations(itemTypeId, saved, fields, state) {
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
// it became) or discarded, or once an item type is deleted.
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
  const structure = structureEditing({
    vm,
    state,
    owner: { kind: 'ITEM', id: itemType.id, name: itemType.name },
    savedTree: draft ? { properties: [], groups: [] } : getItemTypePropertyTree(itemType.id),
    links: draft ? [] : getItemTypeLinkDetails(itemTypeId),
    models: { getLinkPropertyTree, getItemTypes, getTraits, getControlledLists },
  })

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
  return {
    itemType,
    editing: state.editing,
    dirty: state.dirty,
    saving: state.saving,
    saveError: state.saveError,
    conflicts: [...fieldConflicts, ...structure.conflicts],
    startEditing: vm.startEditing,
    isDraft: Boolean(draft),
    noun: 'item type',
    deletion: draft
      ? null
      : {
          consequence: linksDeletedWith(structure.view.links),
          confirm: async () => {
            await deleteDefinition({ type: 'DELETE_ITEM', id: itemType.id })
            navigation.closeItemType()
            itemTypeViewModels.discard(itemTypeId)
          },
        },
    cancel: draft
      ? () => {
          navigation.closeItemType()
          itemTypeViewModels.discard(itemTypeId)
        }
      : vm.cancel,
    save: draft
      ? async () => {
          if (!(await vm.save(createMutations(itemTypeId, fields, state)))) return
          const created = getRawSchema()?.items.find((item) => item.name === fields.name)
          if (created) navigation.selectItemType(created.id)
          else navigation.closeItemType()
          itemTypeViewModels.discard(itemTypeId)
        }
      : () => vm.save([...structure.mutations(), ...overviewMutations(itemType.id, saved, fields, state)]),
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
    ...structure.view,
  }
}
