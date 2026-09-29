import { useState } from 'react'
import PaneView from '../PaneView'
import { flattenProperties } from '../flattenProperties'
import { useItemPaneViewModel } from './ItemPaneViewModel'
import './ItemPaneView.css'

function ItemPaneView({ itemTypeName, itemId, onClose }) {
  const { status, item } = useItemPaneViewModel(itemTypeName, itemId)
  const title = item ? item.displayLabel : itemTypeName

  return (
    <PaneView title={title} onClose={onClose}>
      {status === 'loading' && <item-pane-view>Loading…</item-pane-view>}
      {status === 'notFound' && <item-pane-view>Item not found.</item-pane-view>}
      {status === 'error' && <item-pane-view>Failed to load item.</item-pane-view>}
      {status === 'ready' && item && <ItemPaneContent item={item} />}
    </PaneView>
  )
}

function ItemPaneContent({ item }) {
  const flatProperties = flattenProperties(item.properties)
  const linkEntries = Object.entries(item.links ?? {})
  const stateEntries = Object.entries(item.states ?? {})

  return (
    <item-pane-view>
      <div className="item-pane-type">{item.itemType}</div>

      <CollapsibleSection title="Properties">
        {flatProperties.map(([path, value]) => (
          <div key={path} className="item-pane-property">
            <span className="item-pane-property-name">{path}</span>
            <span className="item-pane-property-value">{String(value)}</span>
          </div>
        ))}
      </CollapsibleSection>

      {linkEntries.length > 0 && (
        <CollapsibleSection title="Links">
          {linkEntries.map(([perspective, links]) => (
            <div key={perspective} className="item-pane-link-group">
              <span className="item-pane-link-perspective">{perspective}</span>
              {links.map((link) => (
                <div key={link.linkId} className="item-pane-link-target">
                  <div className="item-pane-link-target-label">{link.item.displayLabel}</div>
                  {flattenProperties(link.item.properties).map(([path, value]) => (
                    <div key={path} className="item-pane-property">
                      <span className="item-pane-property-name">{path}</span>
                      <span className="item-pane-property-value">{String(value)}</span>
                    </div>
                  ))}
                </div>
              ))}
            </div>
          ))}
        </CollapsibleSection>
      )}

      {stateEntries.length > 0 && (
        <item-pane-section>
          <div className="item-pane-section-title">States</div>
          {stateEntries.map(([machineName, state]) => (
            <div key={machineName} className="item-pane-state">
              <span className="item-pane-state-machine">{machineName}</span>
              <span className="item-pane-state-value">{state.currentState ?? '—'}</span>
            </div>
          ))}
        </item-pane-section>
      )}

      {item.markers && item.markers.length > 0 && (
        <item-pane-section>
          <div className="item-pane-section-title">Markers</div>
          <div className="item-pane-markers">{item.markers.join(', ')}</div>
        </item-pane-section>
      )}
    </item-pane-view>
  )
}

// Local to ItemPaneView -- collapsed state is per-section UI state, never reported outward, same
// convention as CheckboxTree's own expand/collapse. Starts expanded, matching CheckboxTree's own
// "start with everything expanded" precedent.
function CollapsibleSection({ title, children }) {
  const [expanded, setExpanded] = useState(true)

  return (
    <item-pane-section>
      <button
        type="button"
        className="item-pane-section-toggle"
        onClick={() => setExpanded((current) => !current)}
        aria-expanded={expanded}
      >
        <span className="item-pane-section-toggle-icon">{expanded ? '▼' : '▶'}</span>
        <span className="item-pane-section-title">{title}</span>
      </button>
      {expanded && children}
    </item-pane-section>
  )
}

export default ItemPaneView
