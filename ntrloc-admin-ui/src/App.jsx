import { useEffect, useRef } from 'react'
import { useLocation, useNavigate } from 'react-router-dom'
import LayoutView from './layout/LayoutView'
import SearchView from './views/search/SearchView'
import SchemaView from './views/schema/SchemaView'
import AccessView from './views/access/AccessView'
import SecurityView from './views/security/SecurityView'
import AccountView from './views/account/AccountView'
import './App.css'

// Every top-level view stays mounted forever once first activated -- visibility (not mounting)
// is what tracks the current route after that, via a plain CSS display toggle. Using
// <Routes>/<Route> here instead would unmount whichever view isn't current, destroying its
// component state (open search panes, the schema editor's selected item type, ...) every time the
// user navigates away and back.
//
// Mounting is still deferred until a view's first activation, rather than mounting every view
// upfront -- so e.g. SchemaView's initial schema fetch only fires once the user actually visits
// it, not for every view (including admin UI extensions) at app startup regardless of whether
// it's ever opened.
function ViewGate({ active, children }) {
  const everActive = useRef(false)
  everActive.current ||= active

  return <view-gate data-active={active || undefined}>{everActive.current ? children : null}</view-gate>
}

function App({ extensions = [] }) {
  const location = useLocation()
  const navigate = useNavigate()

  // Replaces <Navigate to="/search" replace /> from the old <Routes>-based setup -- "/" isn't a
  // view of its own, just a redirect to "/search".
  useEffect(() => {
    if (location.pathname === '/') void navigate('/search', { replace: true })
  }, [location.pathname, navigate])

  const path = location.pathname

  return (
    <LayoutView extensions={extensions}>
      <ViewGate active={path === '/' || path === '/search'}>
        <SearchView />
      </ViewGate>
      <ViewGate active={path.startsWith('/schema')}>
        <SchemaView />
      </ViewGate>
      <ViewGate active={path.startsWith('/access')}>
        <AccessView />
      </ViewGate>
      <ViewGate active={path.startsWith('/security')}>
        <SecurityView />
      </ViewGate>
      <ViewGate active={path === '/account'}>
        <AccountView />
      </ViewGate>
      {extensions.map(({ navPath, Component }) => (
        <ViewGate key={navPath} active={path === navPath}>
          <Component />
        </ViewGate>
      ))}
    </LayoutView>
  )
}

export default App
