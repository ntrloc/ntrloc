import './ChevronIcon.css'

// Shared by any collapse/expand toggle -- points right collapsed, rotates to point down expanded.
function ChevronIcon({ expanded }) {
  return (
    <svg
      aria-hidden="true"
      className="chevron-icon"
      data-expanded={expanded || undefined}
      viewBox="0 0 24 24"
      width="14"
      height="14"
      fill="none"
      stroke="currentColor"
      strokeWidth="2"
      strokeLinecap="round"
      strokeLinejoin="round"
    >
      <polyline points="9 18 15 12 9 6" />
    </svg>
  )
}

export default ChevronIcon
