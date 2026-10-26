// Every call to the sign-in configuration API. Secrets (the LDAP bind password, OIDC client
// secrets) are write-only: sent when the admin typed one, never returned by the server.

const LDAP_API = '/api/admin/security/ldap'
const OIDC_API = '/api/admin/security/oidc'

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

export async function fetchOidcProviders() {
  const res = await fetch(OIDC_API, { credentials: 'same-origin' })
  if (!res.ok) throw new Error(await readErrorMessage(res, `Failed to load the OIDC providers (${res.status})`))
  return res.json()
}

export async function createOidcProvider(provider) {
  const res = await send('POST', OIDC_API, provider)
  if (!res.ok) throw new Error(await readErrorMessage(res, `Failed to add the provider (${res.status})`))
  return res.json()
}

export async function updateOidcProvider(registrationId, provider) {
  const res = await send('PUT', `${OIDC_API}/${encodeURIComponent(registrationId)}`, provider)
  if (!res.ok) throw new Error(await readErrorMessage(res, `Failed to save the provider (${res.status})`))
  return res.json()
}

export async function deleteOidcProvider(registrationId) {
  const res = await fetch(`${OIDC_API}/${encodeURIComponent(registrationId)}`, {
    method: 'DELETE',
    credentials: 'same-origin',
  })
  if (!res.ok) throw new Error(await readErrorMessage(res, `Failed to delete the provider (${res.status})`))
}

export async function testOidcIssuer(issuerUri) {
  const res = await send('POST', `${OIDC_API}/test`, { issuerUri })
  if (!res.ok) throw new Error(await readErrorMessage(res, `Failed to test the issuer (${res.status})`))
  return res.json()
}
