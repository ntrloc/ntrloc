// Starts a drag from a pointerdown: onMove gets the distance moved so far in diagram units (screen
// pixels over `zoom`), onEnd fires on release. Listens on the window, so the drag carries on outside
// whatever was grabbed. Stops the event, so a drag on a box doesn't also pan the canvas under it.
export function startDiagramDrag(event, zoom, onMove, onEnd) {
  if (event.button !== 0) return
  event.preventDefault()
  event.stopPropagation()
  const startX = event.clientX
  const startY = event.clientY

  function move(e) {
    onMove((e.clientX - startX) / zoom, (e.clientY - startY) / zoom)
  }
  function end() {
    window.removeEventListener('pointermove', move)
    window.removeEventListener('pointerup', end)
    window.removeEventListener('pointercancel', end)
    onEnd?.()
  }
  window.addEventListener('pointermove', move)
  window.addEventListener('pointerup', end)
  window.addEventListener('pointercancel', end)
}
