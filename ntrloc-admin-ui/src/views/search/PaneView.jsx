import './PaneView.css'

// Shared shell for a pane in the search view: header (title + minimize/maximize/close) plus
// whatever body content the caller provides as children. SearchPaneView and ItemPaneView both wrap
// this rather than owning their own header markup -- neither knows or cares what the other renders.
// `maximized` is owned by SearchViewModel (one pane at a time, see its own toggleMaximize) rather
// than local state here, since SearchContentsView's own layout CSS needs to know which pane (if
// any) is maximized to lift it above the rest -- see the data-maximized attribute and
// SearchContentsView.css's own rule for it.
function PaneView({ title, onClose, maximized = false, onToggleMaximize, children }) {
  return (
    <pane-view data-maximized={maximized || undefined}>
      <pane-view-header>
        <span className="pane-view-title">{title}</span>
        <div className="pane-view-header-controls">
          <button type="button" className="pane-view-control-btn" title="Minimize">
            −
          </button>
          <button
            type="button"
            className="pane-view-control-btn"
            title={maximized ? 'Restore' : 'Maximize'}
            onClick={onToggleMaximize}
          >
            {maximized ? '🗗' : '⛶'}
          </button>
          <button type="button" className="pane-view-control-btn" title="Close" onClick={onClose}>
            ✕
          </button>
        </div>
      </pane-view-header>
      {children}
    </pane-view>
  )
}

export default PaneView
