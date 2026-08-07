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
  .item-card-actions button.delete-btn:hover {
    background: rgba(248, 81, 73, 0.1);
    border-color: #ef5350;
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
  .link-groups {
    border-top: 1px solid var(--border);
  }
  .link-group + .link-group {
    border-top: 1px solid var(--border);
  }
  .link-group-header {
    display: flex;
    align-items: center;
    gap: 8px;
    width: 100%;
    padding: 10px 14px;
    background: none;
    border: none;
    color: var(--text);
    font: inherit;
    font-size: 12px;
    font-weight: 600;
    text-align: left;
    cursor: pointer;
  }
  .link-group-header:hover {
    background: rgba(74, 158, 255, 0.04);
  }
  .link-group-header .chevron {
    flex-shrink: 0;
    transition: transform 0.15s ease;
  }
  .link-group-header .chevron.collapsed {
    transform: rotate(-90deg);
  }
  .link-group-label {
    letter-spacing: 0.02em;
  }
  .link-group-count {
    color: var(--muted);
    font-weight: 400;
  }
  .link-group-body {
    padding: 0 14px 12px;
    display: flex;
    flex-direction: column;
    gap: 8px;
  }
  /* Row layout: the unlink action rail on the left, full height, followed by everything the link
     grouped together (link properties + item, or just item -- see renderLinkedItemCard) as one
     block on the right. Unlink acts on the whole grouped unit (it removes the LINK, not either
     side of it individually), which is why it sits outside/beside that grouping rather than
     inside either section's own header, the way edit-link (which only ever acts on the link-
     properties section specifically) still does. */
  .nested-item-card {
    display: flex;
    align-items: stretch;
    background: rgba(255, 255, 255, 0.02);
    border: 1px solid var(--border);
    border-radius: 5px;
    overflow: hidden;
  }
  .nested-item-card > .unlink-button {
    display: flex;
    align-items: center;
    justify-content: center;
    flex-shrink: 0;
    width: 30px;
    padding: 0;
    background: none;
    border: none;
    border-right: 1px solid var(--border);
    color: var(--muted);
    cursor: pointer;
  }
  .nested-item-card > .unlink-button:hover {
    background: rgba(248, 81, 73, 0.1);
    color: #ef5350;
  }
  .nested-item-body {
    flex: 1;
    min-width: 0;
  }
  .nested-item-header {
    display: flex;
    align-items: baseline;
    gap: 8px;
    padding: 7px 12px;
    font-size: 12px;
    font-weight: 600;
    border-bottom: 1px solid var(--border);
  }
  .nested-item-id {
    font-weight: 400;
    color: var(--muted);
    font-family: monospace;
    font-size: 11px;
  }
  /* Link-properties-above-item split, only present when the link type actually has properties to
     show (see renderLinkedItemCard) -- a link with none keeps the single-section layout above
     (just .nested-item-header + item .prop-grid), no empty section on top. Each section reuses
     .nested-item-header/.prop-grid/.prop-row completely unmodified, the same way the top-level
     item card itself uses them -- "flows horizontally" here means exactly what it means there:
     the shared .prop-grid's own auto-fill columns, not a bespoke nested variant. */
  .nested-item-sections {
    display: flex;
    flex-direction: column;
  }
  .nested-item-section + .nested-item-section {
    border-top: 1px solid var(--border);
  }
  .nested-empty-note {
    padding: 4px 12px 10px;
    color: var(--muted);
    font-style: italic;
    font-size: 13px;
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
  .item-card-header[draggable="true"] {
    cursor: grab;
  }
  .item-card-header[draggable="true"]:active {
    cursor: grabbing;
  }
  /* Left behind in place while its content follows the cursor as the native drag image (same
     technique as .pane.is-drag-source) -- content stays laid out (unlike the pane version) so
     the other cards around it don't jump, just dimmed to read as "this is what's being moved". */
  .item-card.is-drag-source {
    opacity: 0.4;
  }
  /* Computed once at dragstart and written directly to matching cards/groups (see
     computeLinkCandidates' callers) -- not part of the normal render(), since re-rendering mid-
     drag would tear down the dragged node and abort the gesture, same reasoning as the existing
     pane-reorder preview. */
  .item-card.valid-drop-target {
    outline: 2px solid var(--accent);
    outline-offset: -2px;
  }
  .item-card.valid-drop-target .item-card-header {
    background: rgba(74, 158, 255, 0.08);
  }
  .link-group.valid-drop-target {
    outline: 2px solid var(--accent);
    outline-offset: -2px;
    border-radius: 4px;
  }
  .nested-item-header {
    justify-content: space-between;
  }
  .nested-item-header .edit-link-button {
    display: inline-flex;
    align-items: center;
    justify-content: center;
    width: 22px;
    height: 22px;
    padding: 0;
    background: none;
    border: none;
    border-radius: 4px;
    color: var(--muted);
    cursor: pointer;
    flex-shrink: 0;
  }
  .nested-item-header .edit-link-button:hover {
    background: rgba(74, 158, 255, 0.1);
    color: var(--accent);
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
    // Item-card drag-to-link state -- fully separate from draggingPaneId/dragPreviewOrder above
    // (a different gesture, on a different element) so the two drag systems can't interfere:
    // each one's own handlers only ever act when its own state is non-null. draggingItem is
    // {itemId, itemType, paneId} for the card currently being dragged; dragValidTargets/
    // dragValidGroups are computed once at dragstart (see startItemDrag) and written directly
    // into the DOM rather than through render(), since a re-render mid-drag would tear down the
    // dragged node and abort the native drag gesture -- same reasoning as dragPreviewOrder.
    this.draggingItem = null;
    this.dragValidTargets = null;
    this.dragValidGroups = null;
    // { [linkId]: {id, properties} } -- the schema-wide link property definitions (AdminLinkView),
    // separate from each item's own per-perspective links map: a perspective only carries a
    // linkId, the property *definitions* for that link live here, keyed by that id.
    this.linkDefsById = new Map();
    // { [typeName]: mappedType } -- every pane's own availableTypes is an identical copy of the
    // same schema, so drag/drop resolution (which is cross-pane by nature) reads from this single
    // component-level cache instead of reaching into whichever pane happens to be involved.
    this.itemTypesByName = new Map();
    // Same mapped types, keyed by id instead of name -- lets typeIsAssignableTo walk a concrete
    // type's supertypeId chain (which only carries ids, never names) up to whatever type a
    // perspective's targets actually declare.
    this.itemTypesById = new Map();
  }

  connectedCallback() {
    this.addPane();
    // Keeps every open pane's dropdown live as the schema changes elsewhere -- previously each
    // pane's availableTypes was fetched once, at creation, and frozen from then on.
    this._unsubscribeSchema = onGlobalSchemaChange(() => this.refreshAvailableTypesFromGlobalSchema());
  }

  disconnectedCallback() {
    if (this._unsubscribeSchema) this._unsubscribeSchema();
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
      // { [itemId]: Set<perspectiveName> } -- absence means expanded, matching editingItems'
      // own "only the entries that need tracking are present" convention. Starts empty so every
      // link group is expanded by default (nothing hidden until the user actively collapses it),
      // per feedback that collapsing is for "pushing items out of view" once you've seen them,
      // not a default-hidden state. Lives on the pane object, not re-initialized in project() --
      // same lifetime as editingItems, reset only on selectType() (a real type change), so
      // re-running the same search (e.g. after a save) doesn't silently re-expand everything.
      collapsedLinkGroups: {},
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

  // links is kept as-is from the admin schema response (already { perspectiveName: [{id,
  // linkId, targets, minCardinality, maxCardinality, definedIn}] }, see AdminItemLinkPerspectiveView)
  // -- computeLinkCandidates reads targets/linkId straight off it, no need to remap.
  mapAvailableTypes(schema) {
    return (schema.items || [])
      .map(item => ({
        id: item.id,
        name: item.name,
        sortableFields: item.sortableFields || [],
        properties: (item.properties || []).map(p => ({ name: p.name, type: p.type, cardinality: p.cardinality })),
        links: item.links || {},
        supertypeId: item.supertypeId || null,
      }))
      .sort((a, b) => a.name.localeCompare(b.name));
  }

  cacheSchemaSideTables(schema) {
    this.linkDefsById = new Map((schema.links || []).map(l => [l.id, l]));
    const mapped = this.mapAvailableTypes(schema);
    this.itemTypesByName = new Map(mapped.map(t => [t.name, t]));
    this.itemTypesById = new Map(mapped.map(t => [t.id, t]));
  }

  // A perspective's declared target can be an abstract supertype (e.g. Person's ownsVehicle
  // targets "Vehicle", not "Car"/"Bicycle" individually -- confirmed live: dragging a Car onto a
  // Person worked because Car's own inherited "ownedBy" perspective targets the concrete "Person"
  // directly, but dragging a Person onto a Bicycle failed until this walked the chain, since
  // "Bicycle" !== "Vehicle" under plain string equality). Walks typeName's own supertypeId chain
  // (concrete type -> ... -> abstract root) checking each ancestor against targetTypeName, so a
  // concrete subtype still matches a perspective declared against its abstract supertype.
  typeIsAssignableTo(typeName, targetTypeName) {
    let current = this.itemTypesByName.get(typeName);
    while (current) {
      if (current.name === targetTypeName) return true;
      current = current.supertypeId ? this.itemTypesById.get(current.supertypeId) : null;
    }
    return false;
  }

  async loadItemTypes(id) {
    try {
      // load(), not reload(): globalSchemaModel is kept current by its own onSchemaEvent
      // subscription, so this only forces a real fetch the very first time (cold start); every
      // later pane just reads the already-current cache. Still self-healing against the rare case
      // of a missed SSE event during a disconnect/reconnect window, same as the New Item dialog's
      // own fresh-fetch-per-open behavior.
      const schema = await globalSchemaModel.load();
      this.pane(id).availableTypes = this.mapAvailableTypes(schema);
      this.cacheSchemaSideTables(schema);
      this.render();
    } catch (e) {
      // Left silently empty, mirroring the Angular view model's fetchItemTypes() error handling.
    }
  }

  refreshAvailableTypesFromGlobalSchema() {
    if (!globalSchemaModel._schema) return;
    // Each pane gets its own array (not a shared reference) -- availableTypes is only ever
    // reassigned wholesale elsewhere in this class, never mutated in place, but there's no reason
    // to rely on that staying true.
    this.panes.forEach(p => { p.availableTypes = this.mapAvailableTypes(globalSchemaModel._schema); });
    this.cacheSchemaSideTables(globalSchemaModel._schema);
    this.render();
  }

  selectType(id, typeName) {
    const pane = this.pane(id);
    pane.selectedTypeName = typeName || null;
    pane.results = [];
    pane.lastProjectionMs = null;
    pane.selectedSortField = null;
    pane.selectedSortDirection = 'ASC';
    pane.editingItems = {};
    pane.collapsedLinkGroups = {};
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

  toggleLinkGroup(id, itemId, perspectiveName) {
    const pane = this.pane(id);
    if (!pane.collapsedLinkGroups[itemId]) pane.collapsedLinkGroups[itemId] = new Set();
    const collapsed = pane.collapsedLinkGroups[itemId];
    if (collapsed.has(perspectiveName)) collapsed.delete(perspectiveName);
    else collapsed.add(perspectiveName);
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
    const properties = {};
    const item = pane.results.find(r => r.itemId === itemId);
    if (!item) return;
    for (const [key, val] of Object.entries(edit.values)) {
      if (edit.removed.has(key)) continue;
      if (item.properties[key] !== val) {
        properties[key] = val === '' ? null : val;
      }
    }
    for (const key of edit.removed) {
      properties[key] = null;
    }
    if (Object.keys(properties).length === 0) {
      delete pane.editingItems[itemId];
      this.render();
      return;
    }
    try {
      const response = await fetch('/api/mutation', {
        method: 'POST',
        credentials: 'include',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ items: [{ type: 'UPDATE', itemId, properties }] }),
      });
      if (!response.ok) {
        const body = await response.json().catch(() => null);
        throw new Error(body?.errors?.[0]?.message || 'Save failed: ' + response.status);
      }
      delete pane.editingItems[itemId];
      this.project(id);
    } catch (e) {
      alert('Save failed: ' + e.message);
    }
  }

  async deleteItem(id, itemId) {
    const pane = this.pane(id);
    const item = pane.results.find(r => r.itemId === itemId);
    if (!item) return;
    const title = item.properties.title || item.properties.name || item.itemType;
    const confirmed = await openConfirmDialog({
      title: 'Delete Item',
      message: `Delete "${title}"? This cannot be undone.`,
      confirmLabel: 'Delete',
      destructive: true,
    });
    if (!confirmed) return;
    try {
      const response = await fetch('/api/mutation', {
        method: 'POST',
        credentials: 'include',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ items: [{ type: 'DELETE', itemId }] }),
      });
      if (!response.ok) {
        const body = await response.json().catch(() => null);
        throw new Error(body?.errors?.[0]?.message || 'Delete failed: ' + response.status);
      }
      delete pane.editingItems[itemId];
      this.project(id);
    } catch (e) {
      alert('Delete failed: ' + e.message);
    }
  }

  // Every valid (type-pair, not-already-linked) way to connect draggedItem to targetItem, paired
  // up by shared linkId rather than by target-type-name coincidence -- two item types can have
  // more than one link between them (e.g. "ownsVehicle" and a hypothetical "reservedVehicle"),
  // and only the underlying linkId (present on both sides' perspectives, via AdminLinkView/
  // AdminItemLinkPerspectiveView -- see global-schema-model.js's own comment on why the admin
  // schema, not the calculated one, is what's available here) reliably says which perspective on
  // draggedItem's side pairs with which on targetItem's side. "Already linked" is checked against
  // the already-fetched projection data (draggedItem.links), not the schema -- the schema only
  // proves the two *types* can be linked, not whether these two *instances* already are.
  // Reused as-is for both the drag-time highlight computation and the actual drop resolution, and
  // for a drop on a specific link-group section (see the .link-group drop handler in wireUp) by
  // just filtering this same list down to the one candidate whose toPerspective matches the
  // pinned group.
  computeLinkCandidates(draggedItem, targetItem) {
    const fromType = this.itemTypesByName.get(draggedItem.itemType);
    const toType = this.itemTypesByName.get(targetItem.itemType);
    if (!fromType || !toType) return [];
    const candidates = [];
    for (const [fromPerspective, fromEntries] of Object.entries(fromType.links)) {
      for (const fromEntry of fromEntries) {
        if (!fromEntry.targets.some(t => this.typeIsAssignableTo(targetItem.itemType, t.name))) continue;
        for (const [toPerspective, toEntries] of Object.entries(toType.links)) {
          if (!toEntries.some(e => e.linkId === fromEntry.linkId)) continue;
          // Matched on target-item identity alone, not l.linkId -- ProjectedLink.linkId is the
          // per-instance link's own id (register_link.link_id), a different UUID from the
          // schema-level fromEntry.linkId (link_definition_id) used everywhere else in this
          // method; a perspective already pointing at this exact target item is already linked
          // regardless of which specific instance id that link happens to have.
          const alreadyLinked = (draggedItem.links?.[fromPerspective] || [])
            .some(l => l.item?.itemId === targetItem.itemId);
          if (alreadyLinked) continue;
          const linkDef = this.linkDefsById.get(fromEntry.linkId);
          candidates.push({
            linkId: fromEntry.linkId,
            fromPerspective,
            toPerspective,
            propertyDefs: linkDef?.properties || [],
          });
        }
      }
    }
    return candidates;
  }

  // Re-runs project() on every currently-open pane that could be showing either side of a link
  // that just changed -- not just a pane whose selectedTypeName exactly matches one of the two
  // concrete types (typeNames), but also any pane searching an ABSTRACT ancestor of one of them:
  // a pane searching "Vehicle" still lists Bicycle instances, so a Person<->Bicycle link change
  // needs to refresh it too, even though "Vehicle" never appears in typeNames itself (typeNames is
  // always the two concrete item types actually on each end of the link). Confirmed live as the
  // cause of a real bug: linking a Person to a Bicycle updated a pane searching "Bicycle" directly,
  // but not one searching "Vehicle" -- same abstract/concrete gap as computeLinkCandidates' own
  // targets check, just surfacing on the refresh side instead of the candidate-resolution side.
  reprojectPanesForTypes(typeNames) {
    this.panes
      .filter(p => p.selectedTypeName && typeNames.some(t => this.typeIsAssignableTo(t, p.selectedTypeName)))
      .forEach(p => this.project(p.id));
  }

  async createLink(candidate, draggedItem, targetItem, properties) {
    try {
      const response = await fetch('/api/mutation', {
        method: 'POST',
        credentials: 'include',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          links: [{
            type: 'CREATE',
            firstItem: { perspectiveName: candidate.fromPerspective, item: { type: 'EXISTING', itemId: draggedItem.itemId } },
            secondItem: { perspectiveName: candidate.toPerspective, item: { type: 'EXISTING', itemId: targetItem.itemId } },
            properties: properties && Object.keys(properties).length > 0 ? properties : undefined,
          }],
        }),
      });
      if (!response.ok) {
        const body = await response.json().catch(() => null);
        throw new Error(body?.errors?.[0]?.message || 'Link failed: ' + response.status);
      }
      this.reprojectPanesForTypes([draggedItem.itemType, targetItem.itemType]);
    } catch (e) {
      alert('Link failed: ' + e.message);
    }
  }

  // Same openConfirmDialog convention as deleteItem -- unlike link *creation* (still immediate on
  // drop with no confirm step when there are no properties to fill in), removing an existing link
  // is a one-click destructive action on data the user can already see, so it earns the same guard
  // rail as deleting an item outright.
  async unlinkItem(id, linkId, otherItemType, otherItemTitle) {
    const pane = this.pane(id);
    const confirmed = await openConfirmDialog({
      title: 'Remove Link',
      message: `Remove link to "${otherItemTitle}" (${otherItemType})?`,
      confirmLabel: 'Remove',
      destructive: true,
    });
    if (!confirmed) return;
    try {
      const response = await fetch('/api/mutation', {
        method: 'POST',
        credentials: 'include',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ links: [{ type: 'DELETE', linkId }] }),
      });
      if (!response.ok) {
        const body = await response.json().catch(() => null);
        throw new Error(body?.errors?.[0]?.message || 'Unlink failed: ' + response.status);
      }
      this.reprojectPanesForTypes([pane.selectedTypeName, otherItemType]);
    } catch (e) {
      alert('Unlink failed: ' + e.message);
    }
  }

  // itemId/perspective/linkId together pin down exactly one ProjectedLink entry inside the
  // *outer* item's own already-fetched projection data -- reused as both the dialog's pre-fill
  // (linkEntry.properties) and the baseline the post-dialog diff is computed against, so this
  // never needs its own separate fetch.
  async editLinkProperties(id, itemId, perspective, linkId, otherItemType, otherItemTitle) {
    const pane = this.pane(id);
    const item = pane.results.find(r => r.itemId === itemId);
    if (!item) return;
    const linkEntry = (item.links[perspective] || []).find(l => l.linkId === linkId);
    if (!linkEntry) return;
    const propertyDefs = this.linkPropertyDefsFor(item.itemType, perspective);
    if (propertyDefs.length === 0) return;
    const edited = await openLinkPropertiesDialog({
      title: `Edit Link Properties — ${otherItemTitle}`,
      propertyDefs,
      initialValues: linkEntry.properties || {},
    });
    if (!edited) return;
    // Delta against the link's current properties, not a full replace -- mirrors saveEdit's own
    // item-property diffing (see its comment): only properties that actually changed are sent,
    // and a property the user blanked out (present in initialValues, absent from `edited` because
    // openLinkPropertiesDialog omits blank fields -- same convention as openLinkCreateDialog's own
    // confirm handler) becomes an explicit null rather than being silently dropped from the diff.
    const current = linkEntry.properties || {};
    const delta = {};
    for (const p of propertyDefs) {
      const newVal = Object.prototype.hasOwnProperty.call(edited, p.name) ? edited[p.name] : null;
      if (newVal !== (current[p.name] ?? null)) delta[p.name] = newVal;
    }
    if (Object.keys(delta).length === 0) return;
    try {
      const response = await fetch('/api/mutation', {
        method: 'POST',
        credentials: 'include',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ links: [{ type: 'UPDATE', linkId, properties: delta }] }),
      });
      if (!response.ok) {
        const body = await response.json().catch(() => null);
        throw new Error(body?.errors?.[0]?.message || 'Update failed: ' + response.status);
      }
      this.reprojectPanesForTypes([item.itemType, otherItemType]);
    } catch (e) {
      alert('Update link failed: ' + e.message);
    }
  }

  // Called once at dragstart (not per dragenter, unlike the pane-reorder preview -- there's no
  // live "preview order" to keep recomputing here, just a fixed valid/invalid classification of
  // every currently-rendered item-card and link-group). Writes '.valid-drop-target' straight into
  // the DOM, matching every other item-card and link-group's own class list; cleared again by
  // clearItemDragHighlights() at dragend.
  applyItemDragHighlights(draggedItem) {
    this.querySelectorAll('.item-card[data-item-id]').forEach(cardEl => {
      const targetItemId = cardEl.dataset.itemId;
      if (targetItemId === draggedItem.itemId) return;
      const targetPaneId = Number(cardEl.closest('[data-pane-id]')?.dataset.paneId);
      const targetPane = this.pane(targetPaneId);
      const targetItem = targetPane?.results.find(r => r.itemId === targetItemId);
      if (!targetItem) return;
      const candidates = this.computeLinkCandidates(draggedItem, targetItem);
      if (candidates.length === 0) return;
      cardEl.classList.add('valid-drop-target');
      cardEl.querySelectorAll('.link-group[data-perspective]').forEach(groupEl => {
        if (candidates.some(c => c.toPerspective === groupEl.dataset.perspective)) {
          groupEl.classList.add('valid-drop-target');
        }
      });
    });
  }

  clearItemDragHighlights() {
    this.querySelectorAll('.valid-drop-target').forEach(el => el.classList.remove('valid-drop-target'));
  }

  // Shared by both drop paths below: resolves candidates down to a chosen {linkId, fromPerspective,
  // toPerspective, properties}, opening the picker/properties dialog only when the candidate list or
  // its properties actually require user input (see ntrloc-link-create-dialog.js), then creates the
  // link. `candidates` is passed in already filtered -- full list for a plain item-card drop, pinned
  // to one perspective for a link-group drop (see wireUp).
  async resolveAndCreateLink(candidates, draggedItem, targetItem) {
    if (candidates.length === 0) return;
    let chosen;
    if (candidates.length === 1 && candidates[0].propertyDefs.length === 0) {
      chosen = { ...candidates[0], properties: {} };
    } else {
      chosen = await openLinkCreateDialog(candidates);
      if (!chosen) return;
    }
    await this.createLink(chosen, draggedItem, targetItem, chosen.properties);
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
    const canEditProps = item.permissions?.edit?.length > 0;
    const canDelete = !!item.permissions?.delete;

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
      <div class="item-card" data-item-id="${item.itemId}" data-item-type="${escapeHtml(item.itemType)}">
        <div class="item-card-header" draggable="true">
          <div>
            <span class="item-card-title">${escapeHtml(title)}</span>
            <span class="item-card-id">${shortId}</span>
          </div>
          ${canEditProps || canDelete ? `<div class="item-card-actions">
            ${canEditProps ? `<button class="${isEditing ? 'editing' : ''}" data-action="toggle-edit">${isEditing ? 'Editing' : 'Edit'}</button>` : ''}
            ${canDelete && !isEditing ? `<button class="delete-btn" data-action="delete-item">Delete</button>` : ''}
          </div>` : ''}
        </div>
        <div class="prop-grid ${isEditing ? 'is-editing' : ''}">${rows}</div>
        ${this.renderLinkGroups(pane, item)}
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

  // Grouped by perspective name (the shape the projection response already comes in --
  // { perspectiveName: [{linkId, properties, item}, ...] }, so no client-side grouping logic is
  // actually needed, just per-group rendering), each group independently collapsible so a large
  // perspective can be pushed out of view without hiding the others. The nested item is rendered
  // one level deep only, matching what the backend actually returns (a linked item's own `links`
  // always comes back empty in this response) -- no risk of runaway recursive nesting.
  renderLinkGroups(pane, item) {
    const links = item.links || {};
    const perspectiveNames = Object.keys(links).sort();
    if (perspectiveNames.length === 0) return '';
    const collapsed = pane.collapsedLinkGroups[item.itemId] || new Set();
    return `
      <div class="link-groups">
        ${perspectiveNames.map(name => {
          const linkedItems = links[name];
          const isCollapsed = collapsed.has(name);
          const propertyDefs = this.linkPropertyDefsFor(item.itemType, name);
          return `
            <div class="link-group" data-perspective="${escapeHtml(name)}">
              <button class="link-group-header" data-action="toggle-link-group" data-perspective="${escapeHtml(name)}">
                <svg class="chevron ${isCollapsed ? 'collapsed' : ''}" viewBox="0 0 24 24" width="14" height="14"
                     fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round">
                  <polyline points="6 9 12 15 18 9"></polyline>
                </svg>
                <span class="link-group-label">${escapeHtml(name)}</span>
                <span class="link-group-count">${linkedItems.length}</span>
              </button>
              ${!isCollapsed ? `
                <div class="link-group-body">
                  ${linkedItems.map(l => this.renderLinkedItemCard(l, propertyDefs)).join('')}
                </div>
              ` : ''}
            </div>
          `;
        }).join('')}
      </div>
    `;
  }

  // Resolves a perspective name to its link type's own property *definitions* -- two hops, since
  // a perspective (AdminItemLinkPerspectiveView) only carries the schema-level link-type id, and
  // the property definitions themselves live on the link type (AdminLinkView), keyed by that id
  // (see cacheSchemaSideTables). Returns [] for a perspective with no properties or one this
  // client's schema cache doesn't (yet) recognize, rather than throwing -- callers all treat an
  // empty list as "nothing to show/edit", which is the correct fallback either way.
  linkPropertyDefsFor(itemTypeName, perspectiveName) {
    const type = this.itemTypesByName.get(itemTypeName);
    const perspectiveEntry = (type?.links[perspectiveName] || [])[0];
    if (!perspectiveEntry) return [];
    return this.linkDefsById.get(perspectiveEntry.linkId)?.properties || [];
  }

  // Item section alone -- no unlink button here anymore (see renderLinkedItemCard's own comment
  // on why it moved out to the card's action rail instead).
  renderNestedItemSection(linkedItem, shortId) {
    const propEntries = Object.entries(linkedItem.properties || {}).sort((a, b) => a[0].localeCompare(b[0]));
    return `
      <div class="nested-item-section">
        <div class="nested-item-header">
          <span>${escapeHtml(linkedItem.itemType)}</span>
          <span class="nested-item-id">${shortId}</span>
        </div>
        <div class="prop-grid">
          ${propEntries.map(([key, val]) => `
            <div class="prop-row">
              <div class="prop-key">${escapeHtml(key)}</div>
              <div class="prop-value">${this.renderPropertyValue(val)}</div>
            </div>
          `).join('')}
        </div>
      </div>
    `;
  }

  renderLinkedItemCard(linkEntry, propertyDefs) {
    const linkedItem = linkEntry.item;
    const shortId = linkedItem.itemId.substring(0, 8) + '...';
    const title = linkedItem.properties.title || linkedItem.properties.name || linkedItem.itemType;
    // Only properties the link actually has a value for -- propertyDefs is every property the
    // link TYPE could carry, not every property THIS link instance has actually set.
    const linkPropEntries = (propertyDefs || [])
      .filter(p => linkEntry.properties && linkEntry.properties[p.name] !== undefined && linkEntry.properties[p.name] !== null)
      .map(p => [p.name, linkEntry.properties[p.name]]);
    const itemSection = this.renderNestedItemSection(linkedItem, shortId);

    const body = propertyDefs.length === 0 ? itemSection : `
      <div class="nested-item-sections">
        <div class="nested-item-section">
          <div class="nested-item-header">
            <span class="nested-item-section-label">Link Properties</span>
            <button class="edit-link-button" title="Edit link properties" aria-label="Edit link properties" data-action="edit-link">
              <svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
                <path d="M12 20h9"></path>
                <path d="M16.5 3.5a2.121 2.121 0 0 1 3 3L7 19l-4 1 1-4Z"></path>
              </svg>
            </button>
          </div>
          ${linkPropEntries.length > 0 ? `
            <div class="prop-grid">
              ${linkPropEntries.map(([key, val]) => `
                <div class="prop-row">
                  <div class="prop-key">${escapeHtml(key)}</div>
                  <div class="prop-value">${this.renderPropertyValue(val)}</div>
                </div>
              `).join('')}
            </div>
          ` : `<div class="nested-empty-note">No properties set</div>`}
        </div>
        ${itemSection}
      </div>
    `;

    // unlink lives on the card itself, to the left of everything it groups together (link
    // properties + item, or just item), since it removes the LINK -- not either section
    // individually the way edit-link (scoped to just the link-properties section) still does.
    return `
      <div class="nested-item-card" data-link-id="${escapeHtml(linkEntry.linkId)}" data-item-type="${escapeHtml(linkedItem.itemType)}" data-item-title="${escapeHtml(title)}">
        <button class="unlink-button" title="Remove link" aria-label="Remove link" data-action="unlink">
          <svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
            <polyline points="3 6 5 6 21 6"></polyline>
            <path d="M19 6l-1 14a2 2 0 0 1-2 2H8a2 2 0 0 1-2-2L5 6"></path>
            <path d="M10 11v6"></path>
            <path d="M14 11v6"></path>
            <path d="M9 6V4a1 1 0 0 1 1-1h4a1 1 0 0 1 1 1v2"></path>
          </svg>
        </button>
        <div class="nested-item-body">${body}</div>
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
          if (action === 'delete-item') el.addEventListener('click', () => this.deleteItem(id, itemId));
          if (action === 'add-prop') el.addEventListener('click', () => this.addProperty(id, itemId));
          if (action === 'remove-prop') el.addEventListener('click', () => this.removeProperty(id, itemId, el.dataset.prop));
          if (action === 'undo-remove') el.addEventListener('click', () => this.undoRemoveProperty(id, itemId, el.dataset.prop));
          if (action === 'edit-value') el.addEventListener('input', e => this.updateEditValue(id, itemId, el.dataset.prop, e.target.value));
          if (action === 'toggle-link-group') el.addEventListener('click', () => this.toggleLinkGroup(id, itemId, el.dataset.perspective));
          // Nested inside cardEl (a linked item's own card, inside a link-group), not one of the
          // outer item's own actions above -- reads the linkId/type off the nested card itself
          // rather than the outer item, since that's what actually identifies the link to delete.
          if (action === 'unlink') {
            el.addEventListener('click', (e) => {
              e.stopPropagation();
              const nestedEl = el.closest('.nested-item-card');
              this.unlinkItem(id, nestedEl.dataset.linkId, nestedEl.dataset.itemType, nestedEl.dataset.itemTitle);
            });
          }
          // Perspective name isn't on the nested card itself (it's shared by every card in the
          // group) -- read off the ancestor .link-group instead of threading it through as its
          // own data attribute on every single nested card.
          if (action === 'edit-link') {
            el.addEventListener('click', (e) => {
              e.stopPropagation();
              const nestedEl = el.closest('.nested-item-card');
              const perspective = el.closest('.link-group').dataset.perspective;
              this.editLinkProperties(id, itemId, perspective, nestedEl.dataset.linkId, nestedEl.dataset.itemType, nestedEl.dataset.itemTitle);
            });
          }
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

      // draggable="true" lives on .item-card-header only, for the same reason as .pane-header
      // above -- an ancestor-wide draggable would hijack click-drag text selection and edit-mode
      // <input> focus inside the card body.
      paneEl.querySelectorAll('.item-card[data-item-id]').forEach(cardEl => {
        const itemId = cardEl.dataset.itemId;
        const headerEl = cardEl.querySelector('.item-card-header');
        headerEl.addEventListener('dragstart', e => {
          const pane = this.pane(id);
          const item = pane.results.find(r => r.itemId === itemId);
          if (!item) return;
          this.draggingItem = item;
          e.dataTransfer.effectAllowed = 'link';
          e.dataTransfer.setData('text/plain', itemId);
          e.dataTransfer.setDragImage(cardEl, e.offsetX, e.offsetY);
          setTimeout(() => cardEl.classList.add('is-drag-source'), 0);
          this.applyItemDragHighlights(item);
        });
        headerEl.addEventListener('dragend', () => {
          cardEl.classList.remove('is-drag-source');
          this.draggingItem = null;
          this.clearItemDragHighlights();
        });

        cardEl.addEventListener('dragover', e => {
          if (this.draggingItem === null || !cardEl.classList.contains('valid-drop-target')) return;
          e.preventDefault();
          e.dataTransfer.dropEffect = 'link';
        });
        cardEl.addEventListener('drop', e => {
          if (this.draggingItem === null) return;
          e.preventDefault();
          const dragged = this.draggingItem;
          const pane = this.pane(id);
          const target = pane.results.find(r => r.itemId === itemId);
          if (!target) return;
          const candidates = this.computeLinkCandidates(dragged, target);
          this.resolveAndCreateLink(candidates, dragged, target);
        });

        // Listeners scoped to each .link-group individually (not delegated from cardEl) so
        // stopPropagation cleanly prevents the ancestor .item-card's own dragover/drop above from
        // also firing -- a drop pinned to one perspective shouldn't also run full resolution.
        cardEl.querySelectorAll('.link-group[data-perspective]').forEach(groupEl => {
          groupEl.addEventListener('dragover', e => {
            if (this.draggingItem === null || !groupEl.classList.contains('valid-drop-target')) return;
            e.preventDefault();
            e.stopPropagation();
            e.dataTransfer.dropEffect = 'link';
          });
          groupEl.addEventListener('drop', e => {
            if (this.draggingItem === null) return;
            e.preventDefault();
            e.stopPropagation();
            const dragged = this.draggingItem;
            const pane = this.pane(id);
            const target = pane.results.find(r => r.itemId === itemId);
            if (!target) return;
            const perspective = groupEl.dataset.perspective;
            const candidates = this.computeLinkCandidates(dragged, target).filter(c => c.toPerspective === perspective);
            this.resolveAndCreateLink(candidates, dragged, target);
          });
        });
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
