// One shared EventSource for the whole app, opened only once the page has fully finished loading
// (window 'load', not on React mount) so it never competes with other page resources for one of the
// browser's limited per-origin HTTP/1.1 connections while the page is still coming up.
//
// Event types are hardcoded for now (just schemaChanged) -- the eventual design has this list built
// from the union of the core UI's own needs plus whatever admin-ui-extensions declare wanting, but
// that's not built yet; see ClusterEventController on the backend for the server side of this same
// "start simple" step.
import { logInfo } from '../log'

const EVENT_TYPES = ['schemaChanged']

let eventSource = null
const listenersByType = new Map()

function connect() {
  if (eventSource) return
  eventSource = new EventSource(`/api/events?types=${EVENT_TYPES.join(',')}`)
  // Events sent while not connected (before the first open, or while EventSource is reconnecting)
  // are never replayed, so every (re)connect tells each listener to refresh, as if it had missed one.
  eventSource.addEventListener('open', () => {
    logInfo('Event stream connected')
    for (const listeners of listenersByType.values()) listeners.forEach((listener) => listener(null))
  })
  // EventSource reconnects by itself; its 'open' then refreshes whatever was missed meanwhile.
  eventSource.addEventListener('error', () => logInfo('Event stream disconnected'))
  for (const type of EVENT_TYPES) {
    eventSource.addEventListener(type, (event) => {
      logInfo(`${type} event received`)
      const listeners = listenersByType.get(type)
      if (listeners) listeners.forEach((listener) => listener(event.data))
    })
  }
}

// document.readyState is already 'complete' if 'load' fired before this module ran (e.g. a slow
// script evaluation, or a Vite HMR re-run) -- the event only fires once, so a plain addEventListener
// would silently never connect in that case.
if (document.readyState === 'complete') {
  connect()
} else {
  window.addEventListener('load', connect)
}

export function onSchemaChanged(listener) {
  if (!listenersByType.has('schemaChanged')) listenersByType.set('schemaChanged', new Set())
  const listeners = listenersByType.get('schemaChanged')
  listeners.add(listener)
  return () => listeners.delete(listener)
}
