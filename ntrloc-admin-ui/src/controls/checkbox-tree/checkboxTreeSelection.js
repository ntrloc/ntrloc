// Pure selection-update logic for CheckboxTree -- kept separate from rendering so it's usable
// wherever the tree itself is reused. selectedKeys may hold node keys at any mix of granularity (a
// whole branch's own key, individual leaf keys, or both at once) -- toggling never invents automatic
// consolidation (checking every child individually does not retroactively collapse into the parent's
// key) or cascading "preserve bare keys at every intermediate level" logic: unchecking one leaf under
// an already-broad selection flattens that one covering ancestor down to individual leaves once,
// rather than fanning out sibling bare-keys at every level in between. Deliberately simple over
// deliberately compact -- see the search feature's own notes on why.

function collectLeafKeys(node) {
  if (!node.children || node.children.length === 0) return [node.key]
  return node.children.flatMap(collectLeafKeys)
}

function collectAllKeys(node) {
  const keys = [node.key]
  ;(node.children || []).forEach((child) => keys.push(...collectAllKeys(child)))
  return keys
}

// node/ancestors describe the toggled checkbox (ancestors root-first, not including node itself);
// wasChecked is its displayed checked state *before* this toggle. Returns a new Set, never mutates
// selectedKeys.
export function toggleCheckboxTreeNode(node, ancestors, selectedKeys, wasChecked) {
  const next = new Set(selectedKeys)

  if (!wasChecked) {
    // Checking: this node's own key now covers everything beneath it, so any of its descendants'
    // keys already selected individually are redundant -- drop them.
    collectAllKeys(node).forEach((key) => next.delete(key))
    next.add(node.key)
    return next
  }

  if (next.has(node.key)) {
    // This node's own key is literally what's selected -- a plain uncheck.
    next.delete(node.key)
    return next
  }

  // Checked only because some ancestor's key covers it -- find the nearest covering ancestor,
  // remove its key, and replace it with every one of its own leaves except the ones under this node.
  const coveringAncestor = [...ancestors].reverse().find((a) => selectedKeys.has(a.key))
  if (!coveringAncestor) return next // defensive: shouldn't happen if wasChecked was computed correctly

  next.delete(coveringAncestor.key)
  const excludedLeafKeys = new Set(collectLeafKeys(node))
  collectLeafKeys(coveringAncestor).forEach((key) => {
    if (!excludedLeafKeys.has(key)) next.add(key)
  })
  return next
}
