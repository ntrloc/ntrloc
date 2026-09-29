import { useState } from 'react'

// Owns which tab is active -- split out from TabView.jsx for the same view/view-model split used
// elsewhere (e.g. useSidebarViewModel): keeps the state and its rules readable and testable
// independent of the markup that renders them.
export function useTabViewModel(sections) {
  const [activeKey, setActiveKey] = useState(sections[0]?.key)
  const activeSection = sections.find((section) => section.key === activeKey) ?? sections[0]

  return { activeKey, activeSection, selectTab: setActiveKey }
}
