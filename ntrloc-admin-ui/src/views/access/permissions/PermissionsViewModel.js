import { useCallback, useEffect, useMemo, useState } from 'react'
import { useDraftMode } from '../../../global/viewmodel/useDraftMode'
import { fetchGrantTargets, fetchPermissions, setPermission } from '../AccessDelegate'
import { permissionKey, permissionKeys, permissionSources } from './grantModel'
import { useGrantChanges } from './useGrantChanges'

// A principal's permissions (a user's or a group's): the item types and markers there are to grant
// against, the principal's own grants (editable), and the grants it inherits from `inheritedFrom`
// groups (shown, not editable here). In draft mode, all of it is the draft's, and what the draft
// changes for this principal is marked (changed, markerChanged, itemTypeChanged).
export function usePermissionsViewModel(principal, inheritedFrom) {
  const { draft, loadDrafts, reloadChanges } = useDraftMode()
  const draftId = draft?.id ?? null
  const [targets, setTargets] = useState(null)
  const [loaded, setLoaded] = useState(null)
  const [error, setError] = useState(null)
  const [openOverrides, setOpenOverrides] = useState(() => new Map())
  const [expandedMarkerId, setExpandedMarkerId] = useState(null)

  // inheritedIds stands in for inheritedFrom, which is a new array on every render.
  const inheritedIds = inheritedFrom.map((g) => g.id).join()
  const loadPermissions = useCallback(
    () => fetchPermissions(draftId, [principal, ...inheritedFrom.map((g) => ({ kind: 'group', id: g.id }))]),
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [draftId, principal.kind, principal.id, inheritedIds],
  )

  // A draft request that fails may mean the draft has gone (applied, discarded); rechecking the drafts
  // sends this tab back to live if so.
  const fail = useCallback(
    (e) => {
      setError(e.message)
      if (draftId) void loadDrafts()
    },
    [draftId, loadDrafts],
  )

  useEffect(() => {
    fetchGrantTargets(draftId).then(setTargets).catch(fail)
  }, [draftId, fail])

  useEffect(() => {
    let current = true
    loadPermissions()
      .then((result) => {
        if (current) {
          setError(null)
          setLoaded(result)
        }
      })
      .catch((e) => current && fail(e))
    return () => {
      current = false
    }
  }, [loadPermissions, fail])

  const { directKeys, groupKeys } = useMemo(() => {
    if (!loaded) return { directKeys: new Set(), groupKeys: [] }
    const [own, ...inherited] = loaded
    const byId = new Map(inheritedFrom.map((g) => [g.id, g]))
    return {
      directKeys: permissionKeys(own.permissions),
      groupKeys: inherited.filter((p) => byId.has(p.principal.id)).map((p) => ({ group: byId.get(p.principal.id), keys: permissionKeys(p.permissions) })),
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [loaded, inheritedIds])

  const changes = useGrantChanges()
  const changedPermissions = changes.changedOf(principal)
  const changedKeys = useMemo(() => permissionKeys(changedPermissions), [changedPermissions])

  const sourcesOf = useCallback((permission) => permissionSources(permission, directKeys, groupKeys), [directKeys, groupKeys])

  // Item types with anything granted start open, the rest closed; after that, each card remembers
  // being opened or closed.
  const itemTypeOpen = (itemTypeId, hasAny) => openOverrides.get(itemTypeId) ?? hasAny
  function toggleItemType(itemTypeId, hasAny) {
    setOpenOverrides((current) => new Map(current).set(itemTypeId, !(current.get(itemTypeId) ?? hasAny)))
  }

  async function toggle(permission) {
    setError(null)
    try {
      await setPermission(draftId, principal, permission, !sourcesOf(permission).direct)
      setLoaded(await loadPermissions())
      if (draftId) void reloadChanges()
    } catch (e) {
      fail(e)
    }
  }

  return {
    ready: targets !== null && loaded !== null,
    error,
    targets,
    sourcesOf,
    changed: (permission) => changedKeys.has(permissionKey(permission)),
    markerChanged: (markerId) => changedPermissions.some((p) => p.markerId === markerId),
    // An item type's card holds its own permissions and those of its markers.
    itemTypeChanged: (itemTypeId, markers) =>
      changedPermissions.some((p) => p.itemTypeId === itemTypeId || markers.some((mk) => mk.id === p.markerId)),
    toggle,
    itemTypeOpen,
    toggleItemType,
    expandedMarkerId,
    toggleMarker: (markerId) => setExpandedMarkerId((current) => (current === markerId ? null : markerId)),
  }
}
