import { useCallback, useEffect, useMemo, useState } from 'react'
import { fetchGrants, fetchGrantTargets, setGrantPermission } from '../AccessDelegate'
import { permissionSources } from './grantModel'

// A principal's permissions (a user's or a group's): the item types and markers there are to grant
// against, the principal's own grants (editable), and the grants it inherits from `inheritedFrom`
// groups (shown, not editable here). Grants are drafted, so this stays editable in draft mode.
export function usePermissionsViewModel(principal, inheritedFrom) {
  const [targets, setTargets] = useState(null)
  const [grants, setGrants] = useState(null)
  const [error, setError] = useState(null)
  const [openOverrides, setOpenOverrides] = useState(() => new Map())
  const [expandedMarkerId, setExpandedMarkerId] = useState(null)

  useEffect(() => {
    Promise.all([fetchGrantTargets(), fetchGrants()])
      .then(([loadedTargets, loadedGrants]) => {
        setTargets(loadedTargets)
        setGrants(loadedGrants)
      })
      .catch((e) => setError(e.message))
  }, [])

  const inheritedIds = inheritedFrom.map((g) => g.id).join()
  const { directGrants, groupGrants } = useMemo(() => {
    if (!grants) return { directGrants: [], groupGrants: [] }
    const byId = new Map(inheritedFrom.map((g) => [g.id, g]))
    return {
      directGrants: grants.filter((g) => g.principal.kind === principal.kind && g.principal.id === principal.id),
      groupGrants: grants
        .filter((g) => g.principal.kind === 'group' && byId.has(g.principal.id))
        .map((grant) => ({ group: byId.get(grant.principal.id), grant })),
    }
    // inheritedIds stands in for inheritedFrom, which is a new array on every render.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [grants, principal.kind, principal.id, inheritedIds])

  const sourcesOf = useCallback((permission) => permissionSources(permission, directGrants, groupGrants), [directGrants, groupGrants])

  // Item types with anything granted start open, the rest closed; after that, each card remembers
  // being opened or closed.
  const itemTypeOpen = (itemTypeId, hasAny) => openOverrides.get(itemTypeId) ?? hasAny
  function toggleItemType(itemTypeId, hasAny) {
    setOpenOverrides((current) => new Map(current).set(itemTypeId, !(current.get(itemTypeId) ?? hasAny)))
  }

  async function toggle(permission) {
    setError(null)
    try {
      await setGrantPermission(principal, permission, !sourcesOf(permission).direct)
      setGrants(await fetchGrants())
    } catch (e) {
      setError(e.message)
    }
  }

  return {
    ready: targets !== null && grants !== null,
    error,
    targets,
    sourcesOf,
    toggle,
    itemTypeOpen,
    toggleItemType,
    expandedMarkerId,
    toggleMarker: (markerId) => setExpandedMarkerId((current) => (current === markerId ? null : markerId)),
  }
}
