import { useEffect, useRef, useState } from 'react'
import CheckboxTree from '../checkbox-tree/CheckboxTree'
import './TreeSelect.css'

// A Select-like trigger that reveals a CheckboxTree in a popover on click, the way a native <select>
// hides its own option list until opened -- same controlled-selection contract as CheckboxTree
// itself (nodes/selectedKeys/onChange), just collapsed behind a closed control. Open/closed is this
// control's own internal state, never reported outward; only the selection is controlled from
// outside. `summary` is the closed-state label -- deliberately a caller-supplied prop rather than
// something this control derives itself (e.g. "N selected" vs. a domain-specific "All properties"),
// keeping it as agnostic about what a selection *means* as CheckboxTree already is.
function TreeSelect({ nodes, selectedKeys, onChange, summary, disabled = false }) {
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
    <tree-select ref={containerRef}>
      <button
        type="button"
        className="tree-select-trigger"
        disabled={disabled}
        onClick={() => setOpen((current) => !current)}
        aria-haspopup="true"
        aria-expanded={open}
      >
        <span className="tree-select-trigger-label">{summary}</span>
        <span className="tree-select-trigger-caret" aria-hidden="true">
          ▾
        </span>
      </button>
      {open && (
        <tree-select-popover>
          <CheckboxTree nodes={nodes} selectedKeys={selectedKeys} onChange={onChange} />
        </tree-select-popover>
      )}
    </tree-select>
  )
}

export default TreeSelect
