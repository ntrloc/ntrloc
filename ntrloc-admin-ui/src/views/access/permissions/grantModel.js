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

// For one permission: whether this principal has it directly, and which groups give it.
// `directKeys` is the principal's own permissionKeys; `groupKeys` is [{ group, keys }].
export function permissionSources(permission, directKeys, groupKeys) {
  const key = permissionKey(permission)
  return {
    direct: directKeys.has(key),
    groups: groupKeys.filter(({ keys }) => keys.has(key)).map(({ group }) => group),
  }
}
