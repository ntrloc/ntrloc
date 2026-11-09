import { useEffect, useRef, useState } from 'react'
import { selectDownward } from './checkboxTreeSelection'
import './CheckboxTree.css'

// A generic checkbox tree -- knows nothing about properties, groups, or search. `nodes` is a plain
// recursive shape ({ key, label, children?, disabled? }); `selectedKeys` is the set the caller
// considers selected; `onChange(newSelectedKeys)` reports the set after a click. What a selection
// means -- whether a branch covers what's below it, or a node implies what's above it -- is the
// `selection` model's business (see checkboxTreeSelection.js). `defaultExpanded(node)` decides
// which branches start open (all, unless given); after that, open/closed is local UI state.
function CheckboxTree({ nodes, selectedKeys, onChange, selection = selectDownward, defaultExpanded = () => true }) {
  function handleToggle(node, ancestors) {
    onChange(selection.toggle(node, ancestors, selectedKeys))
  }

  return (
    <ul className="checkbox-tree">
      {nodes.map((node) => (
        <CheckboxTreeNode
          key={node.key}
          node={node}
          ancestors={[]}
          selectedKeys={selectedKeys}
          selection={selection}
          defaultExpanded={defaultExpanded}
          onToggle={handleToggle}
        />
      ))}
    </ul>
  )
}

function CheckboxTreeNode({ node, ancestors, selectedKeys, selection, defaultExpanded, onToggle }) {
  const [expanded, setExpanded] = useState(() => defaultExpanded(node))
  const checkboxRef = useRef(null)
  const isLeaf = !node.children || node.children.length === 0
  const { checked, indeterminate, implied, disabled } = selection.stateOf(node, ancestors, selectedKeys)

  // The DOM's indeterminate state has no JSX/HTML attribute equivalent -- it can only be set
  // imperatively on the element itself.
  useEffect(() => {
    if (checkboxRef.current) checkboxRef.current.indeterminate = indeterminate
  }, [indeterminate])

  return (
    <li className="checkbox-tree-node">
      <div className="checkbox-tree-row" data-implied={implied || undefined}>
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
        <label className="checkbox-tree-label" data-disabled={disabled || undefined}>
          <input
            type="checkbox"
            ref={checkboxRef}
            checked={checked}
            disabled={disabled}
            onChange={() => onToggle(node, ancestors)}
          />
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
              selection={selection}
              defaultExpanded={defaultExpanded}
              onToggle={onToggle}
            />
          ))}
        </ul>
      )}
    </li>
  )
}

export default CheckboxTree
