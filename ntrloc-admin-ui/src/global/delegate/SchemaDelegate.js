// Owns the one call to the schema API -- every other layer works with plain data, never fetch.
export async function fetchAdminSchema() {
  const res = await fetch('/api/admin/schema', { credentials: 'same-origin' })
  if (!res.ok) throw new Error(`Failed to fetch schema (${res.status})`)
  return res.json()
}
