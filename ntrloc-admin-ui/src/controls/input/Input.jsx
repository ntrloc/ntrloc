import FieldLabel from '../field-label/FieldLabel'
import './Input.css'

// A labeled scalar control: `name` is always shown as a small heading above the value, `editable`
// switches the value itself between plain text (view mode) and an actual input (edit mode) -- see
// Input.css for why that's an underline, not a boxed field, the one visual difference between the
// two states. `children`, used only while editable, lets a caller swap in something other than the
// default text <input> (e.g. a checkbox for a BOOLEAN property) while still getting the same name
// label and the same plain-text view-mode display for free -- `value` still controls what that
// view-mode text reads even when a caller overrides the editing control itself.
function Input({ name, editable = true, value, onChange, children, ...inputProps }) {
  return (
    <input-control>
      <FieldLabel>{name}</FieldLabel>
      {editable ? (
        children ?? <input className="input-control-field" value={value} onChange={onChange} {...inputProps} />
      ) : (
        <span className="input-control-value">{value}</span>
      )}
    </input-control>
  )
}

export default Input
