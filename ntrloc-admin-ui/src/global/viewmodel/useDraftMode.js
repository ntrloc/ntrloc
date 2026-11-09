import { createContext, useContext } from 'react'

// See DraftModeProvider (DraftModeContext.jsx), which supplies this.
export const DraftModeContext = createContext(null)

export function useDraftMode() {
  const context = useContext(DraftModeContext)
  if (!context) throw new Error('useDraftMode must be used inside DraftModeProvider')
  return context
}
