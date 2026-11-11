// Every call the Operations screen makes (/api/admin/operations), admins only.

async function readErrorMessage(res, fallback) {
  const body = await res.json().catch(() => null)
  return body?.message || body?.reason || fallback
}

// { retentionDays (null: keep everything), entries, oldest }
export async function fetchChangeLog() {
  const res = await fetch('/api/admin/operations/changelog', { credentials: 'same-origin' })
  if (!res.ok) throw new Error(await readErrorMessage(res, `Failed to load the changelog settings (${res.status})`))
  return res.json()
}

// retentionDays null keeps everything. Saving prunes at once; returns the settings as they now are.
export async function saveChangeLogRetention(retentionDays) {
  const res = await fetch('/api/admin/operations/changelog', {
    method: 'PUT',
    credentials: 'same-origin',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ retentionDays }),
  })
  if (!res.ok) throw new Error(await readErrorMessage(res, `Failed to save the retention (${res.status})`))
  return res.json()
}
