import Icon from '../icon/Icon'
import './IconButton.css'

// A borderless button showing just an icon -- for row-level actions (delete, undo) where a text
// button would crowd the row. `label` names it for assistive tech and as its tooltip. `variant`
// "secondary" draws it in the secondary color rather than the text color.
function IconButton({ icon, label, onClick, disabled = false, variant }) {
  const className = variant ? `icon-button icon-button-${variant}` : 'icon-button'
  return (
    <button type="button" className={className} aria-label={label} title={label} onClick={onClick} disabled={disabled}>
      <Icon name={icon} />
    </button>
  )
}

export default IconButton
