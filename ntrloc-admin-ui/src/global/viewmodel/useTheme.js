import { useCallback, useState } from 'react'

// Must match index.html's own inline script, which applies whatever's stored here to <html>'s
// data-theme attribute before first paint (see its own comment) -- this hook never needs to do that
// itself on mount, only react to further toggling.
const STORAGE_KEY = 'theme'

function systemPrefersDark() {
  return window.matchMedia('(prefers-color-scheme: dark)').matches
}

// The current *effective* theme ('light' | 'dark') and a way to flip it -- index.css's own
// :root[data-theme='...'] rules (see that file) are what actually repaint the page; this hook's job
// is only to read where things already stand (data-theme if ever set, else the OS preference) and
// keep the DOM attribute + localStorage + this component's own re-render in sync when toggled.
// Deliberately a plain two-state toggle, not a three-way system/light/dark picker -- once touched,
// an explicit choice always wins over the OS preference from then on; there's no "back to system
// default" affordance because AccountView's own switch (see that file) doesn't offer one.
export function useTheme() {
  const [theme, setTheme] = useState(
    () => document.documentElement.getAttribute('data-theme') || (systemPrefersDark() ? 'dark' : 'light'),
  )

  const toggleTheme = useCallback(() => {
    setTheme((prev) => {
      const next = prev === 'dark' ? 'light' : 'dark'
      document.documentElement.setAttribute('data-theme', next)
      localStorage.setItem(STORAGE_KEY, next)
      return next
    })
  }, [])

  return { theme, toggleTheme }
}
