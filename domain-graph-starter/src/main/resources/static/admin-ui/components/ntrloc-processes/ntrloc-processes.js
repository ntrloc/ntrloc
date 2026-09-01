injectStyles('ntrloc-processes-styles', `
  ntrloc-processes {
    display: contents;
  }
  .processes-layout {
    display: flex;
    flex: 1;
    min-height: 0;
  }
  .processes-sidebar {
    width: 240px;
    flex-shrink: 0;
    overflow-y: auto;
    border-right: 1px solid var(--border);
    padding: 12px 0;
    transition: width 0.15s ease;
  }
  .processes-sidebar.collapsed {
    width: 48px;
    overflow: hidden;
  }
  .sidebar-header {
    display: flex;
    justify-content: flex-end;
    padding: 0 8px 8px;
  }
  .processes-sidebar.collapsed .sidebar-header {
    justify-content: center;
    padding: 0 0 8px;
  }
  .sidebar-toggle {
    border: none;
    background: none;
    color: var(--muted);
    cursor: pointer;
    font-size: 39px;
    line-height: 1;
    padding: 0 4px;
    border-radius: 4px;
  }
  .sidebar-toggle:hover {
    background: var(--panel-bg);
    color: var(--text);
  }
  .sidebar-collapsed-label {
    writing-mode: vertical-rl;
    transform: rotate(180deg);
    text-align: center;
    margin: 12px auto 0;
    font-size: 11px;
    font-weight: bold;
    letter-spacing: 0.05em;
    color: var(--muted);
    white-space: nowrap;
  }
  .sidebar-section-title {
    padding: 4px 16px;
    font-size: 11px;
    font-weight: bold;
    letter-spacing: 0.05em;
    color: var(--muted);
  }
  .sidebar-item {
    display: block;
    width: 100%;
    text-align: left;
    padding: 6px 16px;
    border: none;
    background: none;
    color: var(--text);
    font-size: 13px;
    cursor: pointer;
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
  }
  .sidebar-item:hover {
    background: var(--panel-bg);
  }
  .sidebar-item.active {
    background: var(--panel-bg);
    font-weight: bold;
  }
  .sidebar-status {
    padding: 6px 16px;
  }
  .processes-workspace {
    flex: 1;
    min-height: 0;
    display: flex;
    flex-direction: column;
  }
  .workspace-empty {
    flex: 1;
    display: flex;
    align-items: center;
    justify-content: center;
    color: var(--muted);
    font-style: italic;
  }
`);

// IDE-style layout: a persistent sidebar tree on the left (process definitions today; decision
// tables join it as their own section once the DMN editor exists, see task #4/#5 in the workflow
// summary) and a tab workspace on the right that stays visible the whole time, rather than the
// earlier "list view XOR workspace view" toggle. Clicking a sidebar item opens/activates a tab in
// <ntrloc-tab-workspace>; the workspace area itself only ever swaps between that element and a
// plain empty-state message, both created once (see updateWorkspaceState) so open editors are
// never torn down by an unrelated sidebar refresh.
class NtrlocProcesses extends HTMLElement {
  constructor() {
    super();
    this.definitions = [];
    this.loading = true;
    this.error = null;
    this.decisions = [];
    this.decisionsLoading = true;
    this.decisionsError = null;
    this._newDecisionCounter = 0;
    this._newProcessCounter = 0;
    this._workspace = createTabWorkspaceViewModel();
    this._previousTabCount = 0;
    this._collapsed = false;
  }

  connectedCallback() {
    this.renderShell();
    this.load();
    this.loadDecisions();
    this._unsubscribeWorkspace = this._workspace.onChange(() => this.updateWorkspaceState());
  }

  disconnectedCallback() {
    this._unsubscribeWorkspace?.();
  }

  async load() {
    try {
      const response = await fetch('/api/admin/process/definitions', { credentials: 'include' });
      if (!response.ok) {
        throw new Error('Request failed: ' + response.status);
      }
      this.definitions = await response.json();
    } catch (e) {
      this.error = 'Failed to load process definitions: ' + e.message;
    } finally {
      this.loading = false;
      this.renderSidebar();
    }
  }

  async loadDecisions() {
    try {
      const response = await fetch('/api/admin/dmn/decisions', { credentials: 'include' });
      if (!response.ok) {
        throw new Error('Request failed: ' + response.status);
      }
      this.decisions = await response.json();
    } catch (e) {
      this.decisionsError = 'Failed to load decision tables: ' + e.message;
    } finally {
      this.decisionsLoading = false;
      this.renderSidebar();
    }
  }

  openDefinition(id, title) {
    this._workspace.openTab({ id, title, resourceType: 'bpmn' });
  }

  openDecision(id, title, opts = {}) {
    this._workspace.openTab({ id, title, resourceType: 'dmn', initialKey: opts.initialKey, template: opts.template });
  }

  // Entry point for "open the DMN behind this rule" (ntrloc-item-detail.js's Marker Assignment
  // Rules list, see its own comment) -- callers only know a decision *key* (what a marker rule is
  // stored against), never a specific deployment id, so this resolves key -> latest-version id
  // itself rather than pushing that lookup onto every caller. `this.decisions` may already be
  // loaded (this element is never torn down, see the class comment) but could be stale if the
  // table was deployed after this tab's own last load, hence the refetch-and-retry before giving
  // up. this.decisions holds every version of every key (loadDecisions' own fetch, unfiltered), so
  // picking the max version here is what "latest" means, same as newDecisionTable's sibling flow
  // through the decision table editor's own version picker.
  async openDecisionByKey(decisionKey, title) {
    const latest = (decisions) => decisions
      .filter((d) => d.key === decisionKey)
      .sort((a, b) => b.version - a.version)[0];

    let match = latest(this.decisions);
    if (!match) {
      await this.loadDecisions();
      match = latest(this.decisions);
    }
    if (!match) {
      // No table under this key yet -- open a fresh one pre-pinned to the exact key (and in the
      // marker-decision shape: COLLECT + a `markerName` output), so saving it deploys the table
      // the caller's marker rule / state entry decision is already pointing at. A colon in the
      // placeholder id would make the editor try to fetch a real deployment; the `new-decision-`
      // prefix is what marks it "new", so that's kept and any colon in the key is stripped.
      const placeholderId = `new-decision-for-key-${decisionKey.replace(/:/g, '_')}`;
      this.openDecision(placeholderId, title ? `${title} (new)` : `New: ${decisionKey}`,
        { initialKey: decisionKey, template: 'state-entry-markers' });
      return;
    }
    this.openDecision(match.id, title || match.name || match.key);
  }

  // Placeholder id deliberately contains no colon -- ntrloc-decision-table-editor.js treats that
  // as its signal for "not yet deployed, start from an empty table" rather than fetching XML for
  // a real backend id (which always has the "<key>:<version>:<generatedId>" shape).
  newDecisionTable() {
    this._newDecisionCounter += 1;
    this.openDecision(`new-decision-${this._newDecisionCounter}`, 'New Decision Table');
  }

  // Same reserved-placeholder-prefix convention as newDecisionTable() -- ntrloc-process-editor.js
  // treats a "new-process-" prefixed id as its signal to start from a blank canvas.
  newProcess() {
    this._newProcessCounter += 1;
    this.openDefinition(`new-process-${this._newProcessCounter}`, 'New Process');
  }

  recenterActiveDiagram() {
    const active = this._tabWorkspace.activeContentElement;
    if (active && typeof active.centerDiagram === 'function') active.centerDiagram();
  }

  // Built exactly once: the sidebar and the tab workspace (which keeps alive one
  // <ntrloc-process-editor> per open tab, see ntrloc-tab-workspace.js) both need to survive every
  // subsequent render. Rebuilding this.innerHTML the way most components in this app do would
  // tear down every open editor's diagram-js canvas along with it.
  renderShell() {
    this.innerHTML = `
      <div class="processes-layout">
        <div class="processes-sidebar"></div>
        <div class="processes-workspace">
          <p class="workspace-empty">Select a process definition to begin.</p>
        </div>
      </div>
    `;
    this._sidebarEl = this.querySelector('.processes-sidebar');
    this._workspaceEl = this.querySelector('.processes-workspace');
    this._emptyStateEl = this.querySelector('.workspace-empty');
    // Recenters the active diagram once the sidebar's own width animation finishes -- doing this
    // immediately on click would compute against the pre-transition width, since the CSS
    // transition hasn't actually resized .editor-canvas yet at that point.
    this._sidebarEl.addEventListener('transitionend', (event) => {
      if (event.propertyName !== 'width') return;
      this.recenterActiveDiagram();
    });

    // Created and configured *before* insertion into the document -- ntrloc-tab-workspace's
    // connectedCallback reads this.viewModel/this.renderTabContent immediately when the element
    // is connected, so setting these properties after insertion would race connectedCallback
    // against the assignments (it fires synchronously on insertion, before the next line runs).
    this._tabWorkspace = document.createElement('ntrloc-tab-workspace');
    this._tabWorkspace.viewModel = this._workspace;
    this._tabWorkspace.renderTabContent = (tab) => {
      if (tab.resourceType === 'dmn') {
        const el = document.createElement('ntrloc-decision-table-editor');
        el.dataset.decisionId = tab.id;
        if (tab.initialKey) el.dataset.initialKey = tab.initialKey;
        if (tab.template) el.dataset.template = tab.template;
        return el;
      }
      const el = document.createElement('ntrloc-process-editor');
      el.dataset.definitionId = tab.id;
      return el;
    };
    // Appended once and never detached again -- unlike swapping it in/out of the DOM based on
    // whether any tabs are open, this keeps it permanently connected so its own onChange
    // subscription (registered in its connectedCallback) never has to survive a disconnect/
    // reconnect cycle. Detaching it on last-tab-close previously raced its own pruning logic:
    // notify() could disconnect it (via the empty-state swap) before its listener, later in the
    // same notify() iteration, ever got to run and remove the closing tab's content element --
    // leaving a stale entry that broke the next reopen of the same definition.
    this._workspaceEl.appendChild(this._tabWorkspace);

    this.updateWorkspaceState();
  }

  updateWorkspaceState() {
    const tabCount = this._workspace.tabs.length;
    // A tab closing may mean a Save deployed a new version -- refresh the sidebar so its
    // version/deployment-id stays accurate, matching the old list's refresh-on-return behavior.
    // Both lists refresh regardless of which tab type closed -- cheap, and simpler than tracking
    // per-resource-type counts separately.
    if (tabCount < this._previousTabCount) {
      this.load();
      this.loadDecisions();
    }
    this._previousTabCount = tabCount;

    const hasOpenTabs = tabCount > 0;
    this._tabWorkspace.style.display = hasOpenTabs ? '' : 'none';
    this._emptyStateEl.style.display = hasOpenTabs ? 'none' : '';
    this.renderSidebar();
  }

  // Two sections: Process Definitions and Decision Tables, both reusing the same tree/openTab
  // wiring -- <ntrloc-tab-workspace> only ever sees an opaque resourceType string. Collapsed to a
  // thin strip (just the toggle) hides both lists entirely rather than shrinking them, since a
  // partially-visible truncated list wouldn't be usable at any width worth collapsing to.
  renderSidebar() {
    this._sidebarEl.classList.toggle('collapsed', this._collapsed);
    this._sidebarEl.innerHTML = `
      <div class="sidebar-header">
        <button class="sidebar-toggle" title="${this._collapsed ? 'Expand' : 'Collapse'}">${this._collapsed ? '▸' : '◂'}</button>
      </div>
      ${this._collapsed ? '<div class="sidebar-collapsed-label">Diagrams</div>' : `
        <div class="sidebar-section-title">Process Definitions</div>
        ${this.loading ? '<p class="status sidebar-status">Loading...</p>' : ''}
        ${this.error ? `<p class="status sidebar-status">${escapeHtml(this.error)}</p>` : ''}
        ${!this.loading && !this.error && this.definitions.length === 0 ? '<p class="status sidebar-status">None deployed.</p>' : ''}
        ${this.definitions.map((d) => `
          <button class="sidebar-item ${this._workspace.activeTabId === d.id ? 'active' : ''}" data-id="${escapeHtml(d.id)}" title="${escapeHtml(d.name ?? d.key)}">
            ${escapeHtml(d.name ?? d.key)}
          </button>
        `).join('')}
        <button class="sidebar-item new-process-button">+ New Process</button>
        <div class="sidebar-section-title">Decision Tables</div>
        ${this.decisionsLoading ? '<p class="status sidebar-status">Loading...</p>' : ''}
        ${this.decisionsError ? `<p class="status sidebar-status">${escapeHtml(this.decisionsError)}</p>` : ''}
        ${!this.decisionsLoading && !this.decisionsError && this.decisions.length === 0 ? '<p class="status sidebar-status">None deployed.</p>' : ''}
        ${this.decisions.map((d) => `
          <button class="sidebar-item decision-item ${this._workspace.activeTabId === d.id ? 'active' : ''}" data-id="${escapeHtml(d.id)}" title="${escapeHtml(d.name ?? d.key)}">
            ${escapeHtml(d.name ?? d.key)}
          </button>
        `).join('')}
        <button class="sidebar-item new-decision-button">+ New Decision Table</button>
      `}
    `;
    this._sidebarEl.querySelector('.sidebar-toggle').addEventListener('click', () => {
      this._collapsed = !this._collapsed;
      this.renderSidebar();
    });
    this._sidebarEl.querySelectorAll('.sidebar-item.decision-item').forEach((btn) => {
      btn.addEventListener('click', () => {
        const decision = this.decisions.find((d) => d.id === btn.dataset.id);
        this.openDecision(btn.dataset.id, decision ? (decision.name ?? decision.key) : btn.dataset.id);
      });
    });
    this._sidebarEl.querySelectorAll('.sidebar-item:not(.decision-item):not(.new-decision-button):not(.new-process-button)').forEach((btn) => {
      btn.addEventListener('click', () => {
        const def = this.definitions.find((d) => d.id === btn.dataset.id);
        this.openDefinition(btn.dataset.id, def ? (def.name ?? def.key) : btn.dataset.id);
      });
    });
    this._sidebarEl.querySelector('.new-process-button').addEventListener('click', () => {
      this.newProcess();
    });
    this._sidebarEl.querySelector('.new-decision-button').addEventListener('click', () => {
      this.newDecisionTable();
    });
  }
}

function escapeHtml(value) {
  const div = document.createElement('div');
  div.textContent = String(value);
  return div.innerHTML;
}

customElements.define('ntrloc-processes', NtrlocProcesses);
