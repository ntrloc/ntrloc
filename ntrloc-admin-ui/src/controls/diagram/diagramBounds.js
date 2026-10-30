import { edgeLabelSize } from './diagramText'

// Everything a diagram draws, as { minX, minY, width, height } with `padding` all round: its boxes
// (rects), its edges' points (routes, from routeEdges) and the labels on their bends (Map<edge key,
// label text>).
export function diagramBounds(rects, routes, labels = new Map(), padding = 24) {
  let minX = Infinity
  let minY = Infinity
  let maxX = -Infinity
  let maxY = -Infinity
  const include = (x, y, width = 0, height = 0) => {
    minX = Math.min(minX, x)
    minY = Math.min(minY, y)
    maxX = Math.max(maxX, x + width)
    maxY = Math.max(maxY, y + height)
  }
  for (const rect of rects.values()) include(rect.x, rect.y, rect.width, rect.height)
  for (const [key, route] of routes) {
    for (const point of route.points) include(point.x, point.y)
    const label = labels.get(key)
    if (label) {
      const { width, height } = edgeLabelSize(label)
      include(route.label.x - width / 2, route.label.y - height / 2, width, height)
    }
  }
  if (minX === Infinity) return { minX: 0, minY: 0, width: 2 * padding, height: 2 * padding }
  return { minX: minX - padding, minY: minY - padding, width: maxX - minX + 2 * padding, height: maxY - minY + 2 * padding }
}
