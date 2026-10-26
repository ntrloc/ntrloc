import './Switch.css'

// An on/off toggle -- role="switch" + aria-checked, not a disguised checkbox input, so its state is
// exposed to assistive tech as a toggle. data-on (not a className) drives the pure-CSS state, the
// same boolean-attribute convention as CollapsiblePanel's data-collapsed.
function Switch({ on, onChange, label, disabled = false }) {
  return (
    <button
      type="button"
      role="switch"
      aria-checked={on}
      aria-label={label}
      className="switch"
      data-on={on || undefined}
      disabled={disabled}
      onClick={() => onChange(!on)}
    >
      <span className="switch-thumb" />
    </button>
  )
}

export default Switch
