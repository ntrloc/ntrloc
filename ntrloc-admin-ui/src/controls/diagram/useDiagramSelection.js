import { useState } from 'react'

// What's selected in a diagram editor -- { kind, id }, the kinds being the diagram's own (a state, a
// transition, a block...) -- or null.
export function useDiagramSelection() {
  const [selection, setSelection] = useState(null)
  return {
    selection,
    select: (kind, id) => setSelection({ kind, id }),
    clear: () => setSelection(null),
    isSelected: (kind, id) => selection?.kind === kind && selection.id === id,
  }
}
