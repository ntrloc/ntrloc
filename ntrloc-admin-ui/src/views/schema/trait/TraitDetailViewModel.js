import { useSyncExternalStore } from 'react'
import { useItemTypeModel } from '../../../global/model/ItemTypeModel'
import { useTraitModel } from '../../../global/model/TraitModel'
import { useLinkModel } from '../../../global/model/LinkModel'
import { useControlledListModel } from '../../../global/model/ControlledListModel'
import { getRawSchema } from '../../../global/model/SchemaCache'
import { deleteDefinition, linksDeletedWith, groupCreateSpec, linkCreateMutation, propertyCreateSpec, sortByName, structureEditing } from '../SchemaEditing'

const OVERVIEW_LABELS = { name: 'Trait name', description: 'Description' }

// A new trait, not yet saved, as the batch that creates it: its CREATE_TRAIT, then its new links,
// which refer to it by refId -- its temporary id -- as a new item type's do.
function createMutations(draftId, fields, state) {
  return [
    {
      type: 'CREATE_TRAIT',
      refId: draftId,
      name: fields.name,
      description: fields.description || null,
      properties: state.newProperties.filter((p) => p.parentId === null).map((p) => propertyCreateSpec(p.values)),
      groups: state.newGroups
        .filter((g) => g.parentId === null)
        .map((g) => groupCreateSpec(g, state.newGroups, state.newProperties)),
    },
    ...state.newLinks.map((link) => linkCreateMutation({ itemRefId: draftId }, link, state)),
  ]
}

// A draft as the saved trait it will become, with nothing set yet but its name.
const draftTrait = (draft) => ({ id: draft.id, name: draft.name, description: '', implementingItemTypes: [] })

// The trait's own fields as mutations, to follow its structure's (see contentMutations). As with an
// item type, UPDATE_TRAIT goes out whole after EXPECT_TRAIT, so someone else's change made since
// this editor last saw the trait is reported instead of overwritten.
function overviewMutations(traitId, saved, fields, state) {
  if (Object.keys(state.fieldChanges).length === 0) return []
  return [
    { type: 'EXPECT_TRAIT', id: traitId, name: saved.name, description: saved.description || null },
    { type: 'UPDATE_TRAIT', id: traitId, name: fields.name, description: fields.description || null },
  ]
}

// The trait as it should currently be shown and edited: saved data from the schema models with this
// trait's pending edits laid over it -- the same editing an item type has, apart from the fields only
// an item type has (parent type, traits, display label, abstract). Its pending edits live in
// `traitViewModels` (one per trait, kept by SchemaViewModel), so they survive browsing.
// `navigation` ({ selectTrait, closeTrait }) moves on once a draft is saved or discarded, or once a
// trait is deleted.
export function useTraitDetailViewModel(traitViewModels, traitId, navigation) {
  const vm = traitViewModels.get(traitId)
  const state = useSyncExternalStore(traitViewModels.subscribe, vm.getSnapshot)
  const { getItemTypes } = useItemTypeModel()
  const { getTrait, getTraits, getTraitPropertyTree } = useTraitModel()
  const { getTraitLinkDetails, getLinkPropertyTree } = useLinkModel()
  const { getControlledLists } = useControlledListModel()

  const draft = traitViewModels.getDraft(traitId)
  const trait = draft ? draftTrait(draft) : getTrait(traitId)
  if (!trait) return { trait: null }

  const saved = { name: trait.name, description: trait.description ?? '' }
  const fields = { ...saved }
  for (const [key, change] of Object.entries(state.fieldChanges)) fields[key] = change.value
  const setField = (key) => (value) => vm.setField(key, value, saved[key])

  const structure = structureEditing({
    vm,
    state,
    owner: { kind: 'TRAIT', id: trait.id, name: trait.name },
    savedTree: draft ? { properties: [], groups: [] } : getTraitPropertyTree(trait.id),
    links: draft ? [] : getTraitLinkDetails(trait.id),
    models: { getLinkPropertyTree, getItemTypes, getTraits, getControlledLists },
  })

  // A field someone else saved a new value for since this edit was made, as for an item type.
  const fieldConflicts = Object.entries(state.fieldChanges)
    .filter(([key, change]) => change.base !== saved[key] && change.value !== saved[key])
    .map(([key]) => ({
      key,
      label: OVERVIEW_LABELS[key],
      theirs: saved[key] || '(blank)',
      keepMine: () => vm.keepMine(key, saved[key]),
      useTheirs: () => vm.useTheirs(key),
    }))

  return {
    trait,
    editing: state.editing,
    dirty: state.dirty,
    saving: state.saving,
    saveError: state.saveError,
    conflicts: [...fieldConflicts, ...structure.conflicts],
    startEditing: vm.startEditing,
    isDraft: Boolean(draft),
    noun: 'trait',
    deletion: draft
      ? null
      : {
          consequence: linksDeletedWith(structure.view.links),
          confirm: async () => {
            await deleteDefinition({ type: 'DELETE_TRAIT', id: trait.id })
            navigation.closeTrait()
            traitViewModels.discard(traitId)
          },
        },
    cancel: draft
      ? () => {
          navigation.closeTrait()
          traitViewModels.discard(traitId)
        }
      : vm.cancel,
    save: draft
      ? async () => {
          if (!(await vm.save(createMutations(traitId, fields, state)))) return
          const created = getRawSchema()?.traits.find((saved) => saved.name === fields.name)
          if (created) navigation.selectTrait(created.id)
          else navigation.closeTrait()
          traitViewModels.discard(traitId)
        }
      : () => vm.save([...structure.mutations(), ...overviewMutations(trait.id, saved, fields, state)]),
    overview: {
      fields,
      setName: setField('name'),
      setDescription: setField('description'),
      implementingItemTypes: sortByName(trait.implementingItemTypes),
    },
    ...structure.view,
  }
}
