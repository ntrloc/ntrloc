import { useEffect, useLayoutEffect, useRef, useState } from 'react'
import { startDiagramDrag } from './startDiagramDrag'

const MIN_ZOOM = 0.3
const MAX_ZOOM = 3
// A read-only diagram is never drawn larger than life, only shrunk to fit.
const MAX_FIT_ZOOM = 1

function fit(bounds, size, maxZoom) {
  const zoom = Math.max(MIN_ZOOM, Math.min(size.width / bounds.width, size.height / bounds.height, maxZoom))
  return {
    x: bounds.minX + bounds.width / 2 - size.width / 2 / zoom,
    y: bounds.minY + bounds.height / 2 - size.height / 2 / zoom,
    zoom,
  }
}

// Which part of a diagram a DiagramCanvas shows. `bounds` ({ minX, minY, width, height }, in diagram
// units) is everything drawn.
//
// interactive: the canvas pans (drag the background, or scroll) and zooms (ctrl/cmd + scroll),
// starting fitted to the bounds. Otherwise it always shows the whole diagram, never larger than life,
// and doesn't take the wheel -- so a page of them still scrolls -- and `height` is the height that
// fits the diagram at the canvas's width.
export function useDiagramView(bounds, { interactive = false } = {}) {
  const containerRef = useRef(null)
  const [size, setSize] = useState(null)
  const [panned, setPanned] = useState(null)
  const boundsRef = useRef(bounds)

  // An interactive canvas is fitted to the diagram when it's first measured, then stays where it's
  // put -- not refitted as the diagram changes under a drag.
  useLayoutEffect(() => {
    const el = containerRef.current
    if (!el) return
    const measure = () => {
      const measured = { width: el.clientWidth, height: el.clientHeight }
      setSize(measured)
      if (interactive) setPanned((prev) => prev ?? fit(boundsRef.current, measured, MAX_FIT_ZOOM))
    }
    measure()
    const observer = new ResizeObserver(measure)
    observer.observe(el)
    return () => observer.disconnect()
  }, [interactive])

  let view = null
  let height = bounds.height
  if (size && !interactive) {
    const zoom = Math.min(MAX_FIT_ZOOM, size.width / bounds.width)
    height = bounds.height * zoom
    view = { ...fit(bounds, { width: size.width, height }, MAX_FIT_ZOOM), width: size.width, height }
  } else if (size && panned) {
    view = { ...panned, ...size }
  }

  // For the listeners, which outlive any one render.
  const viewRef = useRef(view)
  useLayoutEffect(() => {
    viewRef.current = view
    boundsRef.current = bounds
  })

  useEffect(() => {
    const el = containerRef.current
    if (!el || !interactive) return
    function handleWheel(event) {
      const current = viewRef.current
      if (!current) return
      event.preventDefault()
      if (event.ctrlKey || event.metaKey) {
        const rect = el.getBoundingClientRect()
        const offsetX = event.clientX - rect.left
        const offsetY = event.clientY - rect.top
        const zoom = Math.min(MAX_ZOOM, Math.max(MIN_ZOOM, current.zoom * Math.exp(-event.deltaY * 0.002)))
        setPanned({
          x: current.x + offsetX / current.zoom - offsetX / zoom,
          y: current.y + offsetY / current.zoom - offsetY / zoom,
          zoom,
        })
      } else {
        setPanned({ x: current.x + event.deltaX / current.zoom, y: current.y + event.deltaY / current.zoom, zoom: current.zoom })
      }
    }
    el.addEventListener('wheel', handleWheel, { passive: false })
    return () => el.removeEventListener('wheel', handleWheel)
  }, [interactive])

  function onBackgroundPointerDown(event) {
    if (!interactive || !view) return
    const start = view
    startDiagramDrag(event, start.zoom, (dx, dy) => setPanned({ x: start.x - dx, y: start.y - dy, zoom: start.zoom }))
  }

  return { containerRef, view, height, interactive, onBackgroundPointerDown }
}
