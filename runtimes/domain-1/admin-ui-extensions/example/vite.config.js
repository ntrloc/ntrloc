import { defineConfig } from 'vite'

// Builds straight into this module's own target/classes/static/ -- no manual copy step, and
// nothing generated is ever checked into src/. react is externalized (resolved via window.React at
// runtime, set by the core admin UI's own bootstrap before this script loads) so the extension
// doesn't bundle its own copy of React. manifest.json lives in public/ and is copied verbatim by
// Vite alongside bundle.js -- see AdminUiExtensionScanner in domain-graph-starter for the classpath
// convention (classpath*:/static/admin-ui-extensions/<key>/{manifest.json,bundle.js}) this satisfies.
export default defineConfig({
  build: {
    outDir: '../../target/classes/static/admin-ui-extensions/example',
    emptyOutDir: true,
    lib: {
      entry: 'src/index.js',
      formats: ['iife'],
      name: 'NtrlocAdminExtensionExample',
      fileName: () => 'bundle.js',
    },
    rollupOptions: {
      external: ['react'],
      output: {
        globals: { react: 'React' },
      },
    },
  },
})
