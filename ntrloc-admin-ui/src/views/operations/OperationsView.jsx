import ChangeLogCard from './ChangeLogCard'
import './OperationsView.css'

// Running ntrloc itself, rather than its content: so far, how long the changelog keeps entries.
function OperationsView() {
  return (
    <operations-view>
      <ChangeLogCard />
    </operations-view>
  )
}

export default OperationsView
