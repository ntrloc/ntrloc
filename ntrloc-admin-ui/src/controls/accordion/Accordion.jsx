import { useState } from 'react'
import './Accordion.css'

// A vertical accordion -- each section's label is always visible as a clickable tab; clicking one
// makes it the active section, showing its own view directly beneath it and collapsing whichever
// section was active before. Only ever one section open at a time -- distinct from
// ItemPaneView's own CollapsibleSection, where every section expands/collapses independently.
// Uncontrolled by default (own internal state, same convention as TreeSelect's open/closed state),
// but accepts an optional activeKey/onActiveKeyChange pair for a consumer that needs to switch
// sections itself -- e.g. SchemaView jumping to "Traits" when a trait link elsewhere is clicked.
// Starts on the first section when uncontrolled, same "start expanded" precedent
// CheckboxTree/CollapsibleSection already established, rather than starting fully closed.
function Accordion({ sections, activeKey: controlledActiveKey, onActiveKeyChange }) {
  const [internalActiveKey, setInternalActiveKey] = useState(sections[0]?.key)
  const isControlled = controlledActiveKey !== undefined
  const activeKey = isControlled ? controlledActiveKey : internalActiveKey

  function selectKey(key) {
    if (!isControlled) setInternalActiveKey(key)
    onActiveKeyChange?.(key)
  }

  return (
    <accordion-view>
      {sections.map((section) => {
        const active = section.key === activeKey
        return (
          <accordion-section key={section.key} data-active={active || undefined}>
            <button
              type="button"
              className="accordion-tab"
              aria-expanded={active}
              onClick={() => selectKey(section.key)}
            >
              {section.label}
            </button>
            {active && <accordion-panel>{section.view}</accordion-panel>}
          </accordion-section>
        )
      })}
    </accordion-view>
  )
}

export default Accordion
