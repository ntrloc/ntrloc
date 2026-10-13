import { useTabViewModel } from './TabViewModel'
import './TabView.css'

// A horizontal tab strip -- one section's view shown at a time, switched by clicking its tab.
// Distinct from Accordion (vertical, every tab always visible as a full-width bar, the active
// section's panel expands in place beneath it): TabView's tabs sit in a single row and only the
// active section's view is ever rendered.
//
// indent: space to the left of the first tab, 10px by default. borderStyle: 'full' (default,
// today's only look -- a border around the whole content area) or 'top' (just the one rule between
// the tab row and the content, no border around the content's other three sides). contentPadding:
// the panel's own padding, 10px by default -- a section may carry its own `padding` instead (see
// sections' own shape below), which wins over this default while that section is the active one.
//
// sections: array of { key, label, view, padding? }. padding is optional, per-section -- overrides
// contentPadding for the panel only while that particular section is the active one, e.g. a section
// whose own view already manages its own inset (full-bleed content, a diagram that wants to use
// every pixel) can ask for 0 without changing what every other tab gets.
function TabView({ sections, activeKey: controlledActiveKey, onActiveKeyChange, indent = 10, borderStyle = 'full', contentPadding = 10 }) {
  const { activeKey, activeSection, selectTab } = useTabViewModel(sections, controlledActiveKey, onActiveKeyChange)
  const panelPadding = activeSection?.padding ?? contentPadding

  return (
    <tab-view>
      <tab-view-tabs role="tablist" style={{ paddingLeft: indent }}>
        {sections.map((section) => (
          <button
            key={section.key}
            type="button"
            role="tab"
            className="tab-view-tab"
            aria-selected={section.key === activeKey}
            onClick={() => selectTab(section.key)}
          >
            {section.label}
          </button>
        ))}
      </tab-view-tabs>
      <tab-view-panel role="tabpanel" data-border={borderStyle} style={{ padding: panelPadding }}>
        {activeSection?.view}
      </tab-view-panel>
    </tab-view>
  )
}

export default TabView
