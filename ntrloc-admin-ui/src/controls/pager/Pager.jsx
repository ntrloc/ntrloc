import { useEffect, useState } from 'react'
import './Pager.css'

// Binds to any object implementing the Pageable contract (duck-typed, not enforced by the
// language -- there's no TypeScript here):
//   currentPage(): number       -- 1-based
//   totalPages(): number
//   moveToPreviousPage(): void
//   moveToNextPage(): void
//   moveToPage(page: number): void
// No subscription of its own -- Pager just calls these during render and trusts whatever re-renders
// it (its parent) to hand it fresh values whenever the underlying data changes.
function Pager({ pageable }) {
  const currentPage = pageable.currentPage()
  const totalPages = pageable.totalPages()
  const [draft, setDraft] = useState(String(currentPage))

  // currentPage can change for reasons other than this input's own commit (prev/next buttons) --
  // keep the draft in sync whenever that happens.
  useEffect(() => {
    setDraft(String(currentPage))
  }, [currentPage])

  function commit() {
    const parsed = Number.parseInt(draft, 10)
    if (!Number.isFinite(parsed)) {
      setDraft(String(currentPage))
      return
    }
    const clamped = Math.min(Math.max(parsed, 1), totalPages)
    if (clamped !== currentPage) {
      pageable.moveToPage(clamped)
    } else {
      setDraft(String(currentPage))
    }
  }

  return (
    <nav className="pager" aria-label="Pagination">
      {currentPage > 1 && (
        <button type="button" className="pager-prev-btn" onClick={() => pageable.moveToPreviousPage()}>
          &lt; Page {currentPage - 1}
        </button>
      )}
      <span className="pager-current">
        Page{' '}
        <input
          type="text"
          name="page"
          inputMode="numeric"
          className="pager-current-input"
          value={draft}
          onChange={(e) => setDraft(e.target.value)}
          onBlur={commit}
          onKeyDown={(e) => {
            if (e.key === 'Enter') e.currentTarget.blur()
          }}
        />{' '}
        of {totalPages}
      </span>
      {currentPage < totalPages && (
        <button type="button" className="pager-next-btn" onClick={() => pageable.moveToNextPage()}>
          Page {currentPage + 1} &gt;
        </button>
      )}
    </nav>
  )
}

export default Pager
