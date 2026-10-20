import react from '@vitejs/plugin-react'
import { defineConfig } from 'vite'

// Must match MountBasePathResourceTransformer.PLACEHOLDER
// (domain-graph-starter/src/main/java/org/ntrloc/ui/hosting/) exactly -- that's the one place
// that replaces this with the real configured mount path, at serve time, for index.html
// specifically. See the base/injectBaseHrefPlaceholder comments below for why this exists.
const BASE_PATH_PLACEHOLDER = '__NTRLOC_UI_BASE_PATH__'

// Injects <base href="..."> into index.html's <head> -- every relative asset reference and link
// in the document then resolves against this, not the document's own request URL. In the
// production build this is the bare placeholder above, with NO trailing slash added here --
// MountBasePathResourceTransformer's own basePath already carries its own trailing slash (it's
// UiHostingConfiguration's normalized mount path, e.g. "/admin-react/"), so it's the complete
// replacement value on its own; appending another "/" here would double it up
// ("/admin-react//"). In dev this is just "/", since the dev server always serves from its own
// root with no mount-path concept at all.
function injectBaseHrefPlaceholder(command) {
  return {
    name: 'inject-base-href-placeholder',
    transformIndexHtml(html) {
      const baseHref = command === 'build' ? BASE_PATH_PLACEHOLDER : '/'
      return html.replace('<head>', `<head>\n    <base href="${baseHref}">`)
    },
  }
}

// https://vite.dev/config/
export default defineConfig(({ command }) => ({
  // Dev server still serves from root ("npm run dev" at :5173, unaffected). The production build
  // uses relative asset paths (no leading slash) rather than any fixed absolute prefix -- there
  // is no one fixed prefix: this UI's built dist/ is consumed by more than one independent
  // runtime (domain-1 in this repo, ntrloc-dam elsewhere) that don't necessarily mount it at the
  // same path, and the build itself can't know which. Relative paths are only safe here because
  // of the injected <base href> above -- without it, a relative script/link tag would resolve
  // against the *current* document URL, breaking on a hard refresh at a deep client-side route
  // (this app uses react-router-dom's BrowserRouter, history-based, with server-side SPA fallback
  // -- SpaFallbackResourceResolver -- serving index.html fresh at any such route). With an
  // explicit <base> present, resolution is anchored there instead, regardless of how deep the
  // current URL is. main.jsx reads the <base> tag's own resolved value back (not
  // import.meta.env.BASE_URL, which would just be this literal relative string) for
  // BrowserRouter's basename, so the two never drift apart.
  base: command === 'build' ? './' : '/',
  plugins: [react(), injectBaseHrefPlaceholder(command)],
  server: {
    // Vite binds to localhost only by default -- unreachable from another machine on the LAN
    // regardless of firewall rules, since it's not listening on that interface at all. true binds
    // 0.0.0.0, same effect as `vite --host`, matching domain-1's own Netty server (already *:9090
    // by default). The proxy target below stays localhost-only on purpose -- it runs server-side
    // in this same process, so it always reaches domain-1 via loopback regardless of which
    // interface the browser itself connected through.
    host: true,
    // Vite validates the request's Host header against this list (DNS-rebinding protection) --
    // binding 0.0.0.0 above isn't enough on its own, a request arriving via the LAN-facing
    // interface still gets rejected unless its own Host header is explicitly trusted here.
    allowedHosts: ['denmark.local'],
    proxy: {
      '/api': {
        target: 'http://localhost:9090',
        changeOrigin: true,
      },
    },
  },
}))
