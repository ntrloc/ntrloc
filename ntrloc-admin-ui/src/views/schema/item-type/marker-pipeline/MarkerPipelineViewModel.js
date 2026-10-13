import { useEffect, useLayoutEffect, useRef, useState } from 'react'
import { useMarkerModel } from '../../../../global/model/MarkerModel'
import { useSchemaSnapshot } from '../../../../global/model/SchemaCache'
import { computeLayout, inlinedConditionGroupFor, signalNodes } from './markerPipelineLayout'
import { describeCondition, describeConditionGroupSubject, describeConditionShort } from './pipelineConditionText'

const MIN_ZOOM = 0.3
const MAX_ZOOM = 3

// title/markerNames shown on a carrier block's box -- one chip per marker, always a single line
// (see markerPipelineLayout's own comment: the block sizes to fit the name, never the other way
// around). A pipeline's own carrier blocks only ever reference markers scoped to the same item type
// it's bound to (same assumption AccessMarkersView makes), so resolving names via that item type's
// own marker list is enough -- no need to search every marker in the system.
//
// ctx: { markerNamesById, pipeline, schema, itemTypeId } -- schema/itemTypeId only matter for
// SELECT's own propertyLabel/conditionLabel text (see pipelineConditionText.js); every other block
// type only ever needs markerNamesById, the same as before condition text existed.
function blockLabel(block, ctx) {
  const { markerNamesById, pipeline, schema, itemTypeId } = ctx
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
      // isFilter: true picks the two-port FilterNode rendering (own "input"/"if" ports) rather
      // than the single shared-strip CarrierBlockNode every other block gets -- see
      // filterNodeDimensions' own comment.
      return { title: 'Filter', isFilter: true }
    case 'SELECT': {
      // A <select> whose rows are each gated by one member of a ConditionGroup it wholly owns
      // (see inlinedConditionGroupFor) shows that group inline -- a "property: X" header plus each
      // row's own branch caption (e.g. "NATURE") -- rather than as a separate standalone signal
      // node purely referenced back in via dashed lines to the very rows that already say the same
      // thing.
      const group = inlinedConditionGroupFor(pipeline, block.id)
      const propertyLabel = group ? describeConditionGroupSubject(schema, itemTypeId, group) : null
      const shortLabelByConditionId = new Map((group?.conditions ?? []).map((c) => [c.id, describeConditionShort(c.predicate)]))
      function conditionLabelFor(gate) {
        const pinId = gate?.conditionOutputPinIds?.[0]
        return pinId ? (shortLabelByConditionId.get(pinId) ?? null) : null
      }
      // rows: one per select.adds[i] (an ordinary AddMarkers, own id, own markers), plus a
      // synthetic trailing row standing in for the block's own primary pin -- the (Otherwise)
      // branch, which adds no markers of its own (see markerPipelineLayout's own comment on
      // label.rows and Select.java's doc comment on why id() doubles as that pin).
      return {
        title: 'Select',
        propertyLabel,
        rows: [
          ...block.adds.map((add) => ({
            id: add.id,
            markerNames: markerNames(add.markerIds),
            otherwise: false,
            conditionLabel: conditionLabelFor(add.gate),
          })),
          { id: block.id, markerNames: [], otherwise: true, conditionLabel: null },
        ],
      }
    }
    default:
      return { title: block.type, markerNames: [] }
  }
}

// A standalone signal node's own text -- a ConditionGroup's shared subject plus each member's own
// short label (same shape blockLabel's SELECT case builds for an *inlined* group, since the content
// is the same either way, just drawn in a different place), or a bare Condition's full description
// (see describeCondition -- stacked lines, or a nested "count" sub-box for a COUNTED predicate).
function signalNodeLabel(schema, itemTypeId, signal) {
  if (signal.type === 'CONDITION_GROUP') {
    return {
      propertyLabel: describeConditionGroupSubject(schema, itemTypeId, signal),
      rowLabels: signal.conditions.map((c) => describeConditionShort(c.predicate)),
    }
  }
  return describeCondition(schema, itemTypeId, signal)
}

export function useMarkerPipelineViewModel(pipelineView, itemTypeId, saveLayout, initialView, onViewChange) {
  const { getMarkersForItemType } = useMarkerModel()
  const markerNamesById = new Map(getMarkersForItemType(itemTypeId).map((m) => [m.id, m.name]))
  // Reaches past the shaped per-concept Models (ItemTypeModel/LinkModel/StateMachineModel) straight
  // to the raw schema snapshot -- condition text needs to resolve ids against *whatever item type a
  // subject chain has stepped onto by then* (e.g. a linked item's own state machine), which none of
  // those per-type-scoped Models can do; see pipelineConditionText.js's own comment.
  const schema = useSchemaSnapshot()

  // savedPositions: whatever's currently persisted (from pipelineView.layout). draftPositions: a
  // working copy an edit session drags around, null outside edit mode -- see startEditing/
  // cancelEditing/saveEditing below. Layout is always computed off whichever of the two is live, so
  // a drag in progress renders exactly like a saved one would, with no separate "preview" code path.
  const savedPositions = new Map(Object.entries(pipelineView.layout?.positions ?? {}).map(([id, p]) => [id, p]))
  const [draftPositions, setDraftPositions] = useState(null)
  const effectivePositions = draftPositions ?? savedPositions

  // savedEdgeBends/draftEdgeBends/effectiveEdgeBends: same pattern as positions above, one admin-
  // adjusted number per edge (see orthogonalPath) instead of one {x,y} per node.
  const savedEdgeBends = new Map(Object.entries(pipelineView.layout?.edgeBends ?? {}))
  const [draftEdgeBends, setDraftEdgeBends] = useState(null)
  const effectiveEdgeBends = draftEdgeBends ?? savedEdgeBends

  const blockCtx = { markerNamesById, pipeline: pipelineView.pipeline, schema, itemTypeId }
  const blocks = new Map(pipelineView.pipeline.carrierBlocks.map((b) => [b.id, blockLabel(b, blockCtx)]))
  const signalDescriptions = new Map(
    signalNodes(pipelineView.pipeline).map((node) => [node.id, signalNodeLabel(schema, itemTypeId, node.signal)]),
  )
  const layout = computeLayout(pipelineView.pipeline, effectivePositions, blocks, signalDescriptions, effectiveEdgeBends)

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
  // own. Seeded from initialView (the caller's own remembered pan/zoom for this item type, if any --
  // see AccessMarkersView's viewByItemTypeIdRef) rather than always starting null, so switching back
  // to a pipeline already visited this session resumes exactly where it was left; the fit-zoom effect
  // below only ever computes a fresh fit when there's truly nothing to resume. Still null on a first
  // visit, so the very first layout effect can set the real fit view before the browser ever paints a
  // frame, rather than visibly snapping from some placeholder to the fit view.
  const [view, setView] = useState(initialView ?? null)
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

  // Edit mode -- owned here rather than as local View state, same reasoning as pan/zoom and the
  // context menu above: it's "how this diagram is currently being interacted with," not something
  // the View has any logic of its own around.
  const [isEditing, setIsEditing] = useState(false)
  const [isSaving, setIsSaving] = useState(false)
  const [saveError, setSaveError] = useState(null)
  const nodeDragRef = useRef(null)

  function startEditing() {
    // A fresh clone, not a reference to savedPositions/savedEdgeBends -- dragging mutates this copy
    // freely without ever touching what's actually persisted until Save.
    setDraftPositions(new Map(savedPositions))
    setDraftEdgeBends(new Map(savedEdgeBends))
    setSaveError(null)
    setIsEditing(true)
  }

  function cancelEditing() {
    setDraftPositions(null)
    setDraftEdgeBends(null)
    setDraggingEdgeKey(null)
    setSaveError(null)
    setIsEditing(false)
  }

  async function saveEditing() {
    setIsSaving(true)
    setSaveError(null)
    try {
      await saveLayout({
        positions: Object.fromEntries(draftPositions ?? savedPositions),
        edgeBends: Object.fromEntries(draftEdgeBends ?? savedEdgeBends),
      })
      setDraftPositions(null)
      setDraftEdgeBends(null)
      setDraggingEdgeKey(null)
      setIsEditing(false)
    } catch (error) {
      setSaveError(error.message || 'Failed to save pipeline layout.')
    } finally {
      setIsSaving(false)
    }
  }

  // Dragging a node (block or pseudostate) while in edit mode -- same pointer-capture mechanics as
  // the canvas's own pan above, just scoped to one node's position instead of the whole view, and
  // stopping propagation so the container's own onPointerDown (pan-start) never also engages for the
  // same gesture (same reasoning as the edit bar's own stopPropagation -- see MarkerPipelineView.jsx).
  function onNodePointerDown(event, nodeId) {
    if (!isEditing) return
    if (event.button !== 0) return
    if (!view) return
    event.preventDefault()
    event.stopPropagation()
    // Falls back to the node's current *rendered* position (effectivePositions might not have an
    // entry yet -- e.g. a node still sitting at its rank-based fallback spot, never dragged before)
    // rather than requiring draftPositions to already know about every node up front.
    const current = effectivePositions.get(nodeId) ?? layout.rects.get(nodeId)
    nodeDragRef.current = { nodeId, startX: event.clientX, startY: event.clientY, startPos: { x: current.x, y: current.y } }
    event.currentTarget.setPointerCapture(event.pointerId)
  }

  function onNodePointerMove(event) {
    if (!nodeDragRef.current) return
    event.stopPropagation()
    const { nodeId, startX, startY, startPos } = nodeDragRef.current
    const dx = (event.clientX - startX) / view.zoom
    const dy = (event.clientY - startY) / view.zoom
    setDraftPositions((prev) => {
      const next = new Map(prev)
      next.set(nodeId, { x: startPos.x + dx, y: startPos.y + dy })
      return next
    })
  }

  function onNodePointerUp(event) {
    nodeDragRef.current = null
    event.stopPropagation()
    // Same guard as the canvas's own onPointerUp -- releasing a capture that was never set throws.
    if (event.currentTarget.hasPointerCapture(event.pointerId)) {
      event.currentTarget.releasePointerCapture(event.pointerId)
    }
  }

  // Dragging an edge's own bend handle -- same pointer-capture mechanics as a node drag above, but
  // constrained to a single axis (see orthogonalPath's own `handle.axis`): a forward edge's bend is
  // its vertical run's own x, a backward one's is its detour lane's own y, so only that one
  // coordinate ever moves, never both.
  const edgeDragRef = useRef(null)
  // Reactive, unlike edgeDragRef -- the View needs to re-render the one edge actually being dragged
  // in its own "active" color (see PipelineEdge/SignalReferenceEdge), which a ref mutation alone
  // never triggers.
  const [draggingEdgeKey, setDraggingEdgeKey] = useState(null)

  function onEdgeHandlePointerDown(event, edgeKey, handle) {
    if (!isEditing) return
    if (event.button !== 0) return
    if (!view) return
    event.preventDefault()
    event.stopPropagation()
    const current = effectiveEdgeBends.get(edgeKey) ?? (handle.axis === 'x' ? handle.x : handle.y)
    edgeDragRef.current = { edgeKey, axis: handle.axis, startX: event.clientX, startY: event.clientY, startValue: current }
    setDraggingEdgeKey(edgeKey)
    event.currentTarget.setPointerCapture(event.pointerId)
  }

  function onEdgeHandlePointerMove(event) {
    if (!edgeDragRef.current) return
    event.stopPropagation()
    const { edgeKey, axis, startX, startY, startValue } = edgeDragRef.current
    const delta = axis === 'x' ? (event.clientX - startX) / view.zoom : (event.clientY - startY) / view.zoom
    setDraftEdgeBends((prev) => {
      const next = new Map(prev)
      next.set(edgeKey, startValue + delta)
      return next
    })
  }

  function onEdgeHandlePointerUp(event) {
    edgeDragRef.current = null
    setDraggingEdgeKey(null)
    event.stopPropagation()
    if (event.currentTarget.hasPointerCapture(event.pointerId)) {
      event.currentTarget.releasePointerCapture(event.pointerId)
    }
  }

  useLayoutEffect(() => {
    const el = containerRef.current
    if (!el || view !== null) return
    const zoom = Math.min(el.clientWidth / layout.width, el.clientHeight / layout.height, MAX_ZOOM)
    const clampedZoom = Math.max(MIN_ZOOM, zoom)
    // Centers the diagram within the container, the same centering preserveAspectRatio="xMidYMid
    // meet" used to give for free -- the container is usually a little wider or taller than the
    // diagram's own aspect ratio, so one axis has slack to split evenly on both sides. Anchored at
    // layout.minX/minY, not (0, 0) -- content doesn't necessarily start there (an admin-dragged
    // position can put a node, or Start's own label overhang, left of or above wherever the
    // fallback layout ever would -- see computeLayout's own comment on minX).
    const x = layout.minX + layout.width / 2 - el.clientWidth / 2 / clampedZoom
    const y = layout.minY + layout.height / 2 - el.clientHeight / 2 / clampedZoom
    setView({ x, y, zoom: clampedZoom, width: el.clientWidth, height: el.clientHeight })
  }, [layout, view])

  // Reports every view change back to the caller (see initialView above) -- the one piece of this
  // hook's own state meant to outlive this component's remount, so whoever owns the per-item-type
  // cache needs to hear about every pan/zoom, not just the initial fit.
  useEffect(() => {
    if (view) onViewChange?.(view)
  }, [view, onViewChange])

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
    signalDescriptions,
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
    isEditing,
    isSaving,
    saveError,
    startEditing,
    saveEditing,
    cancelEditing,
    onNodePointerDown,
    onNodePointerMove,
    onNodePointerUp,
    onEdgeHandlePointerDown,
    onEdgeHandlePointerMove,
    onEdgeHandlePointerUp,
    draggingEdgeKey,
  }
}
