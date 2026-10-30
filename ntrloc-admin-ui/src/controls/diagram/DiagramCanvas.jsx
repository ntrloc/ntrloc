import './DiagramCanvas.css'

// The surface a diagram is drawn on: an SVG showing the part of the diagram `canvas` (from
// useDiagramView) says, in diagram units. A read-only canvas sizes its own height to the diagram;
// an interactive one fills its container. `overlay` is plain HTML over the canvas that doesn't
// move or scale with it. onBackgroundPress fires on a press on the canvas itself, not on anything
// drawn on it that handles its own presses -- e.g. to clear a selection.
function DiagramCanvas({ canvas, label, className, overlay, onBackgroundPress, children }) {
  const { containerRef, view, height, interactive, onBackgroundPointerDown } = canvas
  const classes = ['diagram-canvas', interactive && 'diagram-canvas-interactive', className].filter(Boolean).join(' ')
  return (
    <div
      ref={containerRef}
      className={classes}
      style={interactive ? undefined : { height }}
      onPointerDown={(event) => {
        onBackgroundPress?.(event)
        onBackgroundPointerDown(event)
      }}
    >
      {view && (
        <svg
          className="diagram-canvas-svg"
          viewBox={`${view.x} ${view.y} ${view.width / view.zoom} ${view.height / view.zoom}`}
          role="img"
          aria-label={label}
        >
          {children}
        </svg>
      )}
      {overlay}
    </div>
  )
}

export default DiagramCanvas
