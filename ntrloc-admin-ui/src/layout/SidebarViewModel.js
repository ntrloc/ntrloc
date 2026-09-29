import { useEffect, useRef, useState } from 'react'
import { useLocation } from 'react-router-dom'

// Owns the sidebar's locked/collapsed/floating interaction state, kept out of Sidebar.jsx itself
// for the same view/view-model split used elsewhere -- the state and its rules stay readable and
// testable independent of the markup that renders them.
export function useSidebarViewModel() {
  const [locked, setLocked] = useState(true)
  const [hovering, setHovering] = useState(false)
  const floating = !locked && hovering

  function toggleLocked() {
    setLocked((prev) => !prev)
    // Reset hover tracking on every toggle -- otherwise collapsing while the cursor happens to
    // still be over the (now-narrower) rail immediately re-triggers floating, producing a
    // shrink-then-expand flicker right after the click. This makes floating only ever the result
    // of a deliberate hover that starts after the toggle, never a side effect of it.
    setHovering(false)
  }

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
    collapsed: !locked,
    floating,
    toggleLocked,
    onMouseEnter: () => setHovering(true),
    onMouseLeave: () => setHovering(false),
    schemaPath: lastSchemaPathRef.current,
    isSchemaActive: location.pathname.startsWith('/schema'),
  }
}
