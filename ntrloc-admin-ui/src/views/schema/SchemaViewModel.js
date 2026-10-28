import { useEffect, useState, useSyncExternalStore } from 'react'
import { useLocation, useNavigate } from 'react-router-dom'
import { SchemaEditorViewModels } from './SchemaEditing'

const SECTION_PATHS = {
  itemTypes: 'item-types',
  traits: 'traits',
  controlledLists: 'controlled-lists',
}

// Guarded against paths outside /schema (e.g. /search) -- this hook keeps running even while the
// Schema view is hidden, since the whole tree stays mounted (see App.jsx's ViewGate) so switching
// back from another top-level view doesn't unmount it; a stray /search or /schema/whatever-unknown
// path should just mean nothing's selected, not garbage parsed out of an unrelated URL.
function parseSchemaLocation(pathname) {
  if (!pathname.startsWith('/schema')) {
    return { activeSection: 'itemTypes', selectedItemTypeId: null, selectedTraitId: null }
  }
  const [segment, id] = pathname.slice('/schema'.length).replace(/^\//, '').split('/')
  if (segment === 'traits') return { activeSection: 'traits', selectedItemTypeId: null, selectedTraitId: id || null }
  if (segment === 'controlled-lists') {
    return { activeSection: 'controlledLists', selectedItemTypeId: null, selectedTraitId: null }
  }
  return { activeSection: 'itemTypes', selectedItemTypeId: id || null, selectedTraitId: null }
}

// Selection lives in the URL, not plain component state -- every selection (an item type, a trait,
// eventually a controlled list) is a real sub-route (/schema/item-types/:id, /schema/traits/:id),
// pushed onto browser history like any other navigation, so the back/forward buttons walk through
// exactly what was viewed -- including cross-references like a property group's "(via <trait>)"
// link, which is just selectTrait like any other trait selection. Sidebar's own "Schema" nav link
// remembers the last /schema/... path visited (see useSidebarViewModel), so switching away to
// another top-level view and back still restores whatever was selected here.
export function useSchemaViewModel() {
  const location = useLocation()
  const navigate = useNavigate()
  // The item type detail view's active tab -- deliberately NOT part of the URL (unlike selection
  // above): just lifted up here instead of TabView's own internal state, so it survives the detail
  // view unmounting when the accordion switches to a different section and back.
  const [itemTypeTabKey, setItemTypeTabKey] = useState('structure')
  // Keys of the collapsed cards -- an item type's Structure cards and a trait's (prefixed "trait-")
  // -- and of links and link properties toggled from how they start (see ItemTypeLinksView).
  const [collapsedStructureCards, setCollapsedStructureCards] = useState(() => new Set())
  // Every item type's pending edits, kept here -- this view stays mounted (App.jsx's ViewGate) even
  // while another top-level view is showing, so edits survive browsing other item types, traits,
  // and other screens.
  const [itemTypeViewModels] = useState(() => new SchemaEditorViewModels('new-item-type-'))
  const dirtyItemTypeIds = useSyncExternalStore(itemTypeViewModels.subscribe, itemTypeViewModels.getDirtyIds)
  const draftItemTypes = useSyncExternalStore(itemTypeViewModels.subscribe, itemTypeViewModels.getDrafts)
  // Every trait's pending edits, kept the same way.
  const [traitViewModels] = useState(() => new SchemaEditorViewModels('new-trait-'))
  const dirtyTraitIds = useSyncExternalStore(traitViewModels.subscribe, traitViewModels.getDirtyIds)
  const draftTraits = useSyncExternalStore(traitViewModels.subscribe, traitViewModels.getDrafts)
  const hasUnsavedChanges = dirtyItemTypeIds.size > 0 || dirtyTraitIds.size > 0

  // A reload, closing the tab or signing out is the one way to lose those edits, so ask first. Browsers
  // show their own generic "Leave site?" wording; the message can't be customized.
  useEffect(() => {
    if (!hasUnsavedChanges) return
    const warn = (event) => event.preventDefault()
    window.addEventListener('beforeunload', warn)
    return () => window.removeEventListener('beforeunload', warn)
  }, [hasUnsavedChanges])

  const { activeSection, selectedItemTypeId, selectedTraitId } = parseSchemaLocation(location.pathname)

  function toggleStructureCard(key) {
    setCollapsedStructureCards((prev) => {
      const next = new Set(prev)
      if (!next.delete(key)) next.add(key)
      return next
    })
  }

  function setActiveSection(section) {
    if (section === activeSection) return
    void navigate(`/schema/${SECTION_PATHS[section]}`)
  }

  function selectItemType(itemTypeId) {
    void navigate(`/schema/item-types/${itemTypeId}`)
  }

  // A new item type starts as a draft, opened for editing; nothing is saved until its Save.
  function createItemType(name) {
    selectItemType(itemTypeViewModels.createDraft(name))
  }

  // Leaving a draft once it's saved or discarded. Replacing its history entry keeps Back from
  // returning to a draft that no longer exists.
  const draftNavigation = {
    selectItemType: (itemTypeId) => void navigate(`/schema/item-types/${itemTypeId}`, { replace: true }),
    closeItemType: () => void navigate('/schema/item-types', { replace: true }),
  }

  function selectTrait(traitId) {
    void navigate(`/schema/traits/${traitId}`)
  }

  // A new trait starts as a draft too, as a new item type does.
  function createTrait(name) {
    selectTrait(traitViewModels.createDraft(name))
  }

  const traitDraftNavigation = {
    selectTrait: (traitId) => void navigate(`/schema/traits/${traitId}`, { replace: true }),
    closeTrait: () => void navigate('/schema/traits', { replace: true }),
  }

  return {
    activeSection,
    setActiveSection,
    selectedItemTypeId,
    selectItemType,
    createItemType,
    draftItemTypes,
    draftNavigation,
    selectedTraitId,
    selectTrait,
    createTrait,
    draftTraits,
    traitDraftNavigation,
    itemTypeTabKey,
    setItemTypeTabKey,
    collapsedStructureCards,
    toggleStructureCard,
    itemTypeViewModels,
    dirtyItemTypeIds,
    traitViewModels,
    dirtyTraitIds,
  }
}
