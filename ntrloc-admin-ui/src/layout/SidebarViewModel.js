import { useState } from 'react'

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

  return {
    locked,
    collapsed: !locked,
    floating,
    toggleLocked,
    onMouseEnter: () => setHovering(true),
    onMouseLeave: () => setHovering(false),
  }
}
