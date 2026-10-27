const ICONS = {
  plus: <path d="M12 5v14M5 12h14" />,
  trash: (
    <>
      <polyline points="3 6 5 6 21 6" />
      <path d="M19 6l-1 14a2 2 0 0 1-2 2H8a2 2 0 0 1-2-2L5 6" />
      <path d="M10 11v6M14 11v6" />
      <path d="M9 6V4a1 1 0 0 1 1-1h4a1 1 0 0 1 1 1v2" />
    </>
  ),
  undo: (
    <>
      <polyline points="9 14 4 9 9 4" />
      <path d="M4 9h11a5 5 0 0 1 0 10h-3" />
    </>
  ),
}

// The shared set of line icons, drawn in currentColor so they take on whatever text color the
// control around them uses. Decorative only -- the surrounding control carries the accessible name.
function Icon({ name, size = 18 }) {
  return (
    <svg
      aria-hidden="true"
      viewBox="0 0 24 24"
      width={size}
      height={size}
      fill="none"
      stroke="currentColor"
      strokeWidth="2"
      strokeLinecap="round"
      strokeLinejoin="round"
    >
      {ICONS[name]}
    </svg>
  )
}

export default Icon
