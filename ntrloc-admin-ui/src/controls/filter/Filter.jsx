import './Filter.css'

function ClearIcon() {
  return (
    <svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
      <circle cx="12" cy="12" r="9" />
      <line x1="9.5" y1="9.5" x2="14.5" y2="14.5" />
      <line x1="14.5" y1="9.5" x2="9.5" y2="14.5" />
    </svg>
  )
}

// A free-text filter box -- plain controlled input plus a clear button, generic enough to reuse
// anywhere a list needs a "type to narrow it down" control (matching is each consumer's own
// concern, same as this control not knowing what it's filtering).
function Filter({ value, onChange, placeholder, name }) {
  return (
    <filter-view>
      <input
        type="text"
        name={name}
        className="filter-input"
        value={value}
        onChange={(event) => onChange(event.target.value)}
        placeholder={placeholder}
      />
      {value && (
        <button type="button" className="filter-clear" onClick={() => onChange('')} aria-label="Clear filter">
          <ClearIcon />
        </button>
      )}
    </filter-view>
  )
}

export default Filter
