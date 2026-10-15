// Owns the one call to a single controlled list's own values -- not part of the bulk schema fetch
// (SchemaAdminController's own comment: the editor's detail pane lazy-loads these), so it's its own
// delegate rather than folding into SchemaDelegate's fetchAdminSchema.
export async function fetchControlledListValues(listId) {
  const res = await fetch(`/api/admin/schema/controlled-lists/${listId}`, { credentials: 'same-origin' })
  if (!res.ok) throw new Error(`Failed to fetch controlled list (${res.status})`)
  return res.json()
}
