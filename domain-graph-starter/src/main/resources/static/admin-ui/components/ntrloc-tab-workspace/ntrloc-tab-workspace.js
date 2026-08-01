injectStyles('ntrloc-tab-workspace-styles', `
  ntrloc-tab-workspace {
    display: flex;
    flex-direction: column;
    flex: 1;
    min-height: 0;
  }
  .tab-strip {
    display: flex;
    align-items: stretch;
    gap: 2px;
    padding: 4px 8px 0;
    border-bottom: 1px solid var(--border);
    flex-shrink: 0;
    overflow-x: auto;
  }
  .tab-strip .tab {
    display: flex;
    align-items: center;
    gap: 6px;
    padding: 8px 10px;
    border: 1px solid var(--border);
    border-bottom: none;
    border-radius: 6px 6px 0 0;
    background: var(--panel-bg);
    color: var(--muted);
    font-size: 12px;
    cursor: pointer;
    white-space: nowrap;
  }
  .tab-strip .tab.active {
    background: var(--bg);
    color: var(--text);
    /* Sits flush against the content area below -- the strip's own border-bottom would otherwise
       draw a line straight through the active tab's bottom edge. */
    margin-bottom: -1px;
    padding-bottom: 9px;
  }
  .tab-strip .tab-close {
    border: none;
    background: none;
    color: inherit;
    font-size: 14px;
    line-height: 1;
    cursor: pointer;
    padding: 0 2px;
    border-radius: 3px;
  }
  .tab-strip .tab-close:hover {
    background: var(--border);
  }
  .tab-content {
    flex: 1;
    display: flex;
    flex-direction: column;
    min-height: 0;
  }
`);

// Generic "several documents open, one active" shell -- the tab strip is cheap and rebuilt on
// every change, same as every other component in this app (see schema-view-model.js's
// convention). The content area is deliberately NOT rebuilt that way: each tab's content element
// (a live bpmn-js/dmn-js editor instance, holding canvas state no innerHTML round-trip would
// survive) is created exactly once via renderTabContent and left alone for the rest of its life,
// with only its inline display toggled on subsequent renders. Closing a tab removes its content
// element from the DOM, which fires the element's own disconnectedCallback -- ntrloc-process-
// editor.js already tears down its Diagram/ScriptEditor there, so no separate teardown hook is
// needed here.
class NtrlocTabWorkspace extends HTMLElement {
  constructor() {
    super();
    this._viewModel = null;
    this._renderTabContent = null;
    this._unsubscribe = null;
    this._contentElements = new Map();
  }

  // Consumer sets both of these immediately after creating the element, before it's connected.
  set viewModel(vm) {
    this._viewModel = vm;
  }

  // (tab) => HTMLElement. Called exactly once per tab id, the first time that tab is opened.
  set renderTabContent(fn) {
    this._renderTabContent = fn;
  }

  // Lets a host reach into the currently-visible tab's content element for follow-up actions it
  // can't express generically here (e.g. re-centering a diagram after the host's own layout
  // changes) -- this element stays agnostic about what that content actually is.
  get activeContentElement() {
    return this._contentElements.get(this._viewModel.activeTabId) ?? null;
  }

  connectedCallback() {
    // Guards the one-time shell build against a disconnect/reconnect cycle -- rebuilding
    // this.innerHTML unconditionally would wipe .tab-content (and every mounted editor inside it)
    // while _contentElements still pointed at the now-orphaned elements, corrupting the
    // reconciliation in render() below. The host is expected to keep this element permanently
    // connected once mounted (see ntrloc-processes.js), so this is defense-in-depth, not the
    // primary fix for that case.
    if (!this._stripEl) {
      this.innerHTML = `
        <div class="tab-strip"></div>
        <div class="tab-content"></div>
      `;
      this._stripEl = this.querySelector('.tab-strip');
      this._contentEl = this.querySelector('.tab-content');
    }
    this._unsubscribe = this._viewModel.onChange(() => this.render());
    this.render();
  }

  disconnectedCallback() {
    this._unsubscribe?.();
  }

  render() {
    const vm = this._viewModel;

    this._stripEl.innerHTML = vm.tabs.map((tab) => `
      <div class="tab ${tab.id === vm.activeTabId ? 'active' : ''}" data-id="${escapeHtml(tab.id)}">
        <span class="tab-title">${tab.isDirty ? '● ' : ''}${escapeHtml(tab.title)}</span>
        <button class="tab-close" data-id="${escapeHtml(tab.id)}" aria-label="Close tab">&times;</button>
      </div>
    `).join('');

    this._stripEl.querySelectorAll('.tab').forEach((el) => {
      el.addEventListener('click', () => vm.setActiveTab(el.dataset.id));
    });
    this._stripEl.querySelectorAll('.tab-close').forEach((btn) => {
      btn.addEventListener('click', (event) => {
        event.stopPropagation();
        this.closeTab(btn.dataset.id);
      });
    });

    for (const tab of vm.tabs) {
      if (!this._contentElements.has(tab.id)) {
        const el = this._renderTabContent(tab);
        // General contract for tab content elements, not BPMN-specific: a bubbling 'editor-closed'
        // requests this tab be closed (e.g. a toolbar button inside the content itself), and a
        // bubbling 'dirty-changed' (detail: { dirty }) reports unsaved-changes state for the tab
        // title's dot and the close-confirmation below.
        el.addEventListener('editor-closed', () => this.closeTab(tab.id));
        el.addEventListener('dirty-changed', (event) => vm.setTabDirty(tab.id, event.detail.dirty));
        // "Go to Called Process"/"Go to Decision Table" (ntrloc-process-editor.js's Call
        // Activity/DMN Task inspector fields) -- detail is already the exact { id, title,
        // resourceType } shape openTab expects, resolved by the editor itself against the same
        // process-definitions/decision-tables list the sidebar uses, so this is a direct
        // pass-through, not a lookup of its own.
        el.addEventListener('navigate-to-definition', (event) => vm.openTab(event.detail));
        this._contentEl.appendChild(el);
        this._contentElements.set(tab.id, el);
      }
    }
    for (const [id, el] of this._contentElements) {
      el.style.display = id === vm.activeTabId ? '' : 'none';
    }
    for (const [id, el] of [...this._contentElements]) {
      if (!vm.tabs.some((t) => t.id === id)) {
        el.remove();
        this._contentElements.delete(id);
      }
    }
  }

  closeTab(id) {
    const tab = this._viewModel.tabs.find((t) => t.id === id);
    if (tab?.isDirty && !confirm(`Close "${tab.title}" without saving your changes?`)) {
      return;
    }
    this._viewModel.closeTab(id);
  }
}

function escapeHtml(value) {
  const div = document.createElement('div');
  div.textContent = String(value ?? '');
  return div.innerHTML;
}

customElements.define('ntrloc-tab-workspace', NtrlocTabWorkspace);
