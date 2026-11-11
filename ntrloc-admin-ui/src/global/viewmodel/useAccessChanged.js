import { useEffect, useRef } from 'react'
import { onAccessChanged } from '../delegate/ClusterEventDelegate'

// Calls `reload` whenever users, groups, memberships or grants change in live (pushed from the server),
// so a view never shows what another tab, or another admin, has since changed.
export function useAccessChanged(reload) {
  const latest = useRef(reload)
  useEffect(() => {
    latest.current = reload
  })
  useEffect(() => onAccessChanged(() => latest.current()), [])
}
