// Routes an orthogonal connection between source and target that avoids every other shape passed
// in as an obstacle -- not just a heuristic about which axis to turn on first (see
// ConnectionLayouter.js's own simple 'h:h'/'v:v'/'h:v'/'v:h' candidates), but an actual check
// against each obstacle's bounding box, inflated by a small margin so a route never runs flush
// along another shape's edge either. Built as a classic "visibility grid" orthogonal router: every
// obstacle/source/target edge coordinate becomes a candidate grid line, Dijkstra finds the
// cheapest way through the resulting grid graph from any of the source's four side-midpoints to
// any of the target's, and a turn penalty keeps it preferring fewer corners over a merely-shorter
// path. Entirely generic -- no BPMN- or DMN-specific code -- so both ntrloc-process-editor and the
// DRD canvas share this one file unchanged.

const OBSTACLE_PADDING = 15;
const TURN_PENALTY = 60;

function inflate(rect, padding) {
  return { x: rect.x - padding, y: rect.y - padding, width: rect.width + 2 * padding, height: rect.height + 2 * padding };
}

function pointInsideAny(x, y, obstacles) {
  return obstacles.some((r) => x > r.x && x < r.x + r.width && y > r.y && y < r.y + r.height);
}

// An axis-aligned segment is blocked if it runs through (not just touches the edge of) any
// obstacle's interior -- touching is fine and expected, since source/target's own boundary is
// where every route legitimately starts/ends.
function segmentBlocked(p1, p2, obstacles) {
  if (p1.y === p2.y) {
    const y = p1.y, xa = Math.min(p1.x, p2.x), xb = Math.max(p1.x, p2.x);
    return obstacles.some((r) => y > r.y && y < r.y + r.height && xb > r.x && xa < r.x + r.width);
  }
  const x = p1.x, ya = Math.min(p1.y, p2.y), yb = Math.max(p1.y, p2.y);
  return obstacles.some((r) => x > r.x && x < r.x + r.width && yb > r.y && ya < r.y + r.height);
}

function sideMidpoints(rect) {
  return [
    { x: rect.x + rect.width / 2, y: rect.y, side: 'top' },
    { x: rect.x + rect.width / 2, y: rect.y + rect.height, side: 'bottom' },
    { x: rect.x, y: rect.y + rect.height / 2, side: 'left' },
    { x: rect.x + rect.width, y: rect.y + rect.height / 2, side: 'right' },
  ];
}

class MinHeap {
  constructor() { this._items = []; }
  get size() { return this._items.length; }
  push(item) {
    this._items.push(item);
    let i = this._items.length - 1;
    while (i > 0) {
      const parent = (i - 1) >> 1;
      if (this._items[parent].cost <= this._items[i].cost) break;
      [this._items[parent], this._items[i]] = [this._items[i], this._items[parent]];
      i = parent;
    }
  }
  pop() {
    const top = this._items[0];
    const last = this._items.pop();
    if (this._items.length) {
      this._items[0] = last;
      let i = 0;
      for (;;) {
        const l = i * 2 + 1, r = i * 2 + 2;
        let smallest = i;
        if (l < this._items.length && this._items[l].cost < this._items[smallest].cost) smallest = l;
        if (r < this._items.length && this._items[r].cost < this._items[smallest].cost) smallest = r;
        if (smallest === i) break;
        [this._items[smallest], this._items[i]] = [this._items[i], this._items[smallest]];
        i = smallest;
      }
    }
    return top;
  }
}

/**
 * @param {{x,y,width,height}} source
 * @param {{x,y,width,height}} target
 * @param {{x,y,width,height}[]} obstacles
 * @return {Array<{x,y}>|null} an obstacle-free orthogonal route from a source side-midpoint to a
 *   target side-midpoint, or null if every candidate start/end point is itself blocked, or no
 *   route through the grid exists at all (both are rare -- e.g. an obstacle directly overlapping
 *   source or target).
 */
export function routeAroundObstacles(source, target, obstacles) {
  const inflated = obstacles.map((r) => inflate(r, OBSTACLE_PADDING));

  const sourceSides = sideMidpoints(source).filter((p) => !pointInsideAny(p.x, p.y, inflated));
  const targetSides = sideMidpoints(target).filter((p) => !pointInsideAny(p.x, p.y, inflated));
  if (!sourceSides.length || !targetSides.length) return null;

  // Every obstacle/source/target edge becomes a grid line -- the standard "visibility grid"
  // construction for orthogonal routing. A route only ever needs to turn at one of these
  // coordinates; anywhere else, going straight is never worse.
  const xsSet = new Set(), ysSet = new Set();
  [source, target, ...inflated].forEach((r) => { xsSet.add(r.x); xsSet.add(r.x + r.width); });
  [source, target, ...inflated].forEach((r) => { ysSet.add(r.y); ysSet.add(r.y + r.height); });
  sourceSides.concat(targetSides).forEach((p) => { xsSet.add(p.x); ysSet.add(p.y); });

  const xs = [...xsSet].sort((a, b) => a - b);
  const ys = [...ysSet].sort((a, b) => a - b);
  const xi = new Map(xs.map((x, i) => [x, i]));
  const yi = new Map(ys.map((y, i) => [y, i]));

  const targetKeys = new Set(targetSides.map((p) => `${xi.get(p.x)}:${yi.get(p.y)}`));

  // Dijkstra over grid nodes, where a node's state also carries the direction it was entered
  // from ('h'/'v'/null) -- entering the same node from a different direction is a genuinely
  // different, separately-costed state, since it changes whether continuing costs a turn penalty.
  const dist = new Map();
  const prev = new Map();
  const heap = new MinHeap();

  function key(x, y, dir) { return `${x}:${y}:${dir || ''}`; }

  sourceSides.forEach((p) => {
    const x = xi.get(p.x), y = yi.get(p.y);
    const k = key(x, y, null);
    dist.set(k, 0);
    heap.push({ x, y, dir: null, cost: 0 });
  });

  let goalKey = null;

  while (heap.size) {
    const cur = heap.pop();
    const curKey = key(cur.x, cur.y, cur.dir);
    if (cur.cost > (dist.get(curKey) ?? Infinity)) continue;

    if (targetKeys.has(`${cur.x}:${cur.y}`)) {
      goalKey = curKey;
      break;
    }

    const neighbors = [
      cur.x > 0 ? { x: cur.x - 1, y: cur.y, dir: 'h' } : null,
      cur.x < xs.length - 1 ? { x: cur.x + 1, y: cur.y, dir: 'h' } : null,
      cur.y > 0 ? { x: cur.x, y: cur.y - 1, dir: 'v' } : null,
      cur.y < ys.length - 1 ? { x: cur.x, y: cur.y + 1, dir: 'v' } : null,
    ].filter(Boolean);

    for (const n of neighbors) {
      const p1 = { x: xs[cur.x], y: ys[cur.y] };
      const p2 = { x: xs[n.x], y: ys[n.y] };
      if (segmentBlocked(p1, p2, inflated)) continue;

      const dx = p2.x - p1.x, dy = p2.y - p1.y;
      const turn = cur.dir && cur.dir !== n.dir ? TURN_PENALTY : 0;
      const cost = cur.cost + Math.abs(dx) + Math.abs(dy) + turn;

      const nKey = key(n.x, n.y, n.dir);
      if (cost < (dist.get(nKey) ?? Infinity)) {
        dist.set(nKey, cost);
        prev.set(nKey, curKey);
        heap.push({ x: n.x, y: n.y, dir: n.dir, cost });
      }
    }
  }

  if (!goalKey) return null;

  const path = [];
  let k = goalKey;
  while (k) {
    const [gx, gy] = k.split(':');
    path.unshift({ x: xs[Number(gx)], y: ys[Number(gy)] });
    k = prev.get(k);
  }

  return path;
}
