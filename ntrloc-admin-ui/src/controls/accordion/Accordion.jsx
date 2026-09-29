import { useState } from 'react'
import './Accordion.css'

// A vertical accordion -- each section's label is always visible as a clickable tab; clicking one
// makes it the active section, showing its own view directly beneath it and collapsing whichever
// section was active before. Only ever one section open at a time -- distinct from
// ItemPaneView's own CollapsibleSection, where every section expands/collapses independently.
// Uncontrolled: which section is active is this control's own internal state, never reported
// outward, same convention as TreeSelect's open/closed state -- nothing outside an accordion has
// needed to react to which tab is showing yet. Starts on the first section, same "start expanded"
// precedent CheckboxTree/CollapsibleSection already established, rather than starting fully closed.
function Accordion({ sections }) {
  const [activeKey, setActiveKey] = useState(sections[0]?.key)

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
              onClick={() => setActiveKey(section.key)}
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
