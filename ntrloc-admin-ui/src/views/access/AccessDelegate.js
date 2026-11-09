import { createMockAccessData } from './mockAccessData'
import { setPermission } from './permissions/grantModel'

// Every call the Access screens make. TEMPORARY: served from in-memory mock data while the screens
// are being designed (see mockAccessData.js) -- edits change that memory only and are gone on
// reload. Swapping in the real API changes this file alone; the views and view models already call
// it as they will then.

const LATENCY_MS = 150

let data = createMockAccessData()

function respond(value) {
  return new Promise((resolve) => setTimeout(() => resolve(structuredClone(value)), LATENCY_MS))
}

export function fetchUsers() {
  return respond([...data.users].sort((a, b) => a.displayName.localeCompare(b.displayName)))
}

export function fetchGroups() {
  return respond([...data.groups].sort((a, b) => a.name.localeCompare(b.name)))
}

// One user, with what the Overview tab shows alongside: their tokens and the groups they're
// directly in.
export function fetchUserDetail(userId) {
  const user = data.users.find((u) => u.id === userId)
  if (!user) return Promise.reject(new Error('No such user'))
  const groupIds = data.memberships[userId] ?? []
  return respond({
    user,
    tokens: data.tokens[userId] ?? [],
    groups: data.groups.filter((g) => groupIds.includes(g.id)),
  })
}

// One group, with its direct members.
export function fetchGroupDetail(groupId) {
  const group = data.groups.find((g) => g.id === groupId)
  if (!group) return Promise.reject(new Error('No such group'))
  const members = data.users
    .filter((u) => (data.memberships[u.id] ?? []).includes(groupId))
    .sort((a, b) => a.displayName.localeCompare(b.displayName))
  return respond({ group, members })
}

function requireGroup(groupId) {
  const group = data.groups.find((g) => g.id === groupId)
  if (!group) throw new Error('No such group')
  return group
}

export async function renameGroup(groupId, name) {
  const group = requireGroup(groupId)
  if (data.groups.some((g) => g.id !== groupId && g.name.toLowerCase() === name.toLowerCase())) {
    throw new Error(`There's already a group called ${name}.`)
  }
  group.name = name
  return respond(group)
}

// As the real API: one parent, replacing the old one; 'everyone' stays the only top-level group,
// and a group can't sit under itself or anything below it.
export async function setGroupParent(groupId, parentId) {
  const group = requireGroup(groupId)
  requireGroup(parentId)
  const below = (id) => data.groups.filter((g) => g.parentIds.includes(id)).flatMap((g) => [g.id, ...below(g.id)])
  if (parentId === groupId || below(groupId).includes(parentId)) {
    throw new Error("A group can't sit under itself or a group below it.")
  }
  group.parentIds = [parentId]
  return respond(group)
}

function respondOk() {
  return respond(null)
}

function requireUser(userId) {
  const user = data.users.find((u) => u.id === userId)
  if (!user) throw new Error('No such user')
  return user
}

export async function updateUser(userId, { displayName, email, isAdmin }) {
  const user = requireUser(userId)
  if (data.users.some((u) => u.id !== userId && u.email.toLowerCase() === email.toLowerCase())) {
    throw new Error(`There's already a user with the email ${email}.`)
  }
  Object.assign(user, { displayName, email, isAdmin })
  return respond(user)
}

// Local accounts only; the mock just accepts it.
export async function resetPassword(userId) {
  if (requireUser(userId).signIn !== 'local') throw new Error('This user has no local password.')
  return respondOk()
}

// Returns the new token with its raw value -- the only time it's ever shown.
export async function createToken(userId, { name, expiresInDays }) {
  requireUser(userId)
  const now = new Date()
  const token = {
    id: `t-${crypto.randomUUID()}`,
    name,
    createdAt: now.toISOString(),
    expiresAt: expiresInDays ? new Date(now.getTime() + expiresInDays * 86_400_000).toISOString() : null,
  }
  data.tokens[userId] = [...(data.tokens[userId] ?? []), token]
  return respond({ ...token, token: `ntrloc_${crypto.randomUUID().replaceAll('-', '')}` })
}

export function revokeToken(userId, tokenId) {
  data.tokens[userId] = (data.tokens[userId] ?? []).filter((t) => t.id !== tokenId)
  return respondOk()
}

export function addUserToGroup(userId, groupId) {
  const current = data.memberships[userId] ?? []
  if (!current.includes(groupId)) data.memberships[userId] = [...current, groupId]
  return respondOk()
}

export function removeUserFromGroup(userId, groupId) {
  data.memberships[userId] = (data.memberships[userId] ?? []).filter((id) => id !== groupId)
  return respondOk()
}

// What grants are made against: item types (with their properties, link perspectives and state
// machines) and markers.
export function fetchGrantTargets() {
  return respond({ itemTypes: data.itemTypes, markers: data.markers })
}

// Every grant. The real API will answer per principal; one list keeps the prototype simple.
export function fetchGrants() {
  return respond(data.grants)
}

export function setGrantPermission(principal, permission, on) {
  data.grants = setPermission(data.grants, principal, permission, on)
  return respondOk()
}
