import { useEffect, useRef, useState } from 'react'
import { toggleCheckboxTreeNode } from './checkboxTreeSelection'
import './CheckboxTree.css'

// A generic, reusable checkbox tree -- knows nothing about properties, schema, or search. `nodes` is
// a plain recursive shape ({ key, label, children? }); `selectedKeys` is the set of node keys the
// caller currently considers selected (any granularity); `onChange(newSelectedKeys)` reports the
// updated set after a toggle, with the add/remove/flatten mechanics already applied -- see
// checkboxTreeSelection.js for that logic. Everything starts expanded; collapsed state is local,
// per-node UI state, never reported outward.
function CheckboxTree({ nodes, selectedKeys, onChange }) {
  function handleToggle(node, ancestors, wasChecked) {
    onChange(toggleCheckboxTreeNode(node, ancestors, selectedKeys, wasChecked))
  }

  return (
    <ul className="checkbox-tree">
      {nodes.map((node) => (
        <CheckboxTreeNode
          key={node.key}
          node={node}
          ancestors={[]}
          selectedKeys={selectedKeys}
          ancestorChecked={false}
          onToggle={handleToggle}
        />
      ))}
    </ul>
  )
}

// ancestorChecked is threaded down rather than recomputed from scratch at every node: once some
// ancestor's own key is found in selectedKeys, every descendant below it renders checked with no
// further lookups needed.
function CheckboxTreeNode({ node, ancestors, selectedKeys, ancestorChecked, onToggle }) {
  const [expanded, setExpanded] = useState(true)
  const checkboxRef = useRef(null)
  const isLeaf = !node.children || node.children.length === 0
  const checked = ancestorChecked || selectedKeys.has(node.key)
  const indeterminate = !checked && !isLeaf && hasCheckedDescendant(node, selectedKeys)

  // The DOM's indeterminate state has no JSX/HTML attribute equivalent -- it can only be set
  // imperatively on the element itself.
  useEffect(() => {
    if (checkboxRef.current) checkboxRef.current.indeterminate = indeterminate
  }, [indeterminate])

  return (
    <li className="checkbox-tree-node">
      <div className="checkbox-tree-row">
        {isLeaf ? (
          <span className="checkbox-tree-toggle-spacer" aria-hidden="true" />
        ) : (
          <button
            type="button"
            className="checkbox-tree-toggle"
            onClick={() => setExpanded((current) => !current)}
            aria-label={expanded ? 'Collapse' : 'Expand'}
            aria-expanded={expanded}
          >
            {expanded ? '▼' : '▶'}
          </button>
        )}
        <label className="checkbox-tree-label">
          <input type="checkbox" ref={checkboxRef} checked={checked} onChange={() => onToggle(node, ancestors, checked)} />
          {node.label}
        </label>
      </div>
      {!isLeaf && expanded && (
        <ul className="checkbox-tree-children">
          {node.children.map((child) => (
            <CheckboxTreeNode
              key={child.key}
              node={child}
              ancestors={[...ancestors, node]}
              selectedKeys={selectedKeys}
              ancestorChecked={checked}
              onToggle={onToggle}
            />
          ))}
        </ul>
      )}
    </li>
  )
}

function hasCheckedDescendant(node, selectedKeys) {
  return (node.children || []).some((child) => selectedKeys.has(child.key) || hasCheckedDescendant(child, selectedKeys))
}

export default CheckboxTree
