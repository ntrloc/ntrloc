import './OptionList.css'

// A flat single-select list of options -- the non-hierarchical, single-select counterpart to
// CheckboxTree (which is multi-select and recursive). Used inside a PopoverButton wherever a
// custom dropdown needs to stand in for a native <select>: a native <select>'s own popup is
// rendered and positioned by the browser/OS, not by CSS, so it can't be made to consistently open
// below its trigger the way PopoverButton's own popover can (see ItemTypeSelect's own comment).
function OptionList({ options, value, onChange }) {
  return (
    <ul className="option-list" role="listbox">
      {options.map((opt) => (
        <li key={opt.value} role="option" aria-selected={opt.value === value}>
          <button
            type="button"
            className={`option-list-option${opt.value === value ? ' option-list-option-selected' : ''}`}
            onClick={() => onChange(opt.value)}
          >
            {opt.label}
          </button>
        </li>
      ))}
    </ul>
  )
}

export default OptionList
