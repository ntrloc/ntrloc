// Pure logic over grants (the shape in mockAccessData.js): reading one permission out of a list of
// grants, setting it, and working out a user's effective permissions with where each comes from.
//
// A permission is described by:
//   { kind: 'itemType', itemTypeId, op }                     op: 'read' | 'create'
//   { kind: 'marker', markerId, area: 'item', op }           op: 'read' | 'delete'
//   { kind: 'marker', markerId, area: 'property', targetId, op }     op: 'read' | 'write'
//   { kind: 'marker', markerId, area: 'perspective', targetId, op }  op: 'read' | 'create' | 'delete'
//   { kind: 'marker', markerId, area: 'transition', targetId }
//   { kind: 'marker', markerId, area: 'start', targetId }

function samePrincipal(a, b) {
  return a.kind === b.kind && a.id === b.id
}

function findGrant(grants, principal, permission) {
  return grants.find((g) =>
    samePrincipal(g.principal, principal) && g.kind === permission.kind &&
    (permission.kind === 'itemType' ? g.itemTypeId === permission.itemTypeId : g.markerId === permission.markerId),
  )
}

// Whether a single grant gives this permission.
export function grantGives(grant, permission) {
  if (grant.kind !== permission.kind) return false
  if (permission.kind === 'itemType') {
    return grant.itemTypeId === permission.itemTypeId && grant.operations.includes(permission.op)
  }
  if (grant.markerId !== permission.markerId) return false
  switch (permission.area) {
    case 'item':
      return Boolean(grant.item?.[permission.op])
    case 'property':
      return Boolean(grant.properties?.[permission.targetId]?.[permission.op])
    case 'perspective':
      return Boolean(grant.perspectives?.[permission.targetId]?.[permission.op])
    case 'transition':
      return grant.transitions?.includes(permission.targetId) ?? false
    case 'start':
      return grant.stateMachineStarts?.includes(permission.targetId) ?? false
    default:
      return false
  }
}

function emptyMarkerGrant(principal, markerId) {
  return {
    principal,
    kind: 'marker',
    markerId,
    item: { read: false, delete: false },
    properties: {},
    perspectives: {},
    transitions: [],
    stateMachineStarts: [],
  }
}

function toggleIn(list, id, on) {
  const without = list.filter((x) => x !== id)
  return on ? [...without, id] : without
}

// Grants with this principal's permission set on or off -- a new list, the input untouched.
export function setPermission(grants, principal, permission, on) {
  const existing = findGrant(grants, principal, permission)
  let grant
  if (permission.kind === 'itemType') {
    grant = existing
      ? { ...existing, operations: toggleIn(existing.operations, permission.op, on) }
      : { principal, kind: 'itemType', itemTypeId: permission.itemTypeId, operations: on ? [permission.op] : [] }
  } else {
    grant = structuredClone(existing ?? emptyMarkerGrant(principal, permission.markerId))
    switch (permission.area) {
      case 'item':
        grant.item[permission.op] = on
        break
      case 'property':
        grant.properties[permission.targetId] = { read: false, write: false, ...grant.properties[permission.targetId], [permission.op]: on }
        break
      case 'perspective':
        grant.perspectives[permission.targetId] = {
          read: false, create: false, delete: false, ...grant.perspectives[permission.targetId], [permission.op]: on,
        }
        break
      case 'transition':
        grant.transitions = toggleIn(grant.transitions, permission.targetId, on)
        break
      case 'start':
        grant.stateMachineStarts = toggleIn(grant.stateMachineStarts, permission.targetId, on)
        break
    }
  }
  const others = grants.filter((g) => g !== existing)
  return isEmpty(grant) ? others : [...others, grant]
}

function isEmpty(grant) {
  if (grant.kind === 'itemType') return grant.operations.length === 0
  const anyTrue = (o) => Object.values(o ?? {}).some(Boolean)
  return !anyTrue(grant.item) &&
    !Object.values(grant.properties).some(anyTrue) &&
    !Object.values(grant.perspectives).some(anyTrue) &&
    grant.transitions.length === 0 &&
    grant.stateMachineStarts.length === 0
}

// Every group a user belongs to: their direct groups and, through each, its ancestors.
export function groupClosure(directGroupIds, groups) {
  const byId = new Map(groups.map((g) => [g.id, g]))
  const seen = new Set()
  const visit = (id) => {
    if (seen.has(id) || !byId.has(id)) return
    seen.add(id)
    byId.get(id).parentIds.forEach(visit)
  }
  directGroupIds.forEach(visit)
  return [...seen].map((id) => byId.get(id))
}

// Groups as a tree for CheckboxTree ({ key, label, children }): roots are groups with no parent, and
// each group sits under every parent it has, so a group with two parents appears twice. `extra(group)`
// adds fields to a group's node (e.g. disabled).
export function groupHierarchy(groups, extra = () => ({})) {
  const node = (group, path) => ({
    key: group.id,
    label: group.name,
    ...extra(group),
    children: groups
      .filter((g) => g.parentIds.includes(group.id) && !path.has(g.id))
      .map((g) => node(g, new Set(path).add(g.id))),
  })
  return groups.filter((g) => g.parentIds.length === 0).map((g) => node(g, new Set([g.id])))
}

// For one permission: whether this principal has it directly, and which groups give it.
export function permissionSources(permission, directGrants, groupGrants) {
  return {
    direct: directGrants.some((g) => grantGives(g, permission)),
    groups: groupGrants.filter(({ grant }) => grantGives(grant, permission)).map(({ group }) => group),
  }
}
