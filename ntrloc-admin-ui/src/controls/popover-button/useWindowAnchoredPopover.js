import { useLayoutEffect, useRef } from 'react'

const GAP = 4
const VIEWPORT_MARGIN = 8

// Places an open popover against the window (it's styled position: fixed), beside its trigger, so a
// scrolling ancestor -- a card list, a dialog body -- can't clip it: below the trigger, or above it
// when there's more room there, scrolling itself if it fits neither way. It follows the trigger as
// any ancestor scrolls or the window resizes. Returns the refs to put on the trigger and popover.
export function useWindowAnchoredPopover(open) {
  const triggerRef = useRef(null)
  const popoverRef = useRef(null)

  useLayoutEffect(() => {
    if (!open) return
    const place = () => placePopover(triggerRef.current, popoverRef.current)
    place()
    window.addEventListener('resize', place)
    // Capturing, so scrolling any ancestor (not just the window) keeps it beside the trigger.
    window.addEventListener('scroll', place, true)
    return () => {
      window.removeEventListener('resize', place)
      window.removeEventListener('scroll', place, true)
    }
  }, [open])

  return { triggerRef, popoverRef }
}

function placePopover(trigger, popover) {
  if (!trigger || !popover) return
  const rect = trigger.getBoundingClientRect()
  const below = window.innerHeight - rect.bottom - GAP - VIEWPORT_MARGIN
  const above = rect.top - GAP - VIEWPORT_MARGIN
  // Measured at its own size (its stylesheet's max-height still applies), then fitted.
  popover.style.maxHeight = ''
  const height = popover.offsetHeight
  const flip = height > below && above > below
  const room = flip ? above : below
  if (height > room) popover.style.maxHeight = `${Math.max(room, 0)}px`
  const placedHeight = Math.min(height, room)
  popover.style.top = `${flip ? rect.top - GAP - placedHeight : rect.bottom + GAP}px`
  const left = Math.min(rect.left, window.innerWidth - popover.offsetWidth - VIEWPORT_MARGIN)
  popover.style.left = `${Math.max(left, VIEWPORT_MARGIN)}px`
}
