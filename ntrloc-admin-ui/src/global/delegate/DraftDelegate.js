// Owns every call to the draft API. Drafts are listed for the current user: an admin sees every
// draft, a non-admin only those they've been invited to (docs/ntrloc-system-model-notes.md §11).

async function readErrorMessage(res, fallback) {
  const body = await res.json().catch(() => null)
  return body?.message || fallback
}

export async function fetchDrafts() {
  const res = await fetch('/api/drafts', { credentials: 'same-origin' })
  // The draft registry isn't built yet (docs/ntrloc-drafts-plan.md step 1.3): until it is, there are
  // simply no drafts to offer. Remove this once /api/drafts exists.
  if (res.status === 404) return []
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
