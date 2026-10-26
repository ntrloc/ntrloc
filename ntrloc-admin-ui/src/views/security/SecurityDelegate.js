// Every call to the sign-in configuration API. The LDAP bind password is write-only: it's sent on
// save/test when the admin typed one, and the server only ever reports bindPasswordSet.

const LDAP_API = '/api/admin/security/ldap'

async function readErrorMessage(res, fallback) {
  const body = await res.json().catch(() => null)
  return body?.message || fallback
}

function send(method, url, body) {
  return fetch(url, {
    method,
    credentials: 'same-origin',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  })
}

export async function fetchLdapConfig() {
  const res = await fetch(LDAP_API, { credentials: 'same-origin' })
  if (!res.ok) throw new Error(await readErrorMessage(res, `Failed to load the LDAP configuration (${res.status})`))
  return res.json()
}

export async function saveLdapConfig(config) {
  const res = await send('PUT', LDAP_API, config)
  if (!res.ok) throw new Error(await readErrorMessage(res, `Failed to save the LDAP configuration (${res.status})`))
  return res.json()
}

export async function testLdapConfig(config) {
  const res = await send('POST', `${LDAP_API}/test`, config)
  if (!res.ok) throw new Error(await readErrorMessage(res, `Failed to test the LDAP configuration (${res.status})`))
  return res.json()
}
