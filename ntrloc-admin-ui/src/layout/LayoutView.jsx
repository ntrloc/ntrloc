import Navbar from './Navbar'
import DraftNoticeBar from './DraftNoticeBar'
import Sidebar from './Sidebar'
import Content from './Content'
import './LayoutView.css'

function LayoutView({ extensions = [], children }) {
  return (
    <layout-view>
      <Navbar />
      <DraftNoticeBar />
      <layout-view-body>
        <Sidebar extensions={extensions} />
        <Content>{children}</Content>
      </layout-view-body>
    </layout-view>
  )
}

export default LayoutView
