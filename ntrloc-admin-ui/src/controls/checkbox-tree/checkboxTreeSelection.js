// Selection models for CheckboxTree. A model decides what each node shows and what a click does:
//   stateOf(node, ancestors, selectedKeys) -> { checked, indeterminate, implied, disabled }
//   toggle(node, ancestors, selectedKeys) -> the new selectedKeys (a new Set)
// selectedKeys holds only what was chosen; anything implied is worked out from it. `ancestors` are
// root-first and don't include the node. A node with `disabled: true` can't be toggled in any model.

// Selecting downward (search's property tree): a selected branch covers everything beneath it.
// selectedKeys may hold node keys at any mix of granularity (a
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

function hasSelectedDescendant(node, selectedKeys) {
  return (node.children || []).some((child) => selectedKeys.has(child.key) || hasSelectedDescendant(child, selectedKeys))
}

export const selectDownward = {
  stateOf(node, ancestors, selectedKeys) {
    const checked = selectedKeys.has(node.key) || ancestors.some((a) => selectedKeys.has(a.key))
    return {
      checked,
      indeterminate: !checked && hasSelectedDescendant(node, selectedKeys),
      implied: false,
      disabled: Boolean(node.disabled),
    }
  },
  toggle(node, ancestors, selectedKeys) {
    const wasChecked = this.stateOf(node, ancestors, selectedKeys).checked
    return toggleCheckboxTreeNode(node, ancestors, selectedKeys, wasChecked)
  },
}

// Selecting upward (group membership): a selected node implies every node above it -- being in
// Senior editors puts you in Editors. Implied nodes show checked but can't be unchecked there; the
// node that implies them has to go. A key may appear in several places (a group with two parents);
// it's selected in all of them.
export const selectUpward = {
  stateOf(node, ancestors, selectedKeys) {
    const selected = selectedKeys.has(node.key)
    const implied = !selected && hasSelectedDescendant(node, selectedKeys)
    return { checked: selected || implied, indeterminate: false, implied, disabled: implied || Boolean(node.disabled) }
  },
  toggle(node, ancestors, selectedKeys) {
    const next = new Set(selectedKeys)
    if (next.has(node.key)) next.delete(node.key)
    else next.add(node.key)
    return next
  },
}

// Like selectUpward, but exactly one node is chosen (a group's single parent): choosing another
// replaces it, and the chosen one can't be cleared, only replaced.
export const selectOneUpward = {
  stateOf: selectUpward.stateOf,
  toggle(node, ancestors, selectedKeys) {
    return selectedKeys.has(node.key) ? selectedKeys : new Set([node.key])
  },
}

// node/ancestors describe the toggled checkbox (ancestors root-first, not including node itself);
// wasChecked is its displayed checked state *before* this toggle. Returns a new Set, never mutates
// selectedKeys.
function toggleCheckboxTreeNode(node, ancestors, selectedKeys, wasChecked) {
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
