import { useEffect, useState } from 'react'

const DEFAULT_DELAY_MS = 2000

// Avoids spinner flicker on fast delegate calls -- only reports loading once it's
// stayed true for longer than delayMs, and clears immediately once it goes false.
// oxlint's react(set-state-in-effect) flags the immediate-false branch below; that
// rule is aimed at effects that just mirror a prop into state for no reason, which
// isn't this -- the effect also owns the timer, so it can't be replaced by deriving
// state during render.
export function useDelayedLoading(isLoading, delayMs = DEFAULT_DELAY_MS) {
  const [showSpinner, setShowSpinner] = useState(false)

  useEffect(() => {
    if (!isLoading) {
      // oxlint-disable-next-line react/set-state-in-effect -- see file comment above
      setShowSpinner(false)
      return
    }
    const timer = setTimeout(() => setShowSpinner(true), delayMs)
    return () => clearTimeout(timer)
  }, [isLoading, delayMs])

  return showSpinner
}
