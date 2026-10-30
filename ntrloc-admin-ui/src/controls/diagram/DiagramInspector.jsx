import CollapsiblePanel from '../collapsible-panel/CollapsiblePanel'
import Input from '../input/Input'
import './DiagramInspector.css'

// A diagram editor's Details panel, on the right of the diagram, collapsing like the nav sidebar
// (`panel` from useCollapsiblePanel). Shows `children` -- the selected thing's details -- or, with
// nothing selected, `emptyText`. Details lay out with the diagram-inspector-kind / -note / -problem
// classes.
function DiagramInspector({ panel, emptyText, children }) {
  return (
    <CollapsiblePanel
      className="diagram-inspector"
      side="right"
      title="Details"
      locked={panel.locked}
      collapsed={panel.collapsed}
      floating={panel.floating}
      onToggleLocked={panel.toggleLocked}
      onMouseEnter={panel.onMouseEnter}
      onMouseLeave={panel.onMouseLeave}
    >
      <diagram-inspector-details>{children ?? <p className="diagram-inspector-note">{emptyText}</p>}</diagram-inspector-details>
    </CollapsiblePanel>
  )
}

// One read-only detail of what's selected: its name over its value, or a dash for none.
export function InspectorField({ name, value }) {
  return <Input name={name} editable={false} value={value || '—'} />
}

export default DiagramInspector
