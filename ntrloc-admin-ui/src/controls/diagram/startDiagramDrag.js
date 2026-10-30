// Starts a drag from a pointerdown: onMove gets the distance moved so far in diagram units (screen
// pixels over `zoom`), onEnd fires on release, told whether it moved at all. Listens on the window, so
// the drag carries on outside whatever was grabbed. Stops the event, so a drag on a box doesn't also
// pan the canvas under it.
//
// threshold: screen pixels the pointer has to move before it's a drag -- before then onMove isn't
// called, so a press that barely moves is a click (onEnd(false)).
export function startDiagramDrag(event, zoom, onMove, onEnd, { threshold = 0 } = {}) {
  if (event.button !== 0) return
  event.preventDefault()
  event.stopPropagation()
  const startX = event.clientX
  const startY = event.clientY
  let moved = false

  function move(e) {
    if (!moved && Math.hypot(e.clientX - startX, e.clientY - startY) < threshold) return
    moved = true
    onMove((e.clientX - startX) / zoom, (e.clientY - startY) / zoom)
  }
  function end() {
    window.removeEventListener('pointermove', move)
    window.removeEventListener('pointerup', end)
    window.removeEventListener('pointercancel', end)
    onEnd?.(moved)
  }
  window.addEventListener('pointermove', move)
  window.addEventListener('pointerup', end)
  window.addEventListener('pointercancel', end)
}
