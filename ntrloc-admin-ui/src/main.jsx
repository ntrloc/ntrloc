import { StrictMode } from 'react'
import * as React from 'react'
import * as ReactDOM from 'react-dom'
import { createRoot } from 'react-dom/client'
import * as ReactRouterDOM from 'react-router-dom'
import { BrowserRouter } from 'react-router-dom'
import './index.css'
import App from './App.jsx'
import { loadAdminUiExtensions } from './global/viewmodel/loadAdminUiExtensions'

// Exposed so dynamically-loaded extension bundles (built with these externalized, not bundled)
// resolve their imports to these exact instances instead of loading a second copy of React.
window.React = React
window.ReactDOM = ReactDOM
window.ReactRouterDOM = ReactRouterDOM

// Read off the <base> tag's own href (see vite.config.js's injectBaseHrefPlaceholder), not
// import.meta.env.BASE_URL -- that's frozen at build time to the literal relative string ('./'),
// not the real mount path a server resolves the placeholder to at serve time. The trailing slash
// <base href> uses is stripped: BrowserRouter's own basename convention has none (and an
// unstripped one on "/" alone would otherwise reduce to "", not "/").
function resolveBasename() {
  const href = document.querySelector('base')?.getAttribute('href') ?? '/'
  return href === '/' ? href : href.replace(/\/$/, '')
}

async function bootstrap() {
  const extensions = await loadAdminUiExtensions()
  createRoot(document.getElementById('root')).render(
    <StrictMode>
      <BrowserRouter basename={resolveBasename()}>
        <App extensions={extensions} />
      </BrowserRouter>
    </StrictMode>,
  )
}

bootstrap().catch(console.error)
