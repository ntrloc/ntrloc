import { useState } from 'react'
import { useLocation, useNavigate } from 'react-router-dom'

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
  // The item type detail view's own active tab -- deliberately NOT part of the URL (unlike
  // selection above): just lifted up here instead of TabView's own internal state, so it survives
  // ItemTypeDetailView unmounting when the accordion switches to a different section and back.
  const [detailTabKey, setDetailTabKey] = useState('overview')

  const { activeSection, selectedItemTypeId, selectedTraitId } = parseSchemaLocation(location.pathname)

  function setActiveSection(section) {
    if (section === activeSection) return
    navigate(`/schema/${SECTION_PATHS[section]}`)
  }

  function selectItemType(itemTypeId) {
    navigate(`/schema/item-types/${itemTypeId}`)
  }

  function selectTrait(traitId) {
    navigate(`/schema/traits/${traitId}`)
  }

  return {
    activeSection,
    setActiveSection,
    selectedItemTypeId,
    selectItemType,
    selectedTraitId,
    selectTrait,
    detailTabKey,
    setDetailTabKey,
  }
}
