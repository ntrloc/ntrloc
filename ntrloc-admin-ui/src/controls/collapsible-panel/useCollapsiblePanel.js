import { useState } from 'react'

// Three states: locked (default -- full width, pushes content), collapsed (narrow rail, frees up
// horizontal space) and floating (collapsed's rail hovered -- temporarily widens back out as an
// overlay, without reflowing content, so the user can glance at it without committing to
// re-locking). Shared by the nav Sidebar and any other CollapsiblePanel usage (see that
// component's own comment) -- extracted from SidebarViewModel, which now composes this plus its
// own nav-specific concerns (schemaPath/isSchemaActive) rather than owning the state machine
// itself.
export function useCollapsiblePanel(initialLocked = true) {
  const [locked, setLocked] = useState(initialLocked)
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
