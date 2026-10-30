const RADIUS = 7

// A small round icon on a diagram -- 'add' (a plus) or 'delete' (a cross) -- centered on (x, y).
// It handles its own presses: onPointerDown (e.g. to start a drag) and onClick, without the press
// also reaching what it's drawn on.
function DiagramIconButton({ kind, x, y, label, onPointerDown, onClick }) {
  const r = RADIUS
  const arm = r * 0.5
  return (
    <g
      className={`diagram-icon-button diagram-icon-${kind}`}
      role="button"
      aria-label={label}
      onPointerDown={(event) => {
        event.stopPropagation()
        onPointerDown?.(event)
      }}
      onClick={onClick}
    >
      <title>{label}</title>
      <circle cx={x} cy={y} r={r} />
      {kind === 'add' ? (
        <path d={`M ${x - arm} ${y} H ${x + arm} M ${x} ${y - arm} V ${y + arm}`} />
      ) : (
        <path d={`M ${x - arm * 0.8} ${y - arm * 0.8} L ${x + arm * 0.8} ${y + arm * 0.8} M ${x + arm * 0.8} ${y - arm * 0.8} L ${x - arm * 0.8} ${y + arm * 0.8}`} />
      )}
    </g>
  )
}

export default DiagramIconButton
