export async function fetchAdminUiExtensions() {
  const response = await fetch('/api/admin/ui-extensions', { credentials: 'same-origin' })
  if (!response.ok) {
    throw new Error(`Failed to fetch admin UI extensions (${response.status})`)
  }
  return response.json()
}
