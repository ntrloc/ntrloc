// Every call the Access screens make: users, groups and membership (/api/admin/users,
// /api/admin/user-groups) and grants (/api/admin/grants).
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

// One user, with what the Info / Tokens tab shows alongside: their tokens and the groups they're
// directly in.
export async function fetchUserDetail(userId) {
  const [users, tokens, memberships, groups] = await Promise.all([
    get('/api/admin/users', 'fetch the user'),
    get(`/api/admin/users/${userId}/tokens`, "fetch the user's tokens"),
    get(`/api/admin/users/${userId}/user-groups`, "fetch the user's groups"),
    fetchGroups(),
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

export async function fetchGroups() {
  const groups = await get('/api/admin/user-groups', 'fetch groups')
  return groups.sort((a, b) => a.name.localeCompare(b.name))
}

// One group, with its direct members.
export async function fetchGroupDetail(groupId) {
  const [groups, members] = await Promise.all([fetchGroups(), get(`/api/admin/user-groups/${groupId}/members`, 'fetch members')])
  const group = groups.find((g) => g.id === groupId)
  if (!group) throw new Error('No such group')
  return { group, members: members.sort((a, b) => a.displayName.localeCompare(b.displayName)) }
}

export function renameGroup(groupId, name) {
  return request('PUT', `/api/admin/user-groups/${groupId}`, 'rename the group', { name })
}

// One parent, replacing the old one.
export function setGroupParent(groupId, parentId) {
  return request('PUT', `/api/admin/user-groups/${groupId}/parent`, "change the group's parent", { parentUserGroupId: parentId })
}

export function addUserToGroup(userId, groupId) {
  return request('POST', `/api/admin/user-groups/${groupId}/members`, 'add to the group', { userId })
}

export function removeUserFromGroup(userId, groupId) {
  return request('DELETE', `/api/admin/user-groups/${groupId}/members/${userId}`, 'remove from the group')
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
