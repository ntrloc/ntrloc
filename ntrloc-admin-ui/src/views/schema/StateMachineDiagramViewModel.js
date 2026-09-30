import { useEffect, useLayoutEffect, useMemo, useRef, useState } from 'react'
import { computeLayout } from './stateMachineLayout'

const MIN_ZOOM = 0.3
const MAX_ZOOM = 3

// Owns this diagram's own ephemeral view state -- zoom level and scroll position, neither of which
// has any counterpart in the schema itself (same reasoning PropertyTreeViewModel's collapsed-group
// state lives there and not in a Model) -- and calls the pure layout engine to turn this machine's
// states/transitions into concrete geometry. Ported from the legacy admin UI's
// ntrloc-state-machine-diagram.js, but drag-to-pan uses Pointer Events + setPointerCapture here
// instead of that version's document-level mousemove/mouseup listener pair: the dragged element
// keeps receiving pointer events even once the cursor leaves it, so no manual listener
// wiring/teardown is needed.
export function useStateMachineDiagramViewModel(machine, orientation = 'horizontal') {
  const [zoom, setZoom] = useState(1)
  const scrollRef = useRef(null)
  const dragRef = useRef(null)
  const pendingScrollRef = useRef(null)

  const layout = useMemo(() => computeLayout(machine.states, orientation), [machine, orientation])

  // Applies a zoom-anchored scroll adjustment (see onWheel below) the instant the DOM has actually
  // resized to the new zoom, before the browser paints -- doing it inside onWheel itself would read
  // the scroll container's still-old scrollWidth/Height, since React hasn't re-rendered the SVG's
  // width/height attributes yet at that point.
  useLayoutEffect(() => {
    if (pendingScrollRef.current && scrollRef.current) {
      scrollRef.current.scrollLeft = pendingScrollRef.current.left
      scrollRef.current.scrollTop = pendingScrollRef.current.top
      pendingScrollRef.current = null
    }
  }, [zoom])

  function onPointerDown(event) {
    if (event.button !== 0) return // left button only
    const scrollEl = scrollRef.current
    dragRef.current = {
      startX: event.clientX,
      startY: event.clientY,
      startScrollLeft: scrollEl.scrollLeft,
      startScrollTop: scrollEl.scrollTop,
    }
    event.currentTarget.setPointerCapture(event.pointerId)
  }

  function onPointerMove(event) {
    if (!dragRef.current) return
    const scrollEl = scrollRef.current
    scrollEl.scrollLeft = dragRef.current.startScrollLeft - (event.clientX - dragRef.current.startX)
    scrollEl.scrollTop = dragRef.current.startScrollTop - (event.clientY - dragRef.current.startY)
  }

  function onPointerUp(event) {
    dragRef.current = null
    event.currentTarget.releasePointerCapture(event.pointerId)
  }

  // Ctrl/Cmd+wheel-to-zoom (also how browsers report a trackpad pinch gesture), anchored so whatever
  // point was under the cursor stays under it. Plain wheel keeps scrolling -- native overflow:auto,
  // untouched by this. Wired as a native listener (not a React onWheel prop) because React's
  // synthetic wheel handler is passive by default -- calling preventDefault() inside it is silently
  // ignored (logs "Unable to preventDefault inside passive event listener invocation"), which would
  // leave the browser's own page-zoom fighting this one. { passive: false } here is what the legacy
  // component's own addEventListener call relied on too.
  useEffect(() => {
    const scrollEl = scrollRef.current
    if (!scrollEl) return

    function handleWheel(event) {
      if (!(event.ctrlKey || event.metaKey)) return
      event.preventDefault()
      const rect = scrollEl.getBoundingClientRect()
      const offsetX = event.clientX - rect.left
      const offsetY = event.clientY - rect.top
      const contentX = scrollEl.scrollLeft + offsetX
      const contentY = scrollEl.scrollTop + offsetY
      const factor = Math.exp(-event.deltaY * 0.002)

      setZoom((oldZoom) => {
        const newZoom = Math.min(MAX_ZOOM, Math.max(MIN_ZOOM, oldZoom * factor))
        const ratio = newZoom / oldZoom
        pendingScrollRef.current = { left: contentX * ratio - offsetX, top: contentY * ratio - offsetY }
        return newZoom
      })
    }

    scrollEl.addEventListener('wheel', handleWheel, { passive: false })
    return () => scrollEl.removeEventListener('wheel', handleWheel)
  }, [])

  return { layout, zoom, scrollRef, onPointerDown, onPointerMove, onPointerUp }
}
