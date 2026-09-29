import React from 'react'

// Uses useState (not just a static element) so a working build genuinely proves hooks work against
// the host page's own React instance -- a duplicate-React bundling mistake would surface here as an
// "Invalid hook call" error, not silently render.
function ExampleExtensionView() {
  const [count, setCount] = React.useState(0)
  return React.createElement(
    'div',
    { className: 'example-extension-view' },
    React.createElement('h2', null, 'Example Extension'),
    React.createElement(
      'p',
      null,
      'Contributed by an admin UI extension discovered via classpath convention, not part of ' +
        'the core ntrloc-admin-ui bundle.',
    ),
    React.createElement(
      'button',
      { type: 'button', onClick: () => setCount((c) => c + 1) },
      `Clicked ${count} times`,
    ),
  )
}

window.registerNtrlocAdminExtension(ExampleExtensionView)
