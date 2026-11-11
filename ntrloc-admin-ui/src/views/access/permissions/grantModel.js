// Pure logic over permissions and groups. A principal's grants are a list of permissions (the grants
// API's shape), each described by:
//   { kind: 'itemType', itemTypeId, op }                     op: 'read' | 'create'
//   { kind: 'marker', markerId, area: 'item', op }           op: 'read' | 'delete'
//   { kind: 'marker', markerId, area: 'property', targetId, op }     op: 'read' | 'write'
//   { kind: 'marker', markerId, area: 'linkProperty', targetId, op } op: 'read' | 'write'
//   { kind: 'marker', markerId, area: 'perspective', targetId, op }  op: 'read' | 'create' | 'delete'
//   { kind: 'marker', markerId, area: 'transition', targetId }
//   { kind: 'marker', markerId, area: 'start', targetId }

// One string per permission, so a principal's permissions can be a Set. Absent and null fields are
// the same.
export function permissionKey(p) {
  return [p.kind, p.itemTypeId, p.markerId, p.area, p.targetId, p.op].map((v) => v ?? '').join('|')
}

export function permissionKeys(permissions) {
  return new Set(permissions.map(permissionKey))
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

// Every group below this one, nearest first.
export function subgroupsOf(groupId, groups) {
  const below = []
  let level = [groupId]
  while (level.length > 0) {
    const next = groups.filter((g) => g.parentIds.some((p) => level.includes(p)))
    below.push(...next)
    level = next.map((g) => g.id)
  }
  return below
}

// For one permission: whether this principal has it directly, and which groups give it.
// `directKeys` is the principal's own permissionKeys; `groupKeys` is [{ group, keys }].
export function permissionSources(permission, directKeys, groupKeys) {
  const key = permissionKey(permission)
  return {
    direct: directKeys.has(key),
    groups: groupKeys.filter(({ keys }) => keys.has(key)).map(({ group }) => group),
  }
}

const NO_ENTRIES = []

// The mark a set of changes earns: "reconcile" if live has moved underneath any of them, "modified" if
// there are any, null if none.
export function markOf(entries) {
  if (entries.length === 0) return null
  return entries.some((e) => e.reconcile.length > 0) ? 'reconcile' : 'modified'
}

// A draft's grant changes ([{ principal, permission, value, reconcile }]), for the trail of marks: the
// mark for a kind of principal and for one principal, and each principal's changes.
export function indexGrantChanges(grants) {
  const byPrincipal = new Map()
  for (const change of grants) {
    const key = `${change.principal.kind}:${change.principal.id}`
    if (!byPrincipal.has(key)) byPrincipal.set(key, [])
    byPrincipal.get(key).push(change)
  }
  const changesOf = (principal) => byPrincipal.get(`${principal.kind}:${principal.id}`) ?? NO_ENTRIES
  return {
    kindMark: (kind) => markOf(grants.filter((g) => g.principal.kind === kind)),
    principalMark: (principal) => markOf(changesOf(principal)),
    changesOf,
  }
}

const capitalised = (word) => (word ? word[0].toUpperCase() + word.slice(1) : '')

// One permission in words, against what can be granted (the grants API's targets): e.g.
// "Photographer: Create", or "Photographer · Default read · Property firstName: Read".
export function describePermission(p, targets) {
  const op = capitalised(p.op)
  if (p.kind === 'itemType') {
    const itemType = targets.itemTypes.find((t) => t.id === p.itemTypeId)
    return `${itemType?.name ?? 'A deleted item type'}: ${op}`
  }
  const marker = targets.markers.find((m) => m.id === p.markerId)
  if (!marker) return `A deleted marker: ${op || capitalised(p.area)}`
  const itemType = targets.itemTypes.find((t) => t.id === marker.scopeId)
  const prefix = `${itemType?.name ?? '?'} · ${marker.name}`
  const named = (list) => list?.find((x) => x.id === p.targetId)?.name ?? 'something deleted'
  const linkProperties = itemType?.perspectives.flatMap((lp) => lp.properties ?? [])
  const machines = itemType?.stateMachines ?? []
  switch (p.area) {
    case 'item':
      return `${prefix} · Items: ${op}`
    case 'property':
      return `${prefix} · Property ${named(itemType?.properties)}: ${op}`
    case 'linkProperty':
      return `${prefix} · Link property ${named(linkProperties)}: ${op}`
    case 'perspective':
      return `${prefix} · Link ${named(itemType?.perspectives)}: ${op}`
    case 'transition':
      return `${prefix} · Transition ${named(machines.flatMap((m) => m.transitions))}`
    case 'start':
      return `${prefix} · Start ${named(machines)}`
    default:
      return prefix
  }
}

// Why a change needs reconciling, in words (GrantDependencyTracker.Kind on the server).
export function describeReason(reason, principalKind) {
  const who = principalKind === 'user' ? 'This user' : 'This group'
  switch (reason.kind) {
    case 'PRINCIPAL_DELETED':
      return `${who} has been deleted in live.`
    case 'TARGET_DELETED':
      return 'What it grants on has been deleted in live.'
    case 'PRINCIPAL_CHANGED':
      return principalKind === 'user'
        ? 'This user has changed in live: their groups, details or admin rights.'
        : 'This group has changed in live: moved, renamed, or its members or subgroups changed.'
    case 'GROUP_CHANGED':
      return principalKind === 'user'
        ? `Their groups have changed in live (${reason.name ?? 'a group'}).`
        : `${reason.name ?? 'A group'}, above it, has been moved in live.`
    case 'GROUP_DELETED':
      return 'A group it depended on has been deleted in live.'
    case 'INHERITED_CHANGED':
      return 'Live now gives the same permission through a parent group, or no longer does.'
    default:
      return 'Live has changed underneath it.'
  }
}

const RANK = { modified: 1, notice: 2, reconcile: 3 }

// The mark to show where several apply: needs reconciling, then changed in live, then changed in the draft.
export function strongestMark(...marks) {
  return marks.reduce((a, b) => ((RANK[b] ?? 0) > (RANK[a] ?? 0) ? b : a), null)
}

// What a live-change notice (DraftNotice: { latest, changes }) concerns in the Access view: the users and
// groups it's about, and for each, the tab it belongs to ('overview', 'membership' or 'grants').
function noticeTargets(notice) {
  const [type, ...rest] = notice.latest.elementKey.split(':')
  switch (type) {
    case 'group':
      return [{ kind: 'group', id: rest[0], area: 'overview' }]
    case 'user':
      return [{ kind: 'user', id: rest[0], area: 'overview' }]
    case 'membership':
      return [
        { kind: 'group', id: rest[0], area: 'overview' },
        { kind: 'user', id: rest[1], area: 'membership' },
      ]
    case 'grant':
      return [{ kind: rest[0] === 'USER' ? 'user' : 'group', id: rest[1], area: 'grants' }]
    default:
      return []
  }
}

// Where Show takes a notice: the user or group it's about first, on its tab ('overview', 'groups' or
// 'permissions', the detail views' tab keys). Null for one with nowhere to show (a deleted marker).
export function noticeFocus(notice) {
  const target = noticeTargets(notice)[0]
  if (!target) return null
  const tab = { overview: 'overview', membership: 'groups', grants: 'permissions' }[target.area]
  return { kind: target.kind, id: target.id, tab }
}

// The grant a 'grant' notice is about (granted or revoked).
export function noticePermission(notice) {
  return notice.latest.after?.permission ?? notice.latest.before?.permission ?? null
}

// A draft's live-change notices, for the trail of marks and the details panels.
export function indexNotices(notices) {
  const entries = notices.map((notice) => ({ notice, targets: noticeTargets(notice) }))
  const about = (principal) =>
    entries.filter((e) => e.targets.some((t) => t.kind === principal.kind && t.id === principal.id))
  const mark = (found) => (found ? 'notice' : null)
  const grantNoticesOf = (principal) =>
    about(principal)
      .filter((e) => e.targets.some((t) => t.id === principal.id && t.area === 'grants'))
      .map((e) => noticePermission(e.notice))
      .filter(Boolean)
  return {
    any: notices.length > 0,
    kindMark: (kind) => mark(entries.some((e) => e.targets.some((t) => t.kind === kind))),
    principalMark: (principal) => mark(about(principal).length > 0),
    areaMark: (principal, area) => mark(about(principal).some((e) => e.targets.some((t) => t.id === principal.id && t.area === area))),
    noticesFor: (principal) => about(principal).map((e) => e.notice),
    permissionMark: (principal, permission) => mark(grantNoticesOf(principal).some((p) => permissionKey(p) === permissionKey(permission))),
    markerMark: (principal, markerId) => mark(grantNoticesOf(principal).some((p) => p.markerId === markerId)),
    itemTypeMark: (principal, itemTypeId, markers) =>
      mark(grantNoticesOf(principal).some((p) => p.itemTypeId === itemTypeId || markers.some((mk) => mk.id === p.markerId))),
  }
}

// A live-change notice in words. `names`: { users, groups, targets } as the Access screens load them.
export function describeNotice(notice, names) {
  const { kind, before, after } = notice.latest
  const group = (id, fallback) => names.groups.find((g) => g.id === id)?.name ?? fallback ?? 'a deleted group'
  const user = (id, fallback) => names.users.find((u) => u.id === id)?.displayName ?? fallback ?? 'a deleted user'
  const principal = (p) => (p.principalType === 'USER' ? user(p.principalId) : group(p.principalId))
  switch (kind) {
    case 'group.created':
      return `Group ${after.name} was created${after.parentName ? ` under ${after.parentName}` : ''}.`
    case 'group.renamed':
      return `Group ${before.name} was renamed ${after.name}.`
    case 'group.moved':
      return `${after.name} was moved from ${before.parentName ?? 'the top'} to ${after.parentName}.`
    case 'group.deleted':
      return `Group ${before.name} was deleted, with its grants.`
    case 'membership.added':
      return `${after.userName} was added to ${after.groupName}.`
    case 'membership.removed':
      return `${before.userName} was removed from ${before.groupName}.`
    case 'user.created':
      return `User ${after.displayName} was created.`
    case 'user.updated':
      if ('isAdmin' in after) return `${after.displayName} ${after.isAdmin ? 'was made an admin' : 'is no longer an admin'}.`
      return `${after.displayName}'s details were changed.`
    case 'user.deleted':
      return `User ${before.displayName} was deleted, with their grants.`
    case 'grant.granted':
      return `${principal(after)} was granted ${describePermission(after.permission, names.targets)}.`
    case 'grant.revoked':
      return `${principal(before)} lost ${describePermission(before.permission, names.targets)}.`
    case 'marker.deleted':
      return `Marker ${before.name} was deleted, with its grants.`
    default:
      return 'Something changed.'
  }
}

// Who or what made a notice's latest change, and when: "by Pat · 10:42" or "system (startup) · 10:42".
export function describeNoticeOrigin(notice) {
  const { actorName, source, at } = notice.latest
  const when = new Date(at).toLocaleString()
  const who = actorName ? `by ${actorName}${source ? ` (${source})` : ''}` : `system${source ? ` (${source})` : ''}`
  const more = notice.changes > 1 ? ` · ${notice.changes} changes` : ''
  return `${who} · ${when}${more}`
}
