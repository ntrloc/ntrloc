import Button from '../../controls/button/Button'
import './SearchHeaderView.css'

function SearchHeaderView({ onAddSearchPane }) {
  return (
    <search-header-view>
      <Button onClick={onAddSearchPane}>New search pane</Button>
    </search-header-view>
  )
}

export default SearchHeaderView
