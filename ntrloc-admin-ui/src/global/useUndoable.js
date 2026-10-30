import { useCallback, useState } from 'react'

// A value with an undo history, for an editor's draft. Each change is a step to undo, except:
// - change(fn, key): consecutive changes with the same key make one step (e.g. typing into a field);
// - startGesture(): a drag's changes, made with its update(fn) as it goes, make one step when it
//   end()s -- none, if it ended where it started.
// Undoing then making a change drops what could have been redone.
export function useUndoable(initial) {
  const [history, setHistory] = useState(() => ({ past: [], present: initial(), future: [], key: null }))

  const change = useCallback((fn, key = null) => {
    setHistory((h) => {
      const present = fn(h.present)
      if (present === h.present) return h
      const merge = key !== null && key === h.key
      return { past: merge ? h.past : [...h.past, h.present], present, future: [], key }
    })
  }, [])

  const undo = useCallback(() => {
    setHistory((h) =>
      h.past.length === 0 ? h : { past: h.past.slice(0, -1), present: h.past.at(-1), future: [h.present, ...h.future], key: null },
    )
  }, [])

  const redo = useCallback(() => {
    setHistory((h) =>
      h.future.length === 0 ? h : { past: [...h.past, h.present], present: h.future[0], future: h.future.slice(1), key: null },
    )
  }, [])

  function startGesture() {
    const base = history.present
    return {
      update: (fn) => setHistory((h) => ({ ...h, present: fn(base) })),
      end: () =>
        setHistory((h) => (h.present === base ? h : { past: [...h.past, base], present: h.present, future: [], key: null })),
    }
  }

  return {
    present: history.present,
    change,
    startGesture,
    undo,
    redo,
    canUndo: history.past.length > 0,
    canRedo: history.future.length > 0,
  }
}
