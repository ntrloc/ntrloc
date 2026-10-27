import FieldLabel from '../field-label/FieldLabel'
import './Switch.css'

// An on/off toggle -- role="switch" + aria-checked, not a disguised checkbox input, so its state is
// exposed to assistive tech as a toggle. data-on (not a className) drives the pure-CSS state, the
// same boolean-attribute convention as CollapsiblePanel's data-collapsed. `name`, when given, is a
// visible FieldLabel above the switch, the same as Input's; without it, `label` alone names it for
// assistive tech. Not `editable`, it still shows its state but ignores clicks -- unlike `disabled`,
// it isn't greyed out, since there's nothing wrong with the value, it just isn't open for editing.
function Switch({ name, on, onChange, label = name, editable = true, disabled = false }) {
  const toggle = (
    <button
      type="button"
      role="switch"
      aria-checked={on}
      aria-label={label}
      className="switch"
      data-on={on || undefined}
      data-readonly={editable ? undefined : true}
      aria-readonly={!editable}
      disabled={disabled}
      onClick={() => editable && onChange(!on)}
    >
      <span className="switch-thumb" />
    </button>
  )
  if (!name) return toggle
  return (
    <switch-control>
      <FieldLabel>{name}</FieldLabel>
      {toggle}
    </switch-control>
  )
}

export default Switch
