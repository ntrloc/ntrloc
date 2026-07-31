injectStyles('ntrloc-search-styles', `
  ntrloc-search {
    display: contents;
  }
  .search-toolbar {
    flex-shrink: 0;
    display: flex;
    align-items: center;
    gap: 8px;
    padding: 16px 16px 0 16px;
  }
  .panes-container {
    position: relative;
    flex: 1;
    min-height: 0;
    display: grid;
    grid-auto-rows: 1fr;
    gap: 12px;
    padding: 16px;
    overflow: auto;
  }
  .pane {
    position: relative;
    z-index: 1;
    display: flex;
    flex-direction: column;
    min-height: 300px;
    background: var(--panel-bg);
    border: 1px solid var(--border);
    border-radius: 6px;
    overflow: hidden;
  }
  /* Overlays on top of (rather than reflowing) the grid, so the other panes stay exactly
     where they were underneath -- restoring just removes this, needing no position bookkeeping
     since the pane never actually left its grid slot. */
  .pane.is-maximized {
    position: absolute;
    inset: 16px;
    z-index: 10;
  }
  /* The dragged pane itself, left behind in its (live-reordered) slot as an empty dashed
     placeholder -- the actual pane content follows the cursor via the browser's native drag
     image, captured before this class is applied (see the dragstart handler). */
  .pane.is-drag-source {
    background: transparent;
    border: 2px dashed var(--accent);
  }
  .pane.is-drag-source > * {
    visibility: hidden;
  }
  .pane-header[draggable="true"] {
    cursor: grab;
  }
  .pane-header[draggable="true"]:active {
    cursor: grabbing;
  }
  .pane-header {
    display: flex;
    align-items: center;
    justify-content: space-between;
    padding: 0 8px 0 14px;
    height: 40px;
    flex-shrink: 0;
    background: rgba(255, 255, 255, 0.05);
    border-bottom: 1px solid var(--border);
  }
  .pane-title {
    font-size: 11px;
    font-weight: bold;
    letter-spacing: 0.05em;
    color: var(--muted);
    text-transform: uppercase;
  }
  .pane-controls {
    display: flex;
    align-items: center;
  }
  .control-btn {
    width: 28px;
    height: 28px;
    background: none;
    border: none;
    color: var(--muted);
    cursor: pointer;
    font-size: 13px;
  }
  .control-btn:hover {
    color: var(--text);
  }
  .control-btn.close-btn:hover {
    color: #ef5350;
  }
  .pane-body {
    flex: 1;
    min-height: 0;
    padding: 16px;
    overflow-y: auto;
  }
  .query-panel {
    display: flex;
    flex-direction: column;
    gap: 8px;
    margin-bottom: 16px;
  }
  .query-row, .sort-row {
    display: flex;
    align-items: center;
    gap: 10px;
  }
  .sort-label {
    font-size: 12px;
    color: var(--muted);
    flex-shrink: 0;
  }
  .query-select {
    flex: 1;
    min-width: 140px;
  }
  .results-summary {
    font-size: 12px;
    color: var(--muted);
    margin-bottom: 8px;
  }
  .results-list {
    display: flex;
    flex-direction: column;
    gap: 6px;
  }
  .result-item {
    margin: 0;
    padding: 10px 12px;
    background: var(--bg);
    border: 1px solid var(--border);
    border-radius: 4px;
    font-family: monospace;
    font-size: 12px;
    line-height: 1.5;
    white-space: pre-wrap;
    word-break: break-all;
  }
  .view-toggle {
    display: flex;
    align-items: center;
    margin-left: auto;
    border: 1px solid var(--border);
    border-radius: 4px;
    overflow: hidden;
  }
  .view-toggle button {
    padding: 5px 12px;
    background: transparent;
    color: var(--muted);
    border: none;
    font-size: 12px;
    cursor: pointer;
    transition: background 0.15s, color 0.15s;
  }
  .view-toggle button.active {
    background: var(--accent);
    color: #fff;
  }
  .view-toggle button:not(.active):hover {
    background: rgba(74, 158, 255, 0.1);
    color: var(--text);
  }
  .item-card {
    background: var(--bg);
    border: 1px solid var(--border);
    border-radius: 6px;
    overflow: hidden;
  }
  .item-card + .item-card {
    margin-top: 12px;
    border-top: 2px solid rgba(74, 158, 255, 0.5);
  }
  .item-card-header {
    display: flex;
    align-items: center;
    justify-content: space-between;
    padding: 10px 14px;
    border-bottom: 1px solid var(--border);
    background: rgba(255, 255, 255, 0.02);
  }
  .item-card-title {
    font-weight: 600;
    font-size: 13px;
  }
  .item-card-id {
    font-size: 11px;
    color: var(--muted);
    font-family: monospace;
    margin-left: 8px;
  }
  .item-card-actions button {
    padding: 4px 10px;
    border-radius: 4px;
    font-size: 11px;
    cursor: pointer;
    border: 1px solid var(--border);
    background: transparent;
    color: var(--muted);
    transition: all 0.15s;
  }
  .item-card-actions button:hover {
    color: var(--text);
    border-color: var(--accent);
  }
  .item-card-actions button.editing {
    background: var(--accent);
    border-color: var(--accent);
    color: #fff;
  }
  .prop-grid {
    display: grid;
    grid-template-columns: repeat(auto-fill, minmax(300px, 1fr));
    font-size: 13px;
    padding: 4px 0;
  }
  .prop-row {
    display: flex;
    align-items: baseline;
    padding: 4px 14px;
    border-bottom: 1px solid var(--border);
  }
  .prop-row:hover {
    background: rgba(74, 158, 255, 0.04);
  }
  .prop-key {
    color: var(--muted);
    font-weight: 500;
    min-width: 140px;
    flex-shrink: 0;
    padding-right: 12px;
  }
  .prop-value {
    flex: 1;
    word-break: break-word;
    display: flex;
    align-items: center;
    gap: 8px;
  }
  .prop-value .value-text {
    flex: 1;
  }
  .prop-value .value-null {
    color: var(--muted);
    font-style: italic;
  }
  .prop-value .prop-image {
    max-width: 100%;
    max-height: 120px;
    object-fit: contain;
    border-radius: 3px;
  }
  .prop-value input,
  .prop-value textarea,
  .prop-value select {
    flex: 1;
    padding: 4px 8px;
    background: transparent;
    color: var(--text);
    border: none;
    border-bottom: 2px solid transparent;
    border-radius: 0;
    font-size: 13px;
    font-family: inherit;
    outline: none;
    transition: border-color 0.15s;
  }
  .prop-value input:focus,
  .prop-value textarea:focus,
  .prop-value select:focus {
    border-bottom-color: var(--accent);
  }
  .prop-actions {
    display: flex;
    gap: 4px;
    flex-shrink: 0;
  }
  .prop-actions button {
    width: 22px;
    height: 22px;
    border: none;
    background: none;
    cursor: pointer;
    color: var(--muted);
    border-radius: 3px;
    font-size: 12px;
    display: flex;
    align-items: center;
    justify-content: center;
  }
  .prop-actions button:hover {
    background: rgba(74, 158, 255, 0.1);
    color: var(--accent);
  }
  .prop-actions button.delete-btn:hover {
    background: rgba(248, 81, 73, 0.1);
    color: #ef5350;
  }
  .prop-key.removed {
    color: #ef5350;
    text-decoration: line-through;
  }
  .prop-value.removed {
    color: var(--muted);
    font-style: italic;
    text-decoration: line-through;
  }
  .add-prop-row {
    padding: 8px 14px;
  }
  .add-prop-row button {
    padding: 4px 10px;
    border: 1px dashed var(--border);
    background: transparent;
    color: var(--muted);
    border-radius: 4px;
    font-size: 12px;
    cursor: pointer;
  }
  .add-prop-row button:hover {
    border-color: var(--accent);
    color: var(--accent);
  }
  .save-bar {
    display: flex;
    align-items: center;
    justify-content: flex-end;
    gap: 8px;
    padding: 8px 14px;
    border-top: 1px solid var(--border);
    background: rgba(74, 158, 255, 0.04);
  }
  .save-bar .change-count {
    font-size: 12px;
    color: #d29922;
    margin-right: auto;
  }
  .save-bar button {
    padding: 5px 14px;
    border-radius: 4px;
    font-size: 12px;
    cursor: pointer;
    border: none;
  }
  .save-bar .cancel-btn {
    background: transparent;
    border: 1px solid var(--border);
    color: var(--muted);
  }
  .save-bar .save-btn {
    background: #3fb950;
    color: #fff;
  }
  .no-results {
    text-align: center;
    padding: 32px 0;
    color: var(--muted);
    font-style: italic;
  }
  .panes-dock {
    flex-shrink: 0;
    display: flex;
    flex-direction: row;
    gap: 8px;
    align-items: stretch;
    padding: 0 16px 16px 16px;
  }
  .panes-dock .pane {
    width: 220px;
    min-height: unset;
  }
`);

// Recreates the Angular search screen: a toolbar to add panes, and a grid of independent
// search panes (item-type picker + optional sort + Project button + results). Mirrors
// SearchViewModel/SearchPaneViewModel's behavior (one pane can be maximized at a time,
// closed panes are gone for good, minimized panes move to a bottom dock) without reusing
// any Angular code -- this is a fresh implementation built from watching/reading that screen.
class NtrlocSearch extends HTMLElement {
  constructor() {
    super();
    this.panes = [];
    this.nextId = 1;
    this.draggingPaneId = null;
    // Live working copy of the active-pane order while a drag is in progress -- only committed
    // into `panes` on a real drop, so a cancelled drag (dragend with no drop) just discards it.
    this.dragPreviewOrder = null;
  }

  connectedCallback() {
    this.addPane();
  }

  addPane() {
    const id = this.nextId++;
    this.panes.push({
      id,
      windowState: 'normal',
      availableTypes: [],
      selectedTypeName: null,
      sortableFields: [],
      selectedSortField: null,
      selectedSortDirection: 'ASC',
      results: [],
      isLoading: false,
      lastProjectionMs: null,
      viewMode: 'formatted',
      propertyDefs: [],
      editingItems: {},
    });
    this.render();
    this.loadItemTypes(id);
  }

  closePane(id) {
    this.panes = this.panes.filter(p => p.id !== id);
    this.render();
  }

  maximizePane(id) {
    this.panes.forEach(p => { p.windowState = p.id === id ? 'maximized' : 'normal'; });
    this.render();
  }

  minimizePane(id) {
    this.pane(id).windowState = 'minimized';
    this.render();
  }

  restorePane(id) {
    this.pane(id).windowState = 'normal';
    this.render();
  }

  pane(id) {
    return this.panes.find(p => p.id === id);
  }

  async loadItemTypes(id) {
    try {
      const response = await fetch('/api/admin/schema', { credentials: 'include' });
      if (!response.ok) throw new Error('Request failed: ' + response.status);
      const data = await response.json();
      this.pane(id).availableTypes = (data.items || []).map(item => ({
        id: item.id,
        name: item.name,
        sortableFields: item.sortableFields || [],
        properties: (item.properties || []).map(p => ({ name: p.name, type: p.type, cardinality: p.cardinality })),
      }));
      this.render();
    } catch (e) {
      // Left silently empty, mirroring the Angular view model's fetchItemTypes() error handling.
    }
  }

  selectType(id, typeName) {
    const pane = this.pane(id);
    pane.selectedTypeName = typeName || null;
    pane.results = [];
    pane.lastProjectionMs = null;
    pane.selectedSortField = null;
    pane.selectedSortDirection = 'ASC';
    pane.editingItems = {};
    const type = pane.availableTypes.find(t => t.name === typeName);
    pane.sortableFields = type?.sortableFields ?? [];
    pane.propertyDefs = type?.properties ?? [];
    this.render();
  }

  selectSortField(id, field) {
    this.pane(id).selectedSortField = field || null;
    this.render();
  }

  toggleSortDirection(id) {
    const pane = this.pane(id);
    pane.selectedSortDirection = pane.selectedSortDirection === 'ASC' ? 'DESC' : 'ASC';
    this.render();
  }

  async project(id) {
    const pane = this.pane(id);
    if (!pane.selectedTypeName) return;
    pane.isLoading = true;
    pane.lastProjectionMs = null;
    this.render();
    const start = performance.now();
    try {
      const response = await fetch('/api/entity/projection', {
        method: 'POST',
        credentials: 'include',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          itemTypeName: pane.selectedTypeName,
          sortField: pane.selectedSortField,
          sortDirection: pane.selectedSortField ? pane.selectedSortDirection : undefined,
        }),
      });
      if (!response.ok) throw new Error('Request failed: ' + response.status);
      const result = await response.json();
      pane.lastProjectionMs = performance.now() - start;
      pane.results = result.items || [];
    } catch (e) {
      pane.results = [];
    } finally {
      pane.isLoading = false;
      this.render();
    }
  }

  setViewMode(id, mode) {
    this.pane(id).viewMode = mode;
    this.render();
  }

  toggleEdit(id, itemId) {
    const pane = this.pane(id);
    if (pane.editingItems[itemId]) {
      delete pane.editingItems[itemId];
    } else {
      const item = pane.results.find(r => r.itemId === itemId);
      if (!item) return;
      pane.editingItems[itemId] = {
        values: { ...item.properties },
        removed: new Set(),
        added: [],
      };
    }
    this.render();
  }

  cancelEdit(id, itemId) {
    delete this.pane(id).editingItems[itemId];
    this.render();
  }

  updateEditValue(id, itemId, propName, value) {
    const edit = this.pane(id).editingItems[itemId];
    if (edit) edit.values[propName] = value;
  }

  removeProperty(id, itemId, propName) {
    const edit = this.pane(id).editingItems[itemId];
    if (edit) {
      edit.removed.add(propName);
      this.render();
    }
  }

  undoRemoveProperty(id, itemId, propName) {
    const edit = this.pane(id).editingItems[itemId];
    if (edit) {
      edit.removed.delete(propName);
      this.render();
    }
  }

  addProperty(id, itemId) {
    const edit = this.pane(id).editingItems[itemId];
    if (!edit) return;
    const pane = this.pane(id);
    const existingKeys = new Set(Object.keys(edit.values));
    const available = pane.propertyDefs.filter(p => !existingKeys.has(p.name) || edit.removed.has(p.name));
    if (available.length === 0) return;
    const name = prompt('Property name to add:\n\nAvailable: ' + available.map(p => p.name).join(', '));
    if (!name) return;
    const def = pane.propertyDefs.find(p => p.name === name);
    if (!def && !name.trim()) return;
    edit.values[name] = '';
    edit.removed.delete(name);
    this.render();
  }

  async saveEdit(id, itemId) {
    const pane = this.pane(id);
    const edit = pane.editingItems[itemId];
    if (!edit) return;
    const mutations = [];
    const item = pane.results.find(r => r.itemId === itemId);
    if (!item) return;
    for (const [key, val] of Object.entries(edit.values)) {
      if (edit.removed.has(key)) continue;
      if (item.properties[key] !== val) {
        mutations.push({ property: key, value: val === '' ? null : val });
      }
    }
    for (const key of edit.removed) {
      mutations.push({ property: key, value: null });
    }
    if (mutations.length === 0) {
      delete pane.editingItems[itemId];
      this.render();
      return;
    }
    try {
      const response = await fetch(`/api/entity/${itemId}/properties`, {
        method: 'PATCH',
        credentials: 'include',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ mutations }),
      });
      if (!response.ok) throw new Error('Save failed: ' + response.status);
      delete pane.editingItems[itemId];
      this.project(id);
    } catch (e) {
      alert('Save failed: ' + e.message);
    }
  }


  activePanes() {
    return this.panes.filter(p => p.windowState !== 'minimized');
  }

  minimizedPanes() {
    return this.panes.filter(p => p.windowState === 'minimized');
  }

  gridCols() {
    return this.activePanes().length <= 1 ? 1 : 2;
  }

  // Live-updates the in-progress preview order (a working copy of the active panes) by moving
  // the dragged pane to sit where `targetId` currently is, then reflects that into the DOM via
  // each pane's CSS `order` -- no re-render, so the actively-dragged node is never recreated
  // (recreating it would abort the native drag session mid-gesture). A naive splice-out-then-
  // splice-in is a no-op for adjacent swaps (removing index 0 shifts the target into index 0,
  // so re-inserting "at the target's index" lands back where it started), so this shifts every
  // element between the two positions by one instead, same as Angular CDK's moveItemInArray
  // (which SearchViewModel.onDrop relies on).
  previewReorder(targetId) {
    const order = this.dragPreviewOrder;
    const fromIndex = order.findIndex(p => p.id === this.draggingPaneId);
    const toIndex = order.findIndex(p => p.id === targetId);
    if (fromIndex === -1 || toIndex === -1) return;
    const dragged = order[fromIndex];
    const delta = toIndex < fromIndex ? -1 : 1;
    for (let i = fromIndex; i !== toIndex; i += delta) {
      order[i] = order[i + delta];
    }
    order[toIndex] = dragged;
    order.forEach((pane, index) => {
      const el = this.querySelector(`.pane[data-pane-id="${pane.id}"]`);
      if (el) el.style.order = index;
    });
  }

  // Folds the live preview order back into the real backing array: every slot that held an
  // active pane gets the next pane from the (now-reordered) preview list, in order, while any
  // minimized panes interleaved among them keep their original absolute position.
  commitDragPreviewOrder() {
    const activeIds = new Set(this.dragPreviewOrder.map(p => p.id));
    let next = 0;
    this.panes = this.panes.map(p => activeIds.has(p.id) ? this.dragPreviewOrder[next++] : p);
    this.draggingPaneId = null;
    this.dragPreviewOrder = null;
    this.render();
  }

  render() {
    const active = this.activePanes();
    const minimized = this.minimizedPanes();
    this.innerHTML = `
      <div class="search-toolbar">
        <md-outlined-button class="add-pane-button">+ Add Pane</md-outlined-button>
      </div>
      <div class="panes-container" style="grid-template-columns: repeat(${this.gridCols()}, 1fr);">
        ${active.map((pane, index) => this.renderPane(pane, index)).join('')}
      </div>
      ${minimized.length > 0 ? `
        <div class="panes-dock">
          ${minimized.map(pane => this.renderPane(pane)).join('')}
        </div>
      ` : ''}
    `;
    this.wireUp();
  }

  renderPane(pane, index) {
    const minimized = pane.windowState === 'minimized';
    const maximized = pane.windowState === 'maximized';
    const orderStyle = index !== undefined ? `order: ${index};` : '';
    return `
      <div class="pane ${maximized ? 'is-maximized' : ''}" data-pane-id="${pane.id}" style="${orderStyle}">
        <div class="pane-header" ${!minimized && !maximized ? 'draggable="true"' : ''}>
          <span class="pane-title">Search ${pane.id}</span>
          <div class="pane-controls">
            ${!minimized ? `<button class="control-btn" data-action="minimize" title="Minimize">&#8722;</button>` : ''}
            ${pane.windowState === 'normal' ? `<button class="control-btn" data-action="maximize" title="Maximize">&#10530;</button>` : ''}
            ${maximized || minimized ? `<button class="control-btn" data-action="restore" title="Restore">&#10529;</button>` : ''}
            <button class="control-btn close-btn" data-action="close" title="Close">&#10005;</button>
          </div>
        </div>
        ${!minimized ? `
          <div class="pane-body">
            <div class="query-panel">
              <div class="query-row">
                <md-outlined-select class="query-select item-type-select" data-action="select-type">
                  <md-select-option value="" ${!pane.selectedTypeName ? 'selected' : ''}>
                    <div slot="headline">-- Select item type --</div>
                  </md-select-option>
                  ${pane.availableTypes.map(type => `
                    <md-select-option value="${escapeHtml(type.name)}" ${pane.selectedTypeName === type.name ? 'selected' : ''}>
                      <div slot="headline">${escapeHtml(type.name)}</div>
                    </md-select-option>
                  `).join('')}
                </md-outlined-select>
                <md-filled-button class="project-button" data-action="project" ${!pane.selectedTypeName || pane.isLoading ? 'disabled' : ''}>
                  ${pane.isLoading ? 'Loading...' : 'Project'}
                </md-filled-button>
              </div>
              ${pane.sortableFields.length > 0 ? `
                <div class="sort-row">
                  <span class="sort-label">Sort</span>
                  <md-outlined-select class="query-select" data-action="select-sort-field">
                    <md-select-option value="" ${!pane.selectedSortField ? 'selected' : ''}>
                      <div slot="headline">-- None --</div>
                    </md-select-option>
                    ${pane.sortableFields.map(field => `
                      <md-select-option value="${escapeHtml(field.name)}" ${pane.selectedSortField === field.name ? 'selected' : ''}>
                        <div slot="headline">${escapeHtml(field.name)}${field.system ? ' *' : ''}</div>
                      </md-select-option>
                    `).join('')}
                  </md-outlined-select>
                  ${pane.selectedSortField ? `
                    <md-outlined-button class="sort-direction-button" data-action="toggle-sort-direction">${pane.selectedSortDirection}</md-outlined-button>
                  ` : ''}
                </div>
              ` : ''}
            </div>
            ${pane.results.length > 0 ? `
              <div class="results-summary" style="display: flex; align-items: center;">
                <span>${pane.results.length} items
                ${pane.lastProjectionMs !== null ? `<span class="timing"> &middot; ${(pane.lastProjectionMs / 1000).toFixed(3)}s</span>` : ''}</span>
                <div class="view-toggle">
                  <button class="${pane.viewMode === 'formatted' ? 'active' : ''}" data-action="view-formatted">Formatted</button>
                  <button class="${pane.viewMode === 'raw' ? 'active' : ''}" data-action="view-raw">Raw</button>
                </div>
              </div>
              ${pane.viewMode === 'raw' ? `
                <div class="results-list">
                  ${pane.results.map(item => `<pre class="result-item">${escapeHtml(JSON.stringify(item, null, 2))}</pre>`).join('')}
                </div>
              ` : `
                <div class="results-list">
                  ${pane.results.map(item => this.renderItemCard(pane, item)).join('')}
                </div>
              `}
            ` : (!pane.isLoading && pane.selectedTypeName ? '<p class="no-results">No items found.</p>' : '')}
          </div>
        ` : ''}
      </div>
    `;
  }

  renderItemCard(pane, item) {
    const edit = pane.editingItems[item.itemId];
    const isEditing = !!edit;
    const title = item.properties.title || item.properties.name || item.itemType;
    const shortId = item.itemId.substring(0, 8) + '...';
    const canEdit = item.permissions && (item.permissions.edit?.length > 0 || item.permissions.delete);

    const propEntries = Object.entries(item.properties).sort((a, b) => a[0].localeCompare(b[0]));
    let rows;
    if (isEditing) {
      const allKeys = Array.from(new Set([...Object.keys(edit.values)])).sort();
      rows = allKeys.map(key => {
        const removed = edit.removed.has(key);
        const val = edit.values[key];
        const def = pane.propertyDefs.find(p => p.name === key);
        const inputType = this.inputTypeFor(def?.type);
        if (removed) {
          return `<div class="prop-row is-editing">
            <div class="prop-key removed">${escapeHtml(key)}</div>
            <div class="prop-value removed">
              <span class="value-null">(marked for removal)</span>
              <div class="prop-actions">
                <button title="Undo remove" data-action="undo-remove" data-prop="${escapeHtml(key)}">&#x21b6;</button>
              </div>
            </div>
          </div>`;
        }
        return `<div class="prop-row is-editing">
          <div class="prop-key">${escapeHtml(key)}</div>
          <div class="prop-value">
            <input type="${inputType}" value="${escapeHtml(val == null ? '' : String(val))}" data-action="edit-value" data-prop="${escapeHtml(key)}">
            <div class="prop-actions">
              <button class="delete-btn" title="Remove property" data-action="remove-prop" data-prop="${escapeHtml(key)}">&times;</button>
            </div>
          </div>
        </div>`;
      }).join('');
    } else {
      rows = propEntries.map(([key, val]) => `
        <div class="prop-row">
          <div class="prop-key">${escapeHtml(key)}</div>
          <div class="prop-value">
            ${this.renderPropertyValue(val)}
          </div>
        </div>
      `).join('');
    }

    const editCount = isEditing ? edit.removed.size + Object.entries(edit.values).filter(([k, v]) => !edit.removed.has(k) && v !== item.properties[k]).length : 0;

    return `
      <div class="item-card" data-item-id="${item.itemId}">
        <div class="item-card-header">
          <div>
            <span class="item-card-title">${escapeHtml(title)}</span>
            <span class="item-card-id">${shortId}</span>
          </div>
          ${canEdit ? `<div class="item-card-actions">
            <button class="${isEditing ? 'editing' : ''}" data-action="toggle-edit">${isEditing ? 'Editing' : 'Edit'}</button>
          </div>` : ''}
        </div>
        <div class="prop-grid ${isEditing ? 'is-editing' : ''}">${rows}</div>
        ${isEditing ? `
          <div class="add-prop-row">
            <button data-action="add-prop">+ Add property</button>
          </div>
          <div class="save-bar">
            ${editCount > 0 ? `<span class="change-count">${editCount} change${editCount !== 1 ? 's' : ''}</span>` : ''}
            <button class="cancel-btn" data-action="cancel-edit">Cancel</button>
            <button class="save-btn" data-action="save-edit">Save</button>
          </div>
        ` : ''}
      </div>
    `;
  }

  renderPropertyValue(val) {
    if (val == null || val === '') return '<span class="value-null">(empty)</span>';
    if (val && typeof val === 'object' && val.mimeType && val.url) {
      if (val.mimeType.startsWith('image/')) {
        return `<img class="prop-image" src="${escapeHtml(val.url)}" alt="image">`;
      }
      return `<span class="value-text">${escapeHtml(val.mimeType)} (${Math.round((val.length || 0) / 1024)}KB)</span>`;
    }
    if (val && typeof val === 'object') {
      return `<span class="value-text">${escapeHtml(JSON.stringify(val))}</span>`;
    }
    return `<span class="value-text">${escapeHtml(String(val))}</span>`;
  }

  inputTypeFor(schemaType) {
    switch (schemaType) {
      case 'INTEGER': case 'LONG': case 'DOUBLE': case 'FLOAT': return 'number';
      case 'DATE': return 'date';
      case 'BOOLEAN': return 'checkbox';
      default: return 'text';
    }
  }

  wireUp() {
    this.querySelectorAll('[data-pane-id]').forEach(paneEl => {
      const id = Number(paneEl.dataset.paneId);
      paneEl.querySelectorAll('[data-action]:not(.item-card [data-action])').forEach(el => {
        const action = el.dataset.action;
        if (action === 'minimize') el.addEventListener('click', () => this.minimizePane(id));
        if (action === 'maximize') el.addEventListener('click', () => this.maximizePane(id));
        if (action === 'restore') el.addEventListener('click', () => this.restorePane(id));
        if (action === 'close') el.addEventListener('click', () => this.closePane(id));
        if (action === 'project') el.addEventListener('click', () => this.project(id));
        if (action === 'toggle-sort-direction') el.addEventListener('click', () => this.toggleSortDirection(id));
        if (action === 'select-type') el.addEventListener('change', e => this.selectType(id, e.target.value));
        if (action === 'select-sort-field') el.addEventListener('change', e => this.selectSortField(id, e.target.value));
        if (action === 'view-formatted') el.addEventListener('click', () => this.setViewMode(id, 'formatted'));
        if (action === 'view-raw') el.addEventListener('click', () => this.setViewMode(id, 'raw'));
      });

      paneEl.querySelectorAll('.item-card[data-item-id]').forEach(cardEl => {
        const itemId = cardEl.dataset.itemId;
        cardEl.querySelectorAll('[data-action]').forEach(el => {
          const action = el.dataset.action;
          if (action === 'toggle-edit') el.addEventListener('click', () => this.toggleEdit(id, itemId));
          if (action === 'cancel-edit') el.addEventListener('click', () => this.cancelEdit(id, itemId));
          if (action === 'save-edit') el.addEventListener('click', () => this.saveEdit(id, itemId));
          if (action === 'add-prop') el.addEventListener('click', () => this.addProperty(id, itemId));
          if (action === 'remove-prop') el.addEventListener('click', () => this.removeProperty(id, itemId, el.dataset.prop));
          if (action === 'undo-remove') el.addEventListener('click', () => this.undoRemoveProperty(id, itemId, el.dataset.prop));
          if (action === 'edit-value') el.addEventListener('input', e => this.updateEditValue(id, itemId, el.dataset.prop, e.target.value));
        });
      });

      // draggable="true" lives on .pane-header, not the whole pane -- an ancestor-wide draggable
      // hijacks any click-and-drag gesture inside it for native HTML5 DnD, which made it
      // impossible to select text out of a pane's own results (a click-drag over a .result-item
      // started a pane-reorder drag instead of a text selection). The header is a small,
      // content-free strip, so this is a safe place for it. dragover/dragenter/drop stay on
      // paneEl below -- drop-target detection doesn't care which element the drag originated
      // from, and dropping anywhere over a pane (not just its header) should still reorder it.
      const headerEl = paneEl.querySelector('.pane-header');
      if (headerEl && headerEl.draggable) {
        headerEl.addEventListener('dragstart', e => {
          this.draggingPaneId = id;
          this.dragPreviewOrder = this.activePanes().slice();
          e.dataTransfer.effectAllowed = 'move';
          e.dataTransfer.setData('text/plain', String(id));
          // The whole pane is still what visually follows the cursor (not just the header) --
          // setDragImage explicitly takes paneEl as the drag image, independent of which element
          // is the draggable="true" source. Deferred for the same reason as before: the browser
          // must snapshot the pane's normal appearance before is-drag-source hides its content.
          e.dataTransfer.setDragImage(paneEl, e.offsetX, e.offsetY);
          setTimeout(() => paneEl.classList.add('is-drag-source'), 0);
        });
        headerEl.addEventListener('dragend', () => {
          // A real drop already committed and cleared these; this only matters for a cancelled
          // drag (dropped outside any valid target), where it discards the live preview and
          // falls back to a full render so every pane's `order` reverts to its committed slot.
          paneEl.classList.remove('is-drag-source');
          if (this.draggingPaneId !== null) {
            this.draggingPaneId = null;
            this.dragPreviewOrder = null;
            this.render();
          }
        });
      }
      paneEl.addEventListener('dragover', e => {
        if (this.draggingPaneId === null) return;
        e.preventDefault();
        e.dataTransfer.dropEffect = 'move';
      });
      paneEl.addEventListener('dragenter', () => {
        if (this.draggingPaneId === null || this.draggingPaneId === id) return;
        this.previewReorder(id);
      });
      paneEl.addEventListener('drop', e => {
        e.preventDefault();
        if (this.draggingPaneId !== null) this.commitDragPreviewOrder();
      });
    });
    const addPaneButton = this.querySelector('.add-pane-button');
    if (addPaneButton) addPaneButton.addEventListener('click', () => this.addPane());
  }
}

function escapeHtml(value) {
  const div = document.createElement('div');
  div.textContent = String(value);
  return div.innerHTML;
}

customElements.define('ntrloc-search', NtrlocSearch);
