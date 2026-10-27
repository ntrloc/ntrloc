// Owns every call to the schema API -- every other layer works with plain data, never fetch.
export async function fetchAdminSchema() {
  const res = await fetch('/api/admin/schema', { credentials: 'same-origin' })
  if (!res.ok) throw new Error(`Failed to fetch schema (${res.status})`)
  return res.json()
}

// The server refused a batch because something it expected (see EXPECT_ITEM) no longer holds --
// someone else changed the schema first.
export class SchemaConflictError extends Error {}

// One batch of schema mutations, applied by the server all-or-nothing.
export async function applySchemaMutations(mutations) {
  const res = await fetch('/api/admin/schema/mutations', {
    method: 'POST',
    credentials: 'same-origin',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(mutations),
  })
  if (!res.ok) {
    const body = await res.json().catch(() => null)
    const message = body?.message || `Failed to save schema changes (${res.status})`
    throw res.status === 409 ? new SchemaConflictError(message) : new Error(message)
  }
}
