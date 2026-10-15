// Owns the calls to the projection API -- what the Search view shows the user is really a
// collection or single-item projection over the graph, not a distinct "search" concept on the
// backend.
export async function fetchCollectionProjection(spec) {
  return postProjection('/api/projection/collection', spec)
}

// Unlike fetchCollectionProjection, a 404 here is a real, expected outcome (the item was deleted,
// or the caller can no longer read it) -- returned as null rather than thrown, so callers don't
// need to special-case an HTTP status out of a generic error path.
export async function fetchItemProjection(spec) {
  const res = await fetch('/api/projection/item', {
    method: 'POST',
    credentials: 'same-origin',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(spec),
  })
  if (res.status === 404) return null
  if (!res.ok) throw new Error(`Failed to run item projection (${res.status})`)
  return res.json()
}

// A DELETE mutation, same POST /api/mutation endpoint create/update use (MutationRequestProcessor
// dispatches on each item's own "type").
export async function deleteItem(itemId) {
  const res = await fetch('/api/mutation', {
    method: 'POST',
    credentials: 'same-origin',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ items: [{ type: 'DELETE', itemId }], links: [] }),
  })
  if (!res.ok) throw new Error(`Failed to delete item (${res.status})`)
  return res.json()
}

// An UPDATE mutation, same endpoint. `properties` is the diff ItemUpdateMutation itself expects --
// an absent key leaves that property unchanged, a null value clears it, nested groups are nested
// objects (see ItemPaneViewModel.buildPropertyDiff, which is the one place that actually builds
// this shape). Unlike deleteItem, a 400 here is a real, expected outcome -- a validation failure
// the user can see and fix -- so it comes back as { ok: false, errors } rather than throwing; only
// an unexpected failure (network, 5xx) throws.
export async function updateItem(itemId, properties) {
  const res = await fetch('/api/mutation', {
    method: 'POST',
    credentials: 'same-origin',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ items: [{ type: 'UPDATE', itemId, properties }], links: [] }),
  })
  if (res.status === 400) {
    const body = await res.json()
    return { ok: false, errors: body.errors }
  }
  if (!res.ok) throw new Error(`Failed to update item (${res.status})`)
  return { ok: true }
}

async function postProjection(url, spec) {
  const res = await fetch(url, {
    method: 'POST',
    credentials: 'same-origin',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(spec),
  })
  if (!res.ok) throw new Error(`Failed to run projection (${res.status})`)
  return res.json()
}
