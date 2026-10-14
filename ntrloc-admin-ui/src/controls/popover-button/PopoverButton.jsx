import { useEffect, useRef, useState } from 'react'
import ChevronDownIcon from '../chevron-icon/ChevronDownIcon'
import './PopoverButton.css'

// A Select-like trigger that reveals arbitrary content in a popover on click -- the generic form
// of TreeSelect's own trigger+popover shell (see that control), extracted so other controls (Sort,
// Filter, item type, ...) that need "a compact closed button that opens a panel of its own
// controls" don't each reimplement the same open/close/outside-click logic. Open/closed is this
// control's own internal state, never reported outward -- whatever's inside the popover stays
// fully controlled from outside, same as TreeSelect's own CheckboxTree content is. `children` may
// be a plain node (Sort/Filter's own multi-field panels, which should stay open until an outside
// click since there's more than one thing to set) or a `({ close }) => node` render function, for
// content like a single-select OptionList that should close itself immediately on pick -- the way
// a native <select> does -- without this control needing to know anything about what "a pick" means.
function PopoverButton({ summary, disabled = false, children }) {
  const [open, setOpen] = useState(false)
  const containerRef = useRef(null)

  useEffect(() => {
    if (!open) return
    function handlePointerDown(e) {
      if (containerRef.current && !containerRef.current.contains(e.target)) {
        setOpen(false)
      }
    }
    document.addEventListener('mousedown', handlePointerDown)
    return () => document.removeEventListener('mousedown', handlePointerDown)
  }, [open])

  return (
    <popover-button ref={containerRef}>
      <button
        type="button"
        className="popover-button-trigger"
        disabled={disabled}
        onClick={() => setOpen((current) => !current)}
        aria-haspopup="true"
        aria-expanded={open}
      >
        <span className="popover-button-trigger-label">{summary}</span>
        <ChevronDownIcon className="popover-button-trigger-caret" />
      </button>
      {open && (
        <popover-button-popover>
          {typeof children === 'function' ? children({ close: () => setOpen(false) }) : children}
        </popover-button-popover>
      )}
    </popover-button>
  )
}

export default PopoverButton
