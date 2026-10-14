// A static downward chevron -- the "this is a dropdown" affordance shared by every select-style
// trigger (Select's own native <select>, PopoverButton, TreeSelect) so they all render one
// visually identical indicator, regardless of whether the control underneath is a native <select>
// (whose own arrow otherwise varies by browser/OS and can't be reused directly) or a custom
// button. Distinct from ChevronIcon in this same folder -- that one is for collapse/expand toggles
// and rotates between states; this one never rotates, it only ever means "there are options below."
function ChevronDownIcon(props) {
  return (
    <svg
      viewBox="0 0 24 24"
      width="14"
      height="14"
      fill="none"
      stroke="currentColor"
      strokeWidth="2"
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden="true"
      {...props}
    >
      <polyline points="6 9 12 15 18 9" />
    </svg>
  )
}

export default ChevronDownIcon
