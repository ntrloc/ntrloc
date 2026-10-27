import './FieldLabel.css'

// The small heading every form control shows above its value, so Input, Switch and PopoverButton
// all label themselves the same way. Keeps Input's original input-control-name class, which the
// Selenium page objects (and SamlKeysView's own PEM fields) still select on.
function FieldLabel({ children }) {
  return <span className="input-control-name">{children}</span>
}

export default FieldLabel
