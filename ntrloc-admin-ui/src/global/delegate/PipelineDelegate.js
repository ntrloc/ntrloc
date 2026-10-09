// Owns every call to the marker decision pipeline API -- same reasoning as MarkerDelegate being
// separate from SchemaDelegate: pipelines aren't part of /api/admin/schema's own payload, they live
// behind their own admin surface (see MarkerDecisionPipelineAdminController).
//
// Read plus layout updates (the diagram editor's own save) -- pipeline/marker authoring itself is
// still script/API only, no create/delete from here yet.

async function readErrorMessage(res, fallback) {
  const body = await res.json().catch(() => null)
  return body?.message || fallback
}

// An item type has at most one pipeline (see MarkerDecisionPipelineRepository's own comment) --
// null when it has none yet, not an error; any other failure still throws.
export async function fetchPipelineForItemType(itemTypeId) {
  const res = await fetch(`/api/admin/marker-decision-pipelines/by-item-type/${itemTypeId}`, { credentials: 'same-origin' })
  if (res.status === 404) return null
  if (!res.ok) throw new Error(await readErrorMessage(res, `Failed to fetch pipeline (${res.status})`))
  return res.json()
}

// The server's UpdatePipelineRequest wants the full state back (pipeline + enabled + layout), not a
// partial patch -- the diagram editor only ever changes layout, so callers pass the pipeline/enabled
// it already has straight through unchanged.
export async function updatePipeline(id, { pipeline, enabled, layout }) {
  const res = await fetch(`/api/admin/marker-decision-pipelines/${id}`, {
    method: 'PUT',
    credentials: 'same-origin',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ pipeline, enabled, layout }),
  })
  if (!res.ok) throw new Error(await readErrorMessage(res, `Failed to save pipeline (${res.status})`))
  return res.json()
}
