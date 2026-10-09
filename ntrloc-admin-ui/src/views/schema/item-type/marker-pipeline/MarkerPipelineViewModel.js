import { useEffect, useLayoutEffect, useRef, useState } from 'react'
import { useMarkerModel } from '../../../../global/model/MarkerModel'
import { computeLayout } from './markerPipelineLayout'

const MIN_ZOOM = 0.3
const MAX_ZOOM = 3

// title/markerNames shown on a carrier block's box -- one chip per marker, always a single line
// (see markerPipelineLayout's own comment: the block sizes to fit the name, never the other way
// around). A pipeline's own carrier blocks only ever reference markers scoped to the same item type
// it's bound to (same assumption AccessMarkersView makes), so resolving names via that item type's
// own marker list is enough -- no need to search every marker in the system.
function blockLabel(block, markerNamesById) {
  function markerNames(markerIds) {
    return markerIds.map((id) => markerNamesById.get(id) ?? id)
  }
  switch (block.type) {
    case 'ADD_MARKERS':
      return { title: 'Add', markerNames: markerNames(block.markerIds) }
    case 'REMOVE_MARKERS':
      return { title: 'Remove', markerNames: markerNames(block.markerIds) }
    case 'SET_MARKERS':
      return { title: 'Set', markerNames: markerNames(block.markerIds) }
    case 'FILTER':
      return { title: 'Filter', markerNames: [] }
    default:
      return { title: block.type, markerNames: [] }
  }
}

export function useMarkerPipelineViewModel(pipelineView, itemTypeId) {
  const { getMarkersForItemType } = useMarkerModel()
  const markerNamesById = new Map(getMarkersForItemType(itemTypeId).map((m) => [m.id, m.name]))

  const positions = new Map(Object.entries(pipelineView.layout?.positions ?? {}).map(([id, p]) => [id, p]))
  const blocks = new Map(pipelineView.pipeline.carrierBlocks.map((b) => [b.id, blockLabel(b, markerNamesById)]))
  const layout = computeLayout(pipelineView.pipeline, positions, blocks)

  // Pan/zoom -- an infinite canvas (viewBox window into the diagram's own coordinate space), not
  // native container scrolling. useStateMachineDiagramViewModel's own pan (scrollLeft/scrollTop
  // under a bigger-than-container SVG) only ever has somewhere to go once zoomed in past 1:1, which
  // is fine there since that diagram always starts at a plain 1x. This one starts zoomed to *fit* the
  // display area instead (so it fills the space from the moment it renders, same as before pan/zoom
  // existed at all), meaning right at that starting point there's typically no overflow for native
  // scrolling to pan through -- panning would silently do nothing exactly where someone would first
  // try it. A viewBox window has no such constraint: pan.x/pan.y can move freely in any direction at
  // any zoom, the same "grab the canvas" feel as the legacy admin UI's BPMN/DMN process editor.
  //
  // view: { x, y, zoom, width, height } -- (x, y) is the diagram-space point shown at the
  // container's top-left corner; zoom is container-pixels-per-diagram-unit; width/height are the
  // container's own CSS pixel size as of the last measurement, carried in state (rather than read
  // from the ref during render, which React refs don't support -- a ref mutation alone never
  // triggers a re-render) so the View can build a viewBox from plain state with no ref access of its
  // own. null until the container's actual size is known, so the very first layout effect can set
  // the real fit view before the browser ever paints a frame, rather than visibly snapping from some
  // placeholder to the fit view.
  const [view, setView] = useState(null)
  const containerRef = useRef(null)
  const dragRef = useRef(null)

  // Right-click on a block -- a shell for now (see this component's own comment on what it shows),
  // but already positioned correctly for real content later: x/y are container-relative CSS pixels
  // (from the click event, via the container's own bounding rect), not diagram-space coordinates, so
  // the menu renders as plain HTML outside the SVG's viewBox and never scales with zoom.
  const [contextMenu, setContextMenu] = useState(null)

  function onBlockContextMenu(event, blockId) {
    event.preventDefault()
    const rect = containerRef.current.getBoundingClientRect()
    setContextMenu({ x: event.clientX - rect.left, y: event.clientY - rect.top, blockId })
  }

  function closeContextMenu() {
    setContextMenu(null)
  }

  useLayoutEffect(() => {
    const el = containerRef.current
    if (!el || view !== null) return
    const zoom = Math.min(el.clientWidth / layout.width, el.clientHeight / layout.height, MAX_ZOOM)
    const clampedZoom = Math.max(MIN_ZOOM, zoom)
    // Centers the diagram within the container, the same centering preserveAspectRatio="xMidYMid
    // meet" used to give for free -- the container is usually a little wider or taller than the
    // diagram's own aspect ratio, so one axis has slack to split evenly on both sides.
    const x = layout.width / 2 - el.clientWidth / 2 / clampedZoom
    const y = layout.height / 2 - el.clientHeight / 2 / clampedZoom
    setView({ x, y, zoom: clampedZoom, width: el.clientWidth, height: el.clientHeight })
  }, [layout, view])

  function onPointerDown(event) {
    if (event.button !== 0) return // left button only
    if (!view) return // not measured yet -- nothing meaningful to drag relative to
    // Without this, a real mouse drag starting over any of the diagram's <text> content begins a
    // native text-selection gesture instead of a pan -- harmless with synthetic PointerEvents (which
    // skip the browser's own gesture recognition entirely), only visible with an actual mouse
    // involved. user-select:none on the container in the stylesheet is the second line of defense.
    event.preventDefault()
    dragRef.current = { startX: event.clientX, startY: event.clientY, startView: view }
    event.currentTarget.setPointerCapture(event.pointerId)
  }

  function onPointerMove(event) {
    if (!dragRef.current) return
    const { startX, startY, startView } = dragRef.current
    // Screen-pixel drag delta converted to diagram units via the zoom in effect *when the drag
    // started* -- using the live zoom here instead would make the canvas accelerate away from the
    // cursor the moment a zoom happens mid-drag (it can't currently, since wheel-zoom isn't modified
    // during a drag, but computing it this way costs nothing and removes the assumption entirely).
    setView({
      ...startView,
      x: startView.x - (event.clientX - startX) / startView.zoom,
      y: startView.y - (event.clientY - startY) / startView.zoom,
    })
  }

  function onPointerUp(event) {
    dragRef.current = null
    // Guarded -- a pointerup can reach this handler without this element ever having captured that
    // pointer (e.g. the button-!==-0 early return in onPointerDown above, or a right-click elsewhere
    // that a context menu's own overlay intercepts before it ever bubbles here). Releasing a capture
    // that was never set throws.
    if (event.currentTarget.hasPointerCapture(event.pointerId)) {
      event.currentTarget.releasePointerCapture(event.pointerId)
    }
  }

  // Wheel: plain scroll pans (including a trackpad's two-finger gesture, which the browser reports
  // as deltaX/deltaY on a wheel event), Ctrl/Cmd+wheel zooms (also how a trackpad pinch is reported)
  // anchored so whatever diagram point was under the cursor stays under it. Wired as a native
  // listener (not a React onWheel prop) because React's synthetic wheel handler is passive by
  // default -- calling preventDefault() inside it is silently ignored, which would leave the
  // browser's own page-scroll/zoom fighting this one.
  useEffect(() => {
    const el = containerRef.current
    if (!el) return

    function handleWheel(event) {
      event.preventDefault()
      setView((oldView) => {
        if (!oldView) return oldView
        if (event.ctrlKey || event.metaKey) {
          const rect = el.getBoundingClientRect()
          const offsetX = event.clientX - rect.left
          const offsetY = event.clientY - rect.top
          const contentX = oldView.x + offsetX / oldView.zoom
          const contentY = oldView.y + offsetY / oldView.zoom
          const factor = Math.exp(-event.deltaY * 0.002)
          const newZoom = Math.min(MAX_ZOOM, Math.max(MIN_ZOOM, oldView.zoom * factor))
          return { ...oldView, x: contentX - offsetX / newZoom, y: contentY - offsetY / newZoom, zoom: newZoom }
        }
        return { ...oldView, x: oldView.x + event.deltaX / oldView.zoom, y: oldView.y + event.deltaY / oldView.zoom }
      })
    }

    el.addEventListener('wheel', handleWheel, { passive: false })
    return () => el.removeEventListener('wheel', handleWheel)
  }, [])

  return {
    layout,
    blocks,
    start: pipelineView.pipeline.start,
    end: pipelineView.pipeline.end,
    containerRef,
    // null until measured -- the View renders nothing (not a wrongly-scaled frame) until then.
    // Building the viewBox string itself is left to the View (a one-line pure function of this plain
    // state), rather than returned pre-built here, since there's nothing about it that needs to live
    // in this hook specifically.
    view,
    onPointerDown,
    onPointerMove,
    onPointerUp,
    contextMenu,
    onBlockContextMenu,
    closeContextMenu,
  }
}
