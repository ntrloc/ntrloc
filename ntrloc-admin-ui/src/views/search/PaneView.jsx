import './PaneView.css'

// Shared shell for a pane in the search view: header (title + minimize/maximize/close) plus
// whatever body content the caller provides as children. SearchPaneView and ItemPaneView both wrap
// this rather than owning their own header markup -- neither knows or cares what the other renders.
function PaneView({ title, onClose, children }) {
  return (
    <pane-view>
      <pane-view-header>
        <span className="pane-view-title">{title}</span>
        <div className="pane-view-header-controls">
          <button type="button" className="pane-view-control-btn" title="Minimize">
            −
          </button>
          <button type="button" className="pane-view-control-btn" title="Maximize">
            ⛶
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
