import { useEffect, useRef } from 'react'
import { useLocation } from 'react-router-dom'
import { useCollapsiblePanel } from '../controls/collapsible-panel/useCollapsiblePanel'

// Owns the sidebar's own interaction state, kept out of Sidebar.jsx itself for the same
// view/view-model split used elsewhere. The locked/collapsed/floating state machine itself now
// lives in useCollapsiblePanel (shared with any other CollapsiblePanel usage) -- this composes
// that plus the nav-specific bits (schemaPath/isSchemaActive) that don't belong in a generic
// control.
export function useSidebarViewModel() {
  const { locked, collapsed, floating, toggleLocked, onMouseEnter, onMouseLeave } = useCollapsiblePanel(true)

  // The schema editor's own selection (which item type/trait is open) lives in the URL as real
  // sub-routes (/schema/item-types/:id, /schema/traits/:id) so browser back/forward can walk
  // through it -- see SchemaViewModel's own comment. That means the "Schema" nav link can't just
  // point at a hardcoded "/schema": remembering the last /schema/... path visited is what lets
  // switching away to Search and back still restore whatever was selected there.
  const location = useLocation()
  const lastSchemaPathRef = useRef('/schema')
  useEffect(() => {
    if (location.pathname.startsWith('/schema')) {
      lastSchemaPathRef.current = location.pathname
    }
  }, [location.pathname])

  return {
    locked,
    collapsed,
    floating,
    toggleLocked,
    onMouseEnter,
    onMouseLeave,
    schemaPath: lastSchemaPathRef.current,
    isSchemaActive: location.pathname.startsWith('/schema'),
  }
}
