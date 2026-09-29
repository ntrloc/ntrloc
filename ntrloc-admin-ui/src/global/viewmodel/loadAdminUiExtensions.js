import { fetchAdminUiExtensions } from '../delegate/AdminUiExtensionDelegate'

// Extensions are discovered by classpath convention on the backend (see AdminUiExtensionScanner)
// and loaded one at a time here: window.registerNtrlocAdminExtension is a single shared slot, not
// a queue, because we never have two extension scripts executing concurrently -- each bundle.js is
// loaded, given the chance to call the registration hook, and read before the next one starts.
export async function loadAdminUiExtensions() {
  const descriptors = await fetchAdminUiExtensions().catch((error) => {
    console.error('Failed to fetch admin UI extension manifest', error)
    return []
  })

  const loaded = []
  for (const descriptor of descriptors) {
    const Component = await loadExtensionBundle(descriptor.bundleUrl)
    if (Component) {
      loaded.push({ navLabel: descriptor.navLabel, navPath: descriptor.navPath, Component })
    }
  }
  return loaded
}

function loadExtensionBundle(bundleUrl) {
  return new Promise((resolve) => {
    let registered = null
    window.registerNtrlocAdminExtension = (Component) => {
      registered = Component
    }

    const script = document.createElement('script')
    script.src = bundleUrl
    script.onload = () => {
      script.remove()
      if (!registered) {
        console.error(`Admin UI extension bundle did not register a component: ${bundleUrl}`)
      }
      resolve(registered)
    }
    script.onerror = () => {
      console.error(`Failed to load admin UI extension bundle: ${bundleUrl}`)
      script.remove()
      resolve(null)
    }
    document.head.appendChild(script)
  })
}
