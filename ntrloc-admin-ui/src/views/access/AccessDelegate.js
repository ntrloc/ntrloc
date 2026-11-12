// Every call the Access screens make: users, groups and membership (/api/admin/users,
// /api/admin/user-groups) and grants (/api/admin/grants); groups, membership and grants at
// /api/drafts/{id}/admin/... in a draft.
import { draftAddress } from '../../global/delegate/draftAddress'

async function readErrorMessage(res, fallback) {
  const body = await res.json().catch(() => null)
  return body?.message || body?.reason || fallback
}

async function request(method, url, what, body) {
  const res = await fetch(url, {
    method,
    credentials: 'same-origin',
    headers: body === undefined ? undefined : { 'Content-Type': 'application/json' },
    body: body === undefined ? undefined : JSON.stringify(body),
  })
  if (!res.ok) throw new Error(await readErrorMessage(res, `Failed to ${what} (${res.status})`))
  return res.status === 204 ? null : res.json()
}

const get = (url, what) => request('GET', url, what)

// --- Users ---

// signIn isn't in the API yet; until it is, it's unknown (null).
const withSignIn = (user) => ({ signIn: null, ...user })

export async function fetchUsers() {
  const users = await get('/api/admin/users', 'fetch users')
  return users.map(withSignIn).sort((a, b) => a.displayName.localeCompare(b.displayName))
}

// Groups and memberships: each call takes the draft to work in (its id), or null for live. Users
// themselves are always live.
const groupsBase = (draftId) => (draftId ? draftAddress(draftId, 'admin') : '/api/admin')

// One user, with what the Info / Tokens tab shows alongside: their tokens and the groups they're
// directly in (in the draft, if any).
export async function fetchUserDetail(draftId, userId) {
  const [users, tokens, memberships, groups] = await Promise.all([
    get('/api/admin/users', 'fetch the user'),
    get(`/api/admin/users/${userId}/tokens`, "fetch the user's tokens"),
    get(`${groupsBase(draftId)}/users/${userId}/user-groups`, "fetch the user's groups"),
    fetchGroups(draftId),
  ])
  const user = users.find((u) => u.id === userId)
  if (!user) throw new Error('No such user')
  const direct = new Set(memberships.map((m) => m.id))
  return { user: withSignIn(user), tokens, groups: groups.filter((g) => direct.has(g.id)) }
}

export function updateUser(userId, { displayName, email, isAdmin }) {
  return request('PUT', `/api/admin/users/${userId}`, 'update the user', { displayName, email, role: isAdmin ? 'ADMIN' : 'USER' })
}

export function resetPassword(userId, newPassword) {
  return request('PUT', `/api/admin/users/${userId}/password`, 'reset the password', { newPassword })
}

// Returns the new token with its raw value -- the only time it's ever shown.
export function createToken(userId, { name, expiresInDays }) {
  return request('POST', `/api/admin/users/${userId}/tokens`, 'create the token', { name, expiresInDays })
}

export function revokeToken(userId, tokenId) {
  return request('DELETE', `/api/admin/users/${userId}/tokens/${tokenId}`, 'revoke the token')
}

// --- Groups ---

const byName = (groups) => groups.sort((a, b) => a.name.localeCompare(b.name))

// The groups, each { id, name, memberCount, parentIds }. In a draft, the live groups it deletes are
// included too, marked deleted: true, where live has them, so they can be shown and restored.
export async function fetchGroups(draftId) {
  if (!draftId) return byName(await get('/api/admin/user-groups', 'fetch groups'))
  const [groups, deleted] = await Promise.all([
    get(`${groupsBase(draftId)}/user-groups`, 'fetch groups'),
    get(`${groupsBase(draftId)}/user-groups/deleted`, 'fetch deleted groups'),
  ])
  return byName([...groups, ...deleted.map((g) => ({ ...g, deleted: true }))])
}

// One group, with its direct members (none listed for a group the draft deletes).
export async function fetchGroupDetail(draftId, groupId) {
  const groups = await fetchGroups(draftId)
  const group = groups.find((g) => g.id === groupId)
  if (!group) throw new Error('No such group')
  if (group.deleted) return { group, members: [] }
  const members = await get(`${groupsBase(draftId)}/user-groups/${groupId}/members`, 'fetch members')
  return { group, members: members.sort((a, b) => a.displayName.localeCompare(b.displayName)) }
}

export function renameGroup(draftId, groupId, name) {
  return request('PUT', `${groupsBase(draftId)}/user-groups/${groupId}`, 'rename the group', { name })
}

// A new group under parentId. Returns it as { id, name, memberCount, parentIds }.
export function createGroup(draftId, name, parentId) {
  return request('POST', `${groupsBase(draftId)}/user-groups`, 'create the group', { name, parentUserGroupId: parentId })
}

// A group with subgroups is refused unless force is true, which deletes them too. Grants go with them.
export function deleteGroup(draftId, groupId, force) {
  return request('DELETE', `${groupsBase(draftId)}/user-groups/${groupId}${force ? '?force=true' : ''}`, 'delete the group')
}

// Brings back a group the draft deletes (drafts only).
export function restoreGroup(draftId, groupId) {
  return request('POST', `${groupsBase(draftId)}/user-groups/${groupId}/restore`, 'restore the group')
}

// One parent, replacing the old one.
export function setGroupParent(draftId, groupId, parentId) {
  return request('PUT', `${groupsBase(draftId)}/user-groups/${groupId}/parent`, "change the group's parent", { parentUserGroupId: parentId })
}

export function addUserToGroup(draftId, userId, groupId) {
  return request('POST', `${groupsBase(draftId)}/user-groups/${groupId}/members`, 'add to the group', { userId })
}

export function removeUserFromGroup(draftId, userId, groupId) {
  return request('DELETE', `${groupsBase(draftId)}/user-groups/${groupId}/members/${userId}`, 'remove from the group')
}

// --- Grants ---
// Each takes the draft to work in (its id), or null for live.

const grantsAddress = (draftId) => (draftId ? draftAddress(draftId, 'admin', 'grants') : '/api/admin/grants')

// What grants are made against: item types (with their properties, link perspectives and state
// machines) and markers.
export function fetchGrantTargets(draftId) {
  return get(`${grantsAddress(draftId)}/targets`, 'fetch what can be granted')
}

// Each principal's own permissions: [{ principal: { kind, id }, permissions: [permission] }], a
// principal being { kind: 'user' | 'group', id }.
export function fetchPermissions(draftId, principals) {
  const query = principals.map((p) => `principal=${p.kind}:${p.id}`).join('&')
  return get(`${grantsAddress(draftId)}?${query}`, 'fetch grants')
}

export function setPermission(draftId, principal, permission, value) {
  return request('PUT', grantsAddress(draftId), 'change the grant', { principal, permission, value })
}
