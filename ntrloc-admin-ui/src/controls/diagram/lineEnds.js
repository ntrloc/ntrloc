import { ALL_SIDES, nearestPort } from './orthogonalRoutes'

// Dragging a line's end from box to box. On screen pixels, so they feel the same at any zoom: how far
// past its box an attached end can be dragged before it lets go, and how close a loose end has to come
// to a box to snap to it.
export const DISCONNECT_DISTANCE = 40
export const CONNECT_DISTANCE = 24

// How far a point is from a box -- 0 inside it.
function distanceToRect(rect, point) {
  const dx = Math.max(rect.x - point.x, 0, point.x - (rect.x + rect.width))
  const dy = Math.max(rect.y - point.y, 0, point.y - (rect.y + rect.height))
  return Math.hypot(dx, dy)
}

// Where a dragged line end is, with the pointer at `point` (diagram units): still on the box it's
// attached to (attachedId) while within DISCONNECT_DISTANCE of it; otherwise snapped to the nearest
// box canAttach(id) allows, within CONNECT_DISTANCE; otherwise loose, at the point.
// Returns { nodeId, port } when attached, { nodeId: null, point } when loose.
export function dragLineEnd({ point, attachedId, rects, canAttach, zoom, sides = ALL_SIDES }) {
  const attached = attachedId && rects.get(attachedId)
  if (attached && distanceToRect(attached, point) * zoom <= DISCONNECT_DISTANCE) {
    return { nodeId: attachedId, port: nearestPort(attached, point, sides) }
  }
  let nearest = null
  for (const [id, rect] of rects) {
    if (!canAttach(id)) continue
    const distance = distanceToRect(rect, point) * zoom
    if (distance <= CONNECT_DISTANCE && (!nearest || distance < nearest.distance)) nearest = { id, distance }
  }
  if (nearest) return { nodeId: nearest.id, port: nearestPort(rects.get(nearest.id), point, sides) }
  return { nodeId: null, point }
}
