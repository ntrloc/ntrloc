import react from '@vitejs/plugin-react'
import { defineConfig } from 'vite'

// https://vite.dev/config/
export default defineConfig(({ command }) => ({
  // Dev server still serves from root ("npm run dev" at :5173, unaffected); only the production
  // build is prefixed, to be mounted under domain-1's /react-admin (see graph.ui.mounts in
  // runtimes/domain-1/application.yml -- the same general-purpose mount mechanism the existing
  // vanilla-JS admin-ui is served through, just pointed at this build's own output). main.jsx
  // reads this back via import.meta.env.BASE_URL for BrowserRouter's basename, so the two never
  // drift apart.
  base: command === 'build' ? '/react-admin/' : '/',
  plugins: [react()],
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
