// Owns calls to the signed-in user's own account API.

async function readErrorMessage(res, fallback) {
  const body = await res.json().catch(() => null)
  return body?.message || fallback
}

// The signed-in user: { displayName, email, isAdmin }.
export async function fetchMe() {
  const res = await fetch('/api/account/me', { credentials: 'same-origin' })
  if (!res.ok) throw new Error(await readErrorMessage(res, `Failed to fetch the signed-in user (${res.status})`))
  return res.json()
}
