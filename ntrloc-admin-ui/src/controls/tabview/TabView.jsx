import { useTabViewModel } from './TabViewModel'
import './TabView.css'

// A horizontal tab strip -- one section's view shown at a time, switched by clicking its tab.
// Distinct from Accordion (vertical, every tab always visible as a full-width bar, the active
// section's panel expands in place beneath it): TabView's tabs sit in a single row and only the
// active section's view is ever rendered.
function TabView({ sections }) {
  const { activeKey, activeSection, selectTab } = useTabViewModel(sections)

  return (
    <tab-view>
      <tab-view-tabs role="tablist">
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
      <tab-view-panel role="tabpanel">{activeSection?.view}</tab-view-panel>
    </tab-view>
  )
}

export default TabView
