import Navbar from './Navbar'
import Sidebar from './Sidebar'
import Content from './Content'
import './LayoutView.css'

function LayoutView({ extensions = [], children }) {
  return (
    <layout-view>
      <Navbar />
      <layout-view-body>
        <Sidebar extensions={extensions} />
        <Content>{children}</Content>
      </layout-view-body>
    </layout-view>
  )
}

export default LayoutView
