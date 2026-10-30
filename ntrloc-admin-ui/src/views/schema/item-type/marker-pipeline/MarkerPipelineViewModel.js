import { useEffect, useLayoutEffect, useRef, useState } from 'react'
import { dragLineEnd } from '../../../../controls/diagram/lineEnds'
import { startDiagramDrag } from '../../../../controls/diagram/startDiagramDrag'
import { useDiagramSelection } from '../../../../controls/diagram/useDiagramSelection'
import { useMarkerModel } from '../../../../global/model/MarkerModel'
import { useUndoable, useUndoShortcuts } from '../../../../global/useUndoable'
import {
  addCarrierConnection,
  addNode,
  deleteNode,
  gatePinsOf,
  initialDraft,
  moveCarrierEnd,
  moveSignalEnd,
  nodeProblems,
  setBlockMarkers,
  withPositionsFixed,
} from './pipelineDraft'
import { useSchemaSnapshot } from '../../../../global/model/SchemaCache'
import { computeLayout, inlinedConditionGroupFor, orthogonalPath, signalNodes } from './markerPipelineLayout'
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

// Each condition's pin, described in a line: its subject and its test, e.g. "product.owner = Clubs".
function pinDescriptionsOf(schema, itemTypeId, pipeline) {
  const descriptions = new Map()
  for (const signal of pipeline.topLevelSignals ?? []) {
    if (signal.type === 'CONDITION_GROUP') {
      const subject = describeConditionGroupSubject(schema, itemTypeId, signal)
      for (const c of signal.conditions) descriptions.set(c.id, `${subject} ${describeConditionShort(c.predicate)}`.trim())
    } else {
      const box = describeCondition(schema, itemTypeId, signal)
      descriptions.set(signal.id, [box?.subjectText, box?.testText ?? box?.label].filter(Boolean).join(' '))
    }
  }
  return descriptions
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

const NO_HISTORY = { undo: () => {}, redo: () => {} }
// How far (screen pixels) a press on Start's circle has to move to draw a connection, not select Start.
const START_DRAG_THRESHOLD = 4
// Roughly half a new block's size, to center it on where it's placed.
const NEW_NODE_HALF_WIDTH = 80
const NEW_NODE_HALF_HEIGHT = 30

// editable: the diagram opens ready to edit (in the editor dialog), rather than read-only -- its
// pipeline and layout then a draft (see pipelineDraft) with each change a step to undo (Cmd/Ctrl+Z)
// or redo (Cmd/Ctrl+Shift+Z, or Ctrl+Y).
export function useMarkerPipelineViewModel(pipelineView, itemTypeId, savePipeline, initialView, onViewChange, { editable = false } = {}) {
  const { getMarkersForItemType } = useMarkerModel()
  const markerNamesById = new Map(getMarkersForItemType(itemTypeId).map((m) => [m.id, m.name]))
  // Reaches past the shaped per-concept Models (ItemTypeModel/LinkModel/StateMachineModel) straight
  // to the raw schema snapshot -- condition text needs to resolve ids against *whatever item type a
  // subject chain has stepped onto by then* (e.g. a linked item's own state machine), which none of
  // those per-type-scoped Models can do; see pipelineConditionText.js's own comment.
  const schema = useSchemaSnapshot()

  // The draft while editing; otherwise what's saved. Layout is always computed off whichever is live,
  // so a drag in progress renders exactly like a saved one would.
  const history = useUndoable(() => initialDraft(pipelineView))
  useUndoShortcuts(editable ? history : NO_HISTORY)
  const draft = editable ? history.present : null
  const pipeline = draft?.pipeline ?? pipelineView.pipeline
  const effectivePositions = draft?.positions ?? new Map(Object.entries(pipelineView.layout?.positions ?? {}))
  const effectiveEdgeBends = draft?.edgeBends ?? new Map(Object.entries(pipelineView.layout?.edgeBends ?? {}))
  const problems = editable ? nodeProblems(pipeline) : new Map()
  // Why it can't be saved as it is, if it can't. (New blocks go with refIds, which the server swaps
  // for ids.)
  const saveBlocker = problems.size > 0 ? 'Some blocks still need something -- see their details.' : null
  // The item type's markers, for choosing a block's.
  const markerOptions = getMarkersForItemType(itemTypeId)
    .map((m) => ({ value: m.id, label: m.name }))
    .sort((a, b) => a.label.localeCompare(b.label))

  const blockCtx = { markerNamesById, pipeline, schema, itemTypeId }
  const blocks = new Map(pipeline.carrierBlocks.map((b) => [b.id, blockLabel(b, blockCtx)]))
  const signalDescriptions = new Map(signalNodes(pipeline).map((node) => [node.id, signalNodeLabel(schema, itemTypeId, node.signal)]))
  const layout = computeLayout(pipeline, effectivePositions, blocks, signalDescriptions, effectiveEdgeBends)
  const pinDescriptions = pinDescriptionsOf(schema, itemTypeId, pipeline)
  // What's selected while editing: a node (kind 'node'), by its id.
  const selection = useDiagramSelection()

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

  const isEditing = editable
  const [isSaving, setIsSaving] = useState(false)
  const [saveError, setSaveError] = useState(null)
  const nodeDragRef = useRef(null)

  // Whether it saved.
  async function saveEditing() {
    setIsSaving(true)
    setSaveError(null)
    try {
      await savePipeline({
        pipeline: draft.pipeline,
        layout: { positions: Object.fromEntries(draft.positions), edgeBends: Object.fromEntries(draft.edgeBends) },
      })
      return true
    } catch (error) {
      setSaveError(error.message || 'Failed to save pipeline layout.')
      return false
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
    selection.select('node', nodeId)
    // Falls back to the node's current *rendered* position (effectivePositions might not have an
    // entry yet -- e.g. a node still sitting at its rank-based fallback spot, never dragged before)
    // rather than requiring draftPositions to already know about every node up front.
    const current = effectivePositions.get(nodeId) ?? layout.rects.get(nodeId)
    nodeDragRef.current = {
      nodeId,
      startX: event.clientX,
      startY: event.clientY,
      startPos: { x: current.x, y: current.y },
      gesture: history.startGesture(),
    }
    event.currentTarget.setPointerCapture(event.pointerId)
  }

  function onNodePointerMove(event) {
    if (!nodeDragRef.current) return
    event.stopPropagation()
    const { nodeId, startX, startY, startPos, gesture } = nodeDragRef.current
    const dx = (event.clientX - startX) / view.zoom
    const dy = (event.clientY - startY) / view.zoom
    gesture.update((d) => ({ ...d, positions: new Map(d.positions).set(nodeId, { x: startPos.x + dx, y: startPos.y + dy }) }))
  }

  function onNodePointerUp(event) {
    nodeDragRef.current?.gesture.end()
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
    edgeDragRef.current = {
      edgeKey,
      axis: handle.axis,
      startX: event.clientX,
      startY: event.clientY,
      startValue: current,
      gesture: history.startGesture(),
    }
    setDraggingEdgeKey(edgeKey)
    event.currentTarget.setPointerCapture(event.pointerId)
  }

  function onEdgeHandlePointerMove(event) {
    if (!edgeDragRef.current) return
    event.stopPropagation()
    const { edgeKey, axis, startX, startY, startValue, gesture } = edgeDragRef.current
    const delta = axis === 'x' ? (event.clientX - startX) / view.zoom : (event.clientY - startY) / view.zoom
    gesture.update((d) => ({ ...d, edgeBends: new Map(d.edgeBends).set(edgeKey, startValue + delta) }))
  }

  function onEdgeHandlePointerUp(event) {
    edgeDragRef.current?.gesture.end()
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
    // A press on the canvas itself (a node's or handle's own press never reaches here) deselects.
    if (isEditing) selection.clear()
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

  // A change to the pipeline's structure, made with every node's position fixed where it's drawn first:
  // a node never placed is drawn where the flow puts it, and changing the flow would move it.
  function changeStructure(fn) {
    history.change((d) => fn(withPositionsFixed(d, layout.rects)))
  }

  // A new block or condition from the palette, centered on where it was placed (screen
  // coordinates), and selected.
  function placeNode(kind, { clientX, clientY }) {
    if (!view) return
    const rect = containerRef.current.getBoundingClientRect()
    const x = view.x + (clientX - rect.left) / view.zoom
    const y = view.y + (clientY - rect.top) / view.zoom
    const { nodeId } = addNode(history.present, kind, { x: 0, y: 0 })
    changeStructure((d) => addNode(d, kind, { x: x - NEW_NODE_HALF_WIDTH, y: y - NEW_NODE_HALF_HEIGHT }).draft)
    selection.select('node', nodeId)
  }

  // --- Connections: selecting one, and moving its ends ---

  // A connection end being dragged: { key, end, nodeId (null while it's loose), point }.
  const [endDrag, setEndDrag] = useState(null)
  const zeroRect = (p) => ({ x: p.x, y: p.y, width: 0, height: 0 })
  const signalPins = new Set(layout.signalNodes.flatMap((n) => n.pinIds))
  // Carrier output pins: everything that can start a carrier connection -- not End, which has none,
  // nor a condition's pin.
  const isCarrierOutput = (id) => id !== pipeline.end.id && !signalPins.has(id)
  // A carrier output pin's block: a <select> row's is its <select>.
  const ownerOfOutput = (pinId) => pipeline.carrierBlocks.find((b) => b.type === 'SELECT' && b.adds.some((r) => r.id === pinId))?.id ?? pinId
  const connectedOutputs = new Set(pipeline.routes.map((r) => r.outputPinId))

  function onEdgePointerDown(event, edgeKey) {
    if (!isEditing || event.button !== 0) return
    event.stopPropagation()
    selection.select('edge', edgeKey)
  }

  // Where one end of a connection may be moved to, as pins (by id) to snap to -- its own included,
  // so it can go back: a carrier's start to any carrier output, its end to any block's input (not
  // its own block, nor where the same output already leads); a condition connection's start to any
  // condition pin, its end to any gate (not one already reading that condition).
  function endCandidates(edge, end) {
    const pins = new Map()
    const put = (id, point) => pins.set(id, zeroRect(point))
    if (edge.outputPinId) {
      const feeds = (out) => pipeline.routes.find((r) => r.outputPinId === out)?.inputPinIds ?? []
      if (end === 'from') {
        for (const [id, point] of layout.outputPinPositions) {
          if (!isCarrierOutput(id) || ownerOfOutput(id) === edge.targetId) continue
          if (id !== edge.outputPinId && feeds(id).includes(edge.targetId)) continue
          put(id, point)
        }
      } else {
        for (const [id, point] of layout.inputPinPositions) {
          if (id === pipeline.start.id || id === ownerOfOutput(edge.outputPinId)) continue
          if (id !== edge.targetId && feeds(edge.outputPinId).includes(id)) continue
          put(id, point)
        }
      }
    } else if (end === 'from') {
      for (const [id, point] of layout.outputPinPositions) {
        if (!signalPins.has(id)) continue
        if (id !== edge.pinId && gatePinsOf(pipeline, edge.ownerId).includes(id)) continue
        put(id, point)
      }
    } else {
      for (const [id, point] of layout.gateAnchors) {
        if (id !== edge.ownerId && gatePinsOf(pipeline, id).includes(edge.pinId)) continue
        put(id, point)
      }
    }
    return pins
  }

  // Dragging a selected connection's end: on its pin while it's near it, then loose, following the
  // pointer, and snapping to any pin it may move to (see lineEnds' dragLineEnd -- the same as the
  // state machine editor's transitions). Let go on a pin, it moves there; let go loose, it goes back.
  function onEdgeEndPointerDown(event, edge, end) {
    if (!view) return
    const isCarrier = Boolean(edge.outputPinId)
    const originalId = isCarrier ? (end === 'from' ? edge.outputPinId : edge.targetId) : end === 'from' ? edge.pinId : edge.ownerId
    const start = end === 'from' ? edge.points[0] : edge.points[edge.points.length - 1]
    const pins = endCandidates(edge, end)
    let current = { nodeId: originalId }
    startDiagramDrag(
      event,
      view.zoom,
      (dx, dy) => {
        const point = { x: start.x + dx, y: start.y + dy }
        const at = dragLineEnd({ point, attachedId: current.nodeId, rects: pins, canAttach: () => true, zoom: view.zoom })
        current = at
        setEndDrag({ key: edge.key, end, nodeId: at.nodeId, point: at.nodeId ? pins.get(at.nodeId) : point })
      },
      () => {
        setEndDrag(null)
        if (!current.nodeId || current.nodeId === originalId) return
        const moved = current.nodeId
        if (isCarrier) {
          changeStructure((d) => moveCarrierEnd(d, edge, end, moved))
          const out = end === 'from' ? moved : edge.outputPinId
          selection.select('edge', `${out}->${end === 'to' ? moved : edge.targetId}`)
        } else {
          changeStructure((d) => moveSignalEnd(d, edge, end, moved))
          selection.select('edge', `signal:${end === 'from' ? moved : edge.pinId}=>${end === 'to' ? moved : edge.ownerId}`)
        }
      },
    )
  }

  // Drawing a new carrier connection from an output pin with nothing connected, or from Start: it
  // follows the pointer,
  // snapping to any block input it may lead to (as a moved end does). Let go on one, it's made; let go
  // anywhere else, it's dropped.
  const [newConnection, setNewConnection] = useState(null)

  // threshold: how far (screen pixels) the pointer has to move before it's a new connection rather
  // than a click.
  function onOutputPinPointerDown(event, outputPinId, { threshold = 0 } = {}) {
    if (!isEditing || !view) return
    const start = layout.outputPinPositions.get(outputPinId)
    const pins = endCandidates({ outputPinId, targetId: null }, 'to')
    let current = { nodeId: null }
    startDiagramDrag(
      event,
      view.zoom,
      (dx, dy) => {
        const point = { x: start.x + dx, y: start.y + dy }
        current = dragLineEnd({ point, attachedId: current.nodeId, rects: pins, canAttach: () => true, zoom: view.zoom })
        setNewConnection({ outputPinId, point: current.nodeId ? pins.get(current.nodeId) : point })
      },
      () => {
        setNewConnection(null)
        if (!current.nodeId) return
        const targetId = current.nodeId
        changeStructure((d) => addCarrierConnection(d, outputPinId, targetId))
        selection.select('edge', `${outputPinId}->${targetId}`)
      },
      { threshold },
    )
  }

  const newConnectionPoints = newConnection
    ? orthogonalPath(layout.outputPinPositions.get(newConnection.outputPinId), newConnection.point).points
    : null

  // Start leads to any number of blocks: a small circle at its middle to draw another connection from
  // -- shown on hover, or all along while it leads nowhere yet. Not while one of its connections is
  // selected: that connection's end handle is in the same place. { x, y, shown } or null.
  const startPoint = layout.outputPinPositions.get(pipeline.start.id)
  const startConnectionSelected = selection.selection?.kind === 'edge' && selection.selection.id.startsWith(`${pipeline.start.id}->`)
  const startConnector =
    isEditing && startPoint && !startConnectionSelected ? { ...startPoint, shown: !connectedOutputs.has(pipeline.start.id) } : null

  // A press on Start's circle selects Start; a drag from it draws a new connection.
  function onStartConnectorPointerDown(event) {
    selection.select('node', pipeline.start.id)
    onOutputPinPointerDown(event, pipeline.start.id, { threshold: START_DRAG_THRESHOLD })
  }

  // Carrier output pins with nothing connected -- where a new connection can be drawn from. Start can
  // always lead to more (see startConnector).
  const emptyOutputPins = isEditing
    ? [...layout.outputPinPositions].filter(([id]) => isCarrierOutput(id) && id !== pipeline.start.id && !connectedOutputs.has(id))
    : []

  // The connection being dragged, drawn to where its end is now.
  let endDragPoints = null
  if (endDrag) {
    const edge = [...layout.edges, ...layout.signalEdges].find((e) => e.key === endDrag.key)
    if (edge) {
      const from = endDrag.end === 'from' ? endDrag.point : edge.points[0]
      const to = endDrag.end === 'to' ? endDrag.point : edge.points[edge.points.length - 1]
      endDragPoints = orthogonalPath(from, to).points
    }
  }

  function setMarkers(blockId, markerIds) {
    changeStructure((d) => setBlockMarkers(d, blockId, markerIds))
  }

  function removeNode(nodeId) {
    changeStructure((d) => deleteNode(d, nodeId))
    if (selection.isSelected('node', nodeId)) selection.clear()
  }

  return {
    pipeline,
    saveBlocker,
    problems,
    markerOptions,
    setMarkers,
    placeNode,
    removeNode,
    layout,
    blocks,
    signalDescriptions,
    start: pipeline.start,
    end: pipeline.end,
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
    saveEditing,
    onNodePointerDown,
    onNodePointerMove,
    onNodePointerUp,
    onEdgeHandlePointerDown,
    onEdgeHandlePointerMove,
    onEdgeHandlePointerUp,
    draggingEdgeKey,
    pinDescriptions,
    selection: selection.selection,
    isNodeSelected: (nodeId) => selection.isSelected('node', nodeId),
    isEdgeSelected: (edgeKey) => selection.isSelected('edge', edgeKey),
    onEdgePointerDown,
    onEdgeEndPointerDown,
    endDrag,
    endDragPoints,
    isOutputConnected: (pinId) => connectedOutputs.has(pinId),
    emptyOutputPins,
    startConnector,
    onStartConnectorPointerDown,
    onOutputPinPointerDown,
    newConnectionPoints,
  }
}
