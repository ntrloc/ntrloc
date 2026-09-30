import { useLinkListViewModel } from './LinkListViewModel'
import './LinkListView.css'

// First pass, display only -- just the link (perspective) names for now, same starting point
// PropertyTreeView had before it grew into a full table. Shared by both the item type and trait
// editors' Links tab.
function LinkListView({ links }) {
  const { links: sortedLinks } = useLinkListViewModel(links)

  if (sortedLinks.length === 0) return <p>No links yet.</p>

  return (
    <ul className="link-list">
      {sortedLinks.map((link) => (
        <li key={link.name}>{link.name}</li>
      ))}
    </ul>
  )
}

export default LinkListView
