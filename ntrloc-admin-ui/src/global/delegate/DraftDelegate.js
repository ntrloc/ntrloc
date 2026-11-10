// Owns every call to the draft API. Drafts are listed for the current user: an admin sees every
// draft, a non-admin only those they've been invited to (docs/ntrloc-system-model-notes.md §11).

async function readErrorMessage(res, fallback) {
  const body = await res.json().catch(() => null)
  return body?.message || fallback
}

export async function fetchDrafts() {
  const res = await fetch('/api/drafts', { credentials: 'same-origin' })
  if (!res.ok) throw new Error(await readErrorMessage(res, `Failed to fetch drafts (${res.status})`))
  return res.json()
}

// Starts a draft (admins only). Returns it as { id, name, ... }.
export async function createDraft({ name }) {
  const res = await fetch('/api/drafts', {
    method: 'POST',
    credentials: 'same-origin',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ name }),
  })
  if (!res.ok) throw new Error(await readErrorMessage(res, `Failed to create the draft (${res.status})`))
  return res.json()
}

// Writes the draft's changes to live and closes it (admins only). Returns { draft, grants }, each
// grant with its outcome: APPLIED, ALREADY_LIVE or DROPPED.
export async function applyDraft(draftId) {
  const res = await fetch(`/api/drafts/${draftId}/apply`, { method: 'POST', credentials: 'same-origin' })
  if (!res.ok) throw new Error(await readErrorMessage(res, `Failed to apply the draft (${res.status})`))
  return res.json()
}

// Closes the draft without changing live (admins only).
export async function discardDraft(draftId) {
  const res = await fetch(`/api/drafts/${draftId}/discard`, { method: 'POST', credentials: 'same-origin' })
  if (!res.ok) throw new Error(await readErrorMessage(res, `Failed to discard the draft (${res.status})`))
  return res.json()
}

// The draft's participants (admins only): the non-admins invited to work in it, as
// { id, displayName, email, addedAt }.
export async function fetchParticipants(draftId) {
  const res = await fetch(`/api/drafts/${draftId}/participants`, { credentials: 'same-origin' })
  if (!res.ok) throw new Error(await readErrorMessage(res, `Failed to fetch the participants (${res.status})`))
  return res.json()
}

// Users who could be invited: every non-admin (admins reach every draft without an invitation).
export async function fetchInvitableUsers() {
  const res = await fetch('/api/admin/users', { credentials: 'same-origin' })
  if (!res.ok) throw new Error(await readErrorMessage(res, `Failed to fetch users (${res.status})`))
  const users = await res.json()
  return users.filter((u) => !u.isAdmin).sort((a, b) => a.displayName.localeCompare(b.displayName))
}

export async function inviteParticipant(draftId, userId) {
  const res = await fetch(`/api/drafts/${draftId}/participants`, {
    method: 'POST',
    credentials: 'same-origin',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ userId }),
  })
  if (!res.ok) throw new Error(await readErrorMessage(res, `Failed to invite the user (${res.status})`))
}

export async function uninviteParticipant(draftId, userId) {
  const res = await fetch(`/api/drafts/${draftId}/participants/${userId}`, { method: 'DELETE', credentials: 'same-origin' })
  if (!res.ok) throw new Error(await readErrorMessage(res, `Failed to remove the user (${res.status})`))
}
