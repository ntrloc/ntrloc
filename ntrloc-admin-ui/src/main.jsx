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

async function bootstrap() {
  const extensions = await loadAdminUiExtensions()
  createRoot(document.getElementById('root')).render(
    <StrictMode>
      <BrowserRouter basename={import.meta.env.BASE_URL}>
        <App extensions={extensions} />
      </BrowserRouter>
    </StrictMode>,
  )
}

bootstrap()
