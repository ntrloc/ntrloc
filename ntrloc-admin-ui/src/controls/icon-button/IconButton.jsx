import Icon from '../icon/Icon'
import './IconButton.css'

// A borderless button showing just an icon -- for row-level actions (delete, undo) where a text
// button would crowd the row. `label` names it for assistive tech and as its tooltip.
function IconButton({ icon, label, onClick, disabled = false }) {
  return (
    <button type="button" className="icon-button" aria-label={label} title={label} onClick={onClick} disabled={disabled}>
      <Icon name={icon} />
    </button>
  )
}

export default IconButton
