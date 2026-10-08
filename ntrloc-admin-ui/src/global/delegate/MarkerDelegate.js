// Owns every call to the marker API -- markers aren't part of /api/admin/schema's own payload
// (they live outside the schema-mutation batch system entirely, same reasoning as the old
// vanilla-JS admin UI's marker-service.js), so this is a separate delegate from SchemaDelegate,
// feeding MarkerModel's own cache instead of SchemaCache.

async function readErrorMessage(res, fallback) {
  const body = await res.json().catch(() => null)
  return body?.message || fallback
}

export async function fetchMarkers() {
  const res = await fetch('/api/admin/markers', { credentials: 'same-origin' })
  if (!res.ok) throw new Error(await readErrorMessage(res, `Failed to fetch markers (${res.status})`))
  return res.json()
}

export async function createMarker({ name, description, scopeKind, scopeId }) {
  const res = await fetch('/api/admin/markers', {
    method: 'POST',
    credentials: 'same-origin',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ name, description, scopeKind, scopeId }),
  })
  if (!res.ok) throw new Error(await readErrorMessage(res, `Failed to create marker (${res.status})`))
  return res.json()
}

export async function updateMarker(id, { name, description }) {
  const res = await fetch(`/api/admin/markers/${id}`, {
    method: 'PUT',
    credentials: 'same-origin',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ name, description }),
  })
  if (!res.ok) throw new Error(await readErrorMessage(res, `Failed to update marker (${res.status})`))
  return res.json()
}

export async function deleteMarker(id) {
  const res = await fetch(`/api/admin/markers/${id}`, { method: 'DELETE', credentials: 'same-origin' })
  if (!res.ok) throw new Error(await readErrorMessage(res, `Failed to delete marker (${res.status})`))
}
