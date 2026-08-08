injectStyles('ntrloc-links-table-styles', `
  ntrloc-links-table {
    display: contents;
  }
  /* CSS Grid instead of a <table>, matching the Angular reference's links-table.scss exactly
     (same grid-template-columns) -- see ntrloc-property-table.js's comment for the full
     rationale (ports Angular's actual structure instead of a table-layout approximation of it,
     and avoids nesting a <table> inside a <td> for this row's own property grid). */
  .links-grid {
    display: grid;
    grid-template-columns: 8px minmax(100px, 180px) minmax(80px, 140px) auto 1fr;
    column-gap: 16px;
    align-items: start;
    width: 100%;
  }
  .links-grid .grid-row {
    display: contents;
  }
  .links-grid .grid-header {
    font-size: 11px;
    color: var(--muted);
    font-weight: bold;
    letter-spacing: 0.05em;
    padding-bottom: 6px;
    border-bottom: 1px solid var(--border);
  }
  .links-grid .grid-cell {
    padding: 4px 0;
  }
  /* Each perspective is two grid rows, not one: the name/target/cardinality/actions cells fill
     the normal columns above, and this cell spans the full row width (grid-column: 1 / -1 forces
     grid auto-placement onto its own row, since nothing else can share a row with something that
     wide) directly beneath -- the property sub-table gets the whole panel's width to lay out its
     own Name/Description/Type/Cardinality/Usage columns instead of being squeezed into a single
     column of the outer grid. The bottom border that used to sit under every cell now lives here
     only, so each perspective still reads as one row-separated block, not two. */
  .links-grid .properties-cell {
    grid-column: 1 / -1;
    padding: 8px 0 12px 24px;
    border-bottom: 1px solid var(--border);
  }
  .links-grid .properties-label {
    display: block;
    font-size: 11px;
    color: var(--muted);
    font-weight: bold;
    letter-spacing: 0.05em;
    margin-bottom: 4px;
  }
  .links-grid .row-deleted .grid-cell {
    opacity: 0.5;
  }
  .links-table-dirty-dot {
    color: var(--dirty-color, #e3b341);
  }
  .links-table-dirty-dot.is-deleted {
    color: var(--deleted-color, #f85149);
  }
  .links-grid .collapse-button {
    cursor: pointer;
    background: none;
    border: none;
    color: var(--text);
    font-size: 12px;
    margin-right: 4px;
  }
  .links-grid .target-kind {
    color: var(--muted);
    font-size: 11px;
    margin-left: 4px;
  }
  .links-grid .defined-in-badge {
    display: block;
    font-size: 11px;
    font-style: italic;
    opacity: 0.6;
    color: var(--accent);
    margin-top: 2px;
  }
  .links-grid .read-only-value {
    display: block;
    padding: 4px 0;
    font-size: 13px;
    color: var(--text);
    min-height: 1em;
  }
  .links-grid .grid-row.row-clickable {
    cursor: pointer;
  }
  .links-grid .grid-row.row-clickable:hover .grid-cell:not(.properties-cell) {
    background: var(--panel-bg);
  }
  .links-grid .cardinality-inputs {
    display: flex;
    align-items: center;
    gap: 4px;
  }
  /* A Material text field here (tried first) turned out to be the "hard to read/use" complaint:
     each one is ~56px wide with no label, so the native number-input spin buttons ate most of
     that width and squeezed the actual digit into a sliver -- confirmed by comparing against the
     Angular reference, whose own .cardinality-input is a plain <input> (not wrapped in any
     Material field) with the spinner explicitly suppressed and a monospace, centered digit.
     Matching that exactly here rather than reinventing it. */
  .links-grid .cardinality-input {
    width: 3ch;
    text-align: center;
    font-family: monospace;
    font-size: 13px;
    background: transparent;
    border: none;
    border-bottom: 1px solid transparent;
    color: var(--text);
    padding: 2px 0;
    outline: none;
  }
  .links-grid .cardinality-input:focus {
    border-bottom-color: var(--accent);
  }
  .links-grid .cardinality-input:disabled {
    opacity: 0.5;
  }
  .links-grid .cardinality-input::-webkit-inner-spin-button,
  .links-grid .cardinality-input::-webkit-outer-spin-button {
    appearance: none;
    margin: 0;
  }
  .links-grid .cardinality-input[type=number] {
    -moz-appearance: textfield;
  }
  .links-grid .editable-field {
    width: 100%;
    --md-filled-text-field-top-space: 4px;
    --md-filled-text-field-bottom-space: 4px;
  }
  .links-grid .original-value {
    font-size: 11px;
    color: var(--muted);
    text-decoration: line-through;
    margin-top: 2px;
  }
  /* Same fix as ntrloc-property-table.js's actions-cell: md-text-button's 40px default height
     was the tallest thing in the row once everything else was compacted. justify-content
     right-aligns the buttons within this cell -- the actions column is the grid's only 1fr
     track, so it soaks up whatever width the fixed/auto name/target/cardinality columns don't
     use and pushes its own content to the panel's right edge instead of sitting flush against
     Cardinality. */
  .links-grid .actions-cell {
    display: flex;
    align-items: center;
    justify-content: flex-end;
    gap: 2px;
    white-space: nowrap;
  }
  .links-grid .restore-button, .links-grid .done-button {
    --md-text-button-container-height: 30px;
  }
  /* No icon font is vendored in this app (see ntrloc-item-detail.js's chevron-SVG comment for
     the rationale) -- a small stroked inline SVG, matching that same convention, instead of an
     <md-text-button>"Delete" label once there's a Restore/Done text button right next to it on
     every row; the icon plus a native title-attribute tooltip reads clearly without the extra
     width. */
  .links-grid .icon-button {
    display: inline-flex;
    align-items: center;
    justify-content: center;
    width: 30px;
    height: 30px;
    padding: 0;
    background: none;
    border: none;
    border-radius: 6px;
    color: var(--muted);
    cursor: pointer;
  }
  .links-grid .icon-button:hover {
    background: var(--panel-bg);
    color: var(--deleted-color, #f85149);
  }
`);

// Maps a read-only-value cell's field class (see render()) to the selector for the control it
// becomes once the row opens for editing -- lets the row-click handler focus whichever field the
// user actually clicked, not always Name.
const LINK_FIELD_FOCUS_TARGETS = {
  'name-value': '.name-input',
  'cardinality-value': '.min-input',
};

// Flattens the { linkName: perspective[] } record into rows and renders them, one row per
// perspective, with a collapsible group header when a link name has more than one perspective
// (e.g. a self-referential link with two distinct roles). Direct port of the Angular reference's
// links-table.ts buildRows()/recomputeVisibleRows() logic. Each row's own properties are rendered
// via a nested <ntrloc-property-table> against the shared LinkViewModel's properties array.
class NtrlocLinksTable extends HTMLElement {
  constructor() {
    super();
    this._collapsedGroups = new Set();
    // connectedCallback fires synchronously while the parent is still building its innerHTML --
    // before the .data setter (called afterward, from the parent's wireUp()) ever runs
    // _buildRows(). Without this, the resulting connectedCallback -> render() -> _visibleRows()
    // call throws on undefined _rows; the browser silently reports it without interrupting the
    // parent's render (custom element reaction exceptions don't propagate to the caller), so the
    // UI still ends up correct once .data is set moments later, but it was a real error on every
    // render, confirmed via the console.
    this._rows = [];
  }

  set data({ links, propertyTypes }) {
    this._links = links || {};
    this._propertyTypes = propertyTypes || [];
    this._buildRows();
    this.render();
  }

  connectedCallback() {
    this.render();
  }

  _buildRows() {
    this._rows = [];
    // Alphabetical by link/perspective name -- rows are looked up by their own `key` everywhere
    // else (wireUp(), _visibleRows()), never by array position, so reordering this construction
    // is display-only and doesn't risk any indexing mismatch the way property-table's sort does.
    const sortedEntries = Object.entries(this._links).sort(([a], [b]) => a.localeCompare(b));
    for (const [groupName, perspectives] of sortedEntries) {
      const isCollapsible = perspectives.length > 1;
      perspectives.forEach((vm, i) => {
        this._rows.push({ key: `${groupName}::${i}`, groupName, showGroupName: i === 0, isCollapsible, vm });
      });
    }
  }

  _visibleRows() {
    const seen = new Set();
    return this._rows.filter((row) => {
      if (!seen.has(row.groupName)) {
        seen.add(row.groupName);
        return true;
      }
      return !this._collapsedGroups.has(row.groupName);
    });
  }

  _formatMax(max) {
    return max === null || max === undefined ? '∞' : String(max);
  }

  render() {
    const rows = this._visibleRows();
    this.innerHTML = `
      <div class="links-grid" role="grid" aria-label="Links">
        <div class="grid-row" role="row">
          <div class="grid-header" role="columnheader"></div>
          <div class="grid-header" role="columnheader">Name</div>
          <div class="grid-header" role="columnheader">Target</div>
          <div class="grid-header" role="columnheader">Cardinality</div>
          <div class="grid-header" role="columnheader"></div>
        </div>
        ${rows.map((row) => {
          const editable = !row.vm.isDeleted && !row.vm.isReadonly;
          const showForm = editable && row.vm.isEditing;
          return `
          <div class="grid-row ${row.vm.isDeleted ? 'row-deleted' : ''} ${editable && !row.vm.isEditing ? 'row-clickable' : ''}" role="row" data-key="${escapeHtml(row.key)}">
            <div class="grid-cell" role="gridcell">${row.vm.isDirty ? `<span class="links-table-dirty-dot ${row.vm.isDeleted ? 'is-deleted' : ''}">●</span>` : ''}</div>
            <div class="grid-cell" role="gridcell">
              ${row.showGroupName && row.isCollapsible ? `<button class="collapse-button" data-group="${escapeHtml(row.groupName)}">${this._collapsedGroups.has(row.groupName) ? '▸' : '▾'}</button>` : ''}
              ${showForm
                ? `<md-filled-text-field class="editable-field name-input" value="${escapeHtml(row.vm.name)}"></md-filled-text-field>`
                : `<span class="read-only-value name-value">${escapeHtml(row.vm.name)}</span>`}
              ${!row.vm.isDeleted && row.vm.name !== row.vm.originalName && row.vm.originalName ? `<div class="original-value">${escapeHtml(row.vm.originalName)}</div>` : ''}
            </div>
            <div class="grid-cell" role="gridcell">
              ${row.vm.targets.map((t) => `<span class="target-name">${escapeHtml(t.name)}</span><span class="target-kind">${escapeHtml(t.kind)}</span>`).join(' / ')}
              ${row.vm.definedIn ? `<span class="defined-in-badge">via ${escapeHtml(row.vm.definedIn.entityName)}</span>` : ''}
            </div>
            <div class="grid-cell" role="gridcell">
              ${showForm ? `
                <div class="cardinality-inputs">
                  <input type="number" min="0" class="cardinality-input min-input" value="${row.vm.minCardinality}" />
                  <span>..</span>
                  <input type="number" min="0" class="cardinality-input max-input" value="${row.vm.maxCardinality ?? ''}" placeholder="∞" />
                </div>
              ` : `<span class="read-only-value cardinality-value">${row.vm.minCardinality}..${this._formatMax(row.vm.maxCardinality)}</span>`}
              ${!row.vm.isDeleted && (row.vm.minCardinality !== row.vm.originalMinCardinality || row.vm.maxCardinality !== row.vm.originalMaxCardinality) ? `<div class="original-value">${row.vm.originalMinCardinality}..${this._formatMax(row.vm.originalMaxCardinality)}</div>` : ''}
            </div>
            <div class="grid-cell actions-cell" role="gridcell">
              ${showForm ? '<md-text-button class="done-button">Done</md-text-button>' : ''}
              ${!row.vm.isReadonly ? (
                row.vm.isDeleted
                  ? '<md-text-button class="restore-button">Restore</md-text-button>'
                  : `
                    <button class="icon-button delete-button" title="Delete" aria-label="Delete">
                      <svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
                        <polyline points="3 6 5 6 21 6"></polyline>
                        <path d="M19 6l-1 14a2 2 0 0 1-2 2H8a2 2 0 0 1-2-2L5 6"></path>
                        <path d="M10 11v6"></path>
                        <path d="M14 11v6"></path>
                        <path d="M9 6V4a1 1 0 0 1 1-1h4a1 1 0 0 1 1 1v2"></path>
                      </svg>
                    </button>
                  `
              ) : ''}
            </div>
            ${row.vm.isDeleted ? '' : `
              <div class="grid-cell properties-cell" role="gridcell">
                <span class="properties-label">Properties</span>
                <ntrloc-property-table data-key="link-${escapeHtml(row.vm.linkId)}"></ntrloc-property-table>
              </div>
            `}
          </div>
        `;
        }).join('')}
      </div>
    `;
    this.wireUp();
  }

  wireUp() {
    this.querySelectorAll('.collapse-button').forEach((button) => {
      button.addEventListener('click', () => {
        const group = button.dataset.group;
        if (this._collapsedGroups.has(group)) this._collapsedGroups.delete(group);
        else this._collapsedGroups.add(group);
        this.render();
      });
    });

    this.querySelectorAll('.grid-row[data-key]').forEach((row) => {
      const key = row.dataset.key;
      const entry = this._rows.find((r) => r.key === key);
      if (!entry) return;
      const vm = entry.vm;

      if (row.classList.contains('row-clickable')) {
        row.addEventListener('click', (event) => {
          // Excludes clicks on the collapse toggle, the row's own action buttons, and the nested
          // property sub-table -- each of those is already independently clickable and shouldn't
          // also open this row into edit mode.
          if (event.target.closest('.actions-cell, .properties-cell, .collapse-button')) return;
          // Whichever read-only field the click actually landed in gets focused once the row
          // opens, not always Name -- read-only-value carries a second class naming its field
          // (name-value/cardinality-value, see render()) that maps to the input it becomes.
          const clickedValue = event.target.closest('.read-only-value');
          const fieldClass = clickedValue ? [...clickedValue.classList].find((c) => c !== 'read-only-value') : null;
          const preferredSelector = LINK_FIELD_FOCUS_TARGETS[fieldClass] ?? '.name-input';
          vm.isEditing = true;
          this.render();
          const newRow = Array.from(this.querySelectorAll('.grid-row[data-key]')).find((r) => r.dataset.key === key);
          const target = newRow ? (newRow.querySelector(preferredSelector) ?? newRow.querySelector('.name-input')) : null;
          if (target) Promise.resolve(target.updateComplete).then(() => target.focus());
        });
      }

      const doneButton = row.querySelector('.done-button');
      if (doneButton) doneButton.addEventListener('click', () => {
        vm.isEditing = false;
        this.render();
      });

      const nameInput = row.querySelector('.name-input');
      if (nameInput) nameInput.addEventListener('change', (event) => {
        vm.name = event.target.value;
        this.render();
        notifySchemaViewModelChange();
      });

      const minInput = row.querySelector('.min-input');
      if (minInput) minInput.addEventListener('change', (event) => {
        vm.minCardinality = event.target.value === '' ? 0 : Number(event.target.value);
        this.render();
        notifySchemaViewModelChange();
      });

      const maxInput = row.querySelector('.max-input');
      if (maxInput) maxInput.addEventListener('change', (event) => {
        vm.maxCardinality = event.target.value === '' ? null : Number(event.target.value);
        this.render();
        notifySchemaViewModelChange();
      });

      const deleteButton = row.querySelector('.delete-button');
      if (deleteButton) deleteButton.addEventListener('click', () => {
        vm.isDeleted = true;
        this.render();
        notifySchemaViewModelChange();
      });

      const restoreButton = row.querySelector('.restore-button');
      if (restoreButton) restoreButton.addEventListener('click', () => {
        vm.isDeleted = false;
        this.render();
        notifySchemaViewModelChange();
      });

      const propertyTable = row.querySelector('ntrloc-property-table');
      if (propertyTable) {
        propertyTable.data = { properties: vm.link.properties, propertyTypes: this._propertyTypes, allowAdd: !vm.isReadonly };
      }
    });
  }
}

function escapeHtml(value) {
  const div = document.createElement('div');
  div.textContent = String(value ?? '');
  return div.innerHTML;
}

customElements.define('ntrloc-links-table', NtrlocLinksTable);
