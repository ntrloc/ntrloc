import { useState } from 'react'

// Owns which tab is active -- split out from TabView.jsx for the same view/view-model split used
// elsewhere (e.g. useSidebarViewModel): keeps the state and its rules readable and testable
// independent of the markup that renders them. Uncontrolled by default (own internal state), but
// accepts an optional activeKey/onActiveKeyChange pair for a consumer that needs this tab to
// survive somewhere TabView itself doesn't -- e.g. ItemTypeDetailView unmounting when the schema
// editor's accordion switches to a different section and back.
export function useTabViewModel(sections, controlledActiveKey, onActiveKeyChange) {
  const [internalActiveKey, setInternalActiveKey] = useState(sections[0]?.key)
  const isControlled = controlledActiveKey !== undefined
  const activeKey = isControlled ? controlledActiveKey : internalActiveKey
  const activeSection = sections.find((section) => section.key === activeKey) ?? sections[0]

  function selectTab(key) {
    if (!isControlled) setInternalActiveKey(key)
    onActiveKeyChange?.(key)
  }

  return { activeKey, activeSection, selectTab }
}
