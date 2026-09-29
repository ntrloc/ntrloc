import { Navigate, Route, Routes } from 'react-router-dom'
import LayoutView from './layout/LayoutView'
import SearchView from './views/search/SearchView'
import SchemaView from './views/schema/SchemaView'

function App({ extensions = [] }) {
  return (
    <LayoutView extensions={extensions}>
      <Routes>
        <Route path="/" element={<Navigate to="/search" replace />} />
        <Route path="/search" element={<SearchView />} />
        <Route path="/schema" element={<SchemaView />} />
        {extensions.map(({ navPath, Component }) => (
          <Route key={navPath} path={navPath} element={<Component />} />
        ))}
      </Routes>
    </LayoutView>
  )
}

export default App
