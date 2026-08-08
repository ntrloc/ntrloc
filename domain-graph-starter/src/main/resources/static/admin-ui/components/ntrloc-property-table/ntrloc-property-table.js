injectStyles('ntrloc-property-table-styles', `
  ntrloc-property-table {
    display: contents;
  }
  /* CSS Grid instead of a <table>, matching the Angular reference's property-grid.scss exactly
     (same grid-template-columns) rather than a table-layout approximation of it -- ports the
     Angular structure directly instead of an approximation of it, and sidesteps table-layout
     quirks (auto-sized columns fighting fixed-width form controls, a nested table-in-a-cell for
     link properties). Row grouping is done with a display:contents wrapper per row (the same
     technique already used for the outer custom element itself) purely so each row can carry
     role="row" and a data-index for wireUp() to query against -- it has zero effect on the grid
     layout itself, which only "sees" the actual cell divs. */
  .property-grid {
    display: grid;
    grid-template-columns: 12px 1fr 2fr 1fr 1fr auto auto;
    gap: 4px 16px;
    align-items: center;
    width: 100%;
  }
  .property-grid .grid-row {
    display: contents;
  }
  .property-grid .grid-header {
    font-size: 11px;
    color: var(--muted);
    font-weight: bold;
    letter-spacing: 0.05em;
    padding-bottom: 6px;
    border-bottom: 1px solid var(--border);
    align-self: end;
  }
  .property-grid .grid-cell {
    padding: 2px 0;
    align-self: center;
  }
  .property-grid .row-deleted .grid-cell {
    opacity: 0.5;
  }
  .prop-grid-dirty-dot {
    color: var(--dirty-color, #e3b341);
  }
  .prop-grid-dirty-dot.is-new {
    color: var(--new-color, #3fb950);
  }
  .prop-grid-dirty-dot.is-deleted {
    color: var(--deleted-color, #f85149);
  }
  .property-grid .editable-field {
    width: 100%;
    --md-filled-text-field-top-space: 4px;
    --md-filled-text-field-bottom-space: 4px;
  }
  /* md-filled-select has no *-text-field-top/bottom-space alias of its own (unlike
     md-filled-text-field) -- it reads the shared underlying --md-filled-field-top/bottom-space
     tokens directly, so that's what has to be set here to make the two match heights. */
  .property-grid md-filled-select.editable-field {
    --md-filled-field-top-space: 4px;
    --md-filled-field-bottom-space: 4px;
  }
  .property-grid .original-value {
    font-size: 11px;
    color: var(--muted);
    text-decoration: line-through;
    margin-top: 2px;
  }
  .property-grid .defined-in-badge {
    display: block;
    font-size: 11px;
    font-style: italic;
    opacity: 0.6;
    color: var(--accent);
    margin-top: 2px;
  }
  .property-grid .read-only-value {
    display: block;
    padding: 4px 0;
    font-size: 13px;
    color: var(--text);
    min-height: 1em;
  }
  .property-grid .grid-row.row-clickable {
    cursor: pointer;
  }
  .property-grid .grid-row.row-clickable:hover .grid-cell {
    background: var(--panel-bg);
  }
  .property-grid .actions-cell {
    display: flex;
    align-items: center;
    gap: 2px;
    white-space: nowrap;
  }
  /* md-text-button defaults to a 40px-tall container -- once the row's text fields were
     compacted, this became the tallest thing in the row and the actual floor on row height.
     30px still comfortably fits the button's own label text/click target. */
  .property-grid .actions-cell md-text-button {
    --md-text-button-container-height: 30px;
  }
  /* No icon font is vendored in this app (see ntrloc-item-detail.js's chevron-SVG comment) --
     a small stroked inline SVG instead of an <md-text-button>"Delete" label, matching
     ntrloc-links-table.js's identical icon-button treatment for its own Delete action. */
  .property-grid .icon-button {
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
  .property-grid .icon-button:hover {
    background: var(--panel-bg);
    color: var(--deleted-color, #f85149);
  }
  .add-property-row {
    margin-top: 8px;
  }
  .property-grid .filter-row .grid-cell {
    padding-bottom: 6px;
  }
  .property-grid .filter-cell {
    position: relative;
  }
  .property-grid .filter-input {
    width: 100%;
    box-sizing: border-box;
    padding: 4px 24px 4px 6px;
    font-size: 13px;
    font-family: inherit;
    background: var(--panel-bg);
    border: 1px solid var(--border);
    border-radius: 4px;
    color: var(--text);
    outline: none;
  }
  .property-grid .filter-input:focus {
    border-color: var(--accent);
  }
  .property-grid .filter-clear-button {
    position: absolute;
    right: 4px;
    top: 50%;
    transform: translateY(-50%);
    background: none;
    border: none;
    color: var(--muted);
    cursor: pointer;
    font-size: 13px;
    line-height: 1;
    padding: 2px 4px;
  }
  .property-grid .filter-clear-button:hover {
    color: var(--text);
  }
  .property-grid .filter-empty-status {
    grid-column: 1 / -1;
  }
`);

// Maps a read-only-value cell's field class (see render()) to the selector for the control it
// becomes once the row opens for editing -- lets the row-click handler focus whichever field the
// user actually clicked, not always Name. type-select/cardinality-select only actually render in
// some states (see render()'s own conditions), so a miss here falls back to .name-input rather
// than silently focusing nothing.
const PROPERTY_FIELD_FOCUS_TARGETS = {
  'name-value': '.name-input',
  'description-value': '.description-input',
  'type-value': '.type-select',
  'cardinality-value': '.cardinality-select',
  'usage-value': '.usage-select',
};

// Reusable editable property grid -- used both for an item/trait's own properties and for a
// link's nested properties (previously duplicated markup in each place). Given { properties,
// propertyTypes, allowAdd } via the .data setter; mutates the PropertyDefinitionViewModel objects
// in `properties` directly by reference (they belong to whatever ItemDefinitionViewModel/
// TraitDefinitionViewModel/LinkViewModel the caller holds) and calls notifySchemaViewModelChange()
// after every edit -- this component does not call the mutation API itself, matching the
// established "dumb presentation component" convention from before this rewrite.
class NtrlocPropertyTable extends HTMLElement {
  constructor() {
    super();
    // Instance state, not view-model state -- filtering is a display-only concern, same class of
    // trade-off as ntrloc-links-table.js's own _collapsedGroups: it resets if some unrelated edit
    // elsewhere in the panel triggers a full notifySchemaViewModelChange() rebuild (which replaces
    // this component with a fresh instance), but persists correctly across every keystroke within
    // a single filtering session, since typing in the filter itself only ever calls a local
    // this.render(), never notify.
    this._nameFilter = '';
    this._descriptionFilter = '';
  }

  set data({ properties, propertyTypes, allowAdd }) {
    this._properties = properties || [];
    this._propertyTypes = propertyTypes || [];
    this._allowAdd = !!allowAdd;
    this.render();
  }

  get data() {
    return this._properties || [];
  }

  connectedCallback() {
    this.render();
  }

  hasControlledList(prop) {
    const CONTROLLED_LIST_TYPES = new Set(['STRING', 'INT', 'LONG']);
    return !prop.isNew && prop.controlledListId != null && CONTROLLED_LIST_TYPES.has(prop.type);
  }

  render() {
    const props = this._properties || [];
    // Display order only -- sorts a list of indices into the real (unsorted) storage array
    // rather than the array itself, so data-index below still refers to the true position in
    // this._properties and every existing wireUp() lookup (this._properties[index]) keeps
    // working unchanged. Sorting props itself would also risk reshuffling a row's storage
    // position out from under an in-flight edit.
    const displayOrder = props.map((_, index) => index).sort((a, b) => props[a].name.localeCompare(props[b].name));
    // Case-insensitive substring match, independently per column (both must match when both are
    // set). A row currently new or mid-edit is never hidden by its own now-stale match state --
    // without that, adding a property while a filter is active would make it vanish immediately
    // (blank name doesn't match anything), and renaming a row you're actively editing would make
    // it disappear out from under you the moment it stops matching.
    const nameFilter = this._nameFilter.trim().toLowerCase();
    const descriptionFilter = this._descriptionFilter.trim().toLowerCase();
    const filteredOrder = displayOrder.filter((index) => {
      const prop = props[index];
      if (prop.isNew || prop.isEditing) return true;
      const nameMatches = !nameFilter || prop.name.toLowerCase().includes(nameFilter);
      const descriptionMatches = !descriptionFilter || (prop.description ?? '').toLowerCase().includes(descriptionFilter);
      return nameMatches && descriptionMatches;
    });
    this.innerHTML = `
      ${props.length === 0 ? '<p class="status">No properties defined.</p>' : `
        <div class="property-grid" role="grid" aria-label="Properties">
          <div class="grid-row filter-row" role="row">
            <div class="grid-cell" role="gridcell"></div>
            <div class="grid-cell filter-cell" role="gridcell">
              <input type="text" class="filter-input name-filter-input" placeholder="Filter by name" value="${escapeHtml(this._nameFilter)}" />
              ${this._nameFilter ? '<button class="filter-clear-button name-filter-clear" title="Clear filter" aria-label="Clear name filter">✕</button>' : ''}
            </div>
            <div class="grid-cell filter-cell" role="gridcell">
              <input type="text" class="filter-input description-filter-input" placeholder="Filter by description" value="${escapeHtml(this._descriptionFilter)}" />
              ${this._descriptionFilter ? '<button class="filter-clear-button description-filter-clear" title="Clear filter" aria-label="Clear description filter">✕</button>' : ''}
            </div>
            <div class="grid-cell" role="gridcell"></div>
            <div class="grid-cell" role="gridcell"></div>
            <div class="grid-cell" role="gridcell"></div>
            <div class="grid-cell" role="gridcell"></div>
          </div>
          <div class="grid-row" role="row">
            <div class="grid-header" role="columnheader"></div>
            <div class="grid-header" role="columnheader">Name</div>
            <div class="grid-header" role="columnheader">Description</div>
            <div class="grid-header" role="columnheader">Type</div>
            <div class="grid-header" role="columnheader">Cardinality</div>
            <div class="grid-header" role="columnheader">Usage</div>
            <div class="grid-header" role="columnheader"></div>
          </div>
          ${filteredOrder.length === 0 ? '<p class="status filter-empty-status">No properties match the current filter.</p>' : filteredOrder.map((index) => {
            const prop = props[index];
            const editable = !prop.isDeleted && !prop.isReadonly;
            const showForm = editable && prop.isEditing;
            return `
            <div class="grid-row ${prop.isDeleted ? 'row-deleted' : ''} ${editable && !prop.isEditing ? 'row-clickable' : ''}" role="row" data-index="${index}">
              <div class="grid-cell" role="gridcell">${prop.isDirty ? `<span class="prop-grid-dirty-dot ${prop.isNew ? 'is-new' : ''} ${prop.isDeleted ? 'is-deleted' : ''}">●</span>` : ''}</div>
              <div class="grid-cell" role="gridcell">
                ${showForm
                  ? `<md-filled-text-field class="editable-field name-input" value="${escapeHtml(prop.name)}"></md-filled-text-field>`
                  : `<span class="read-only-value name-value">${escapeHtml(prop.name)}</span>`}
                ${!prop.isNew && prop.name !== prop.originalName && prop.originalName ? `<div class="original-value">${escapeHtml(prop.originalName)}</div>` : ''}
                ${prop.definedIn ? `<span class="defined-in-badge">via ${escapeHtml(prop.definedIn.entityName)}</span>` : ''}
              </div>
              <div class="grid-cell" role="gridcell">
                ${showForm
                  ? `<md-filled-text-field class="editable-field description-input" value="${escapeHtml(prop.description ?? '')}"></md-filled-text-field>`
                  : `<span class="read-only-value description-value">${escapeHtml(prop.description ?? '')}</span>`}
                ${!prop.isNew && prop.description !== prop.originalDescription && prop.originalDescription ? `<div class="original-value">${escapeHtml(prop.originalDescription)}</div>` : ''}
              </div>
              <div class="grid-cell" role="gridcell">
                ${prop.isNew && showForm ? `
                  <md-filled-select class="editable-field type-select">
                    ${this._propertyTypes.map((t) => `<md-select-option value="${t.type}" ${t.type === prop.type ? 'selected' : ''}><div slot="headline">${t.type}</div></md-select-option>`).join('')}
                  </md-filled-select>
                ` : `<span class="read-only-value type-value">${escapeHtml(prop.type)}</span>`}
              </div>
              <div class="grid-cell" role="gridcell">
                ${prop.validCardinalities.length > 1 && showForm ? `
                  <md-filled-select class="editable-field cardinality-select">
                    ${prop.validCardinalities.map((c) => `<md-select-option value="${c}" ${c === prop.cardinality ? 'selected' : ''}><div slot="headline">${c}</div></md-select-option>`).join('')}
                  </md-filled-select>
                ` : `<span class="read-only-value cardinality-value">${escapeHtml(prop.cardinality)}</span>`}
                ${!prop.isNew && prop.cardinality !== prop.originalCardinality && prop.originalCardinality ? `<div class="original-value">${escapeHtml(prop.originalCardinality)}</div>` : ''}
              </div>
              <div class="grid-cell" role="gridcell">
                ${showForm ? `
                  <md-filled-select class="editable-field usage-select">
                    ${['OPTIONAL', 'REQUIRED', 'DEPRECATED'].map((u) => `<md-select-option value="${u}" ${u === prop.usage ? 'selected' : ''}><div slot="headline">${u}</div></md-select-option>`).join('')}
                  </md-filled-select>
                ` : `<span class="read-only-value usage-value">${escapeHtml(prop.usage)}</span>`}
                ${!prop.isNew && prop.usage !== prop.originalUsage && prop.originalUsage ? `<div class="original-value">${escapeHtml(prop.originalUsage)}</div>` : ''}
              </div>
              <div class="grid-cell actions-cell" role="gridcell">
                ${showForm ? '<md-text-button class="done-button">Done</md-text-button>' : ''}
                ${this.hasControlledList(prop) ? '<md-text-button class="list-button">List</md-text-button>' : ''}
                ${!prop.isReadonly ? (
                  prop.isDeleted
                    ? '<md-text-button class="restore-button">Restore</md-text-button>'
                    : `
                      ${prop.isDirty && !prop.isNew ? '<md-text-button class="revert-button">Revert</md-text-button>' : ''}
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
            </div>
          `;
          }).join('')}
        </div>
      `}
      ${this._allowAdd ? `
        <div class="add-property-row">
          <md-outlined-button class="add-property-button">+ Add Property</md-outlined-button>
        </div>
      ` : ''}
    `;
    this.wireUp();
  }

  wireUp() {
    // Plain native <input>s, not Material fields -- .focus() is synchronous and immediate, no
    // shadow-DOM-upgrade delay to work around. Still needed at all because this.render() replaces
    // innerHTML on every keystroke (to re-filter live): the input that had focus is destroyed and
    // a new one created with the updated value, so without re-focusing (and restoring the caret
    // position, not just focus) only one character could ever be typed before focus fell out of
    // the field.
    const nameFilterInput = this.querySelector('.name-filter-input');
    if (nameFilterInput) nameFilterInput.addEventListener('input', (event) => {
      const cursorPos = event.target.selectionStart;
      this._nameFilter = event.target.value;
      this.render();
      const newInput = this.querySelector('.name-filter-input');
      if (newInput) {
        newInput.focus();
        newInput.setSelectionRange(cursorPos, cursorPos);
      }
    });

    const nameFilterClear = this.querySelector('.name-filter-clear');
    if (nameFilterClear) nameFilterClear.addEventListener('click', () => {
      this._nameFilter = '';
      this.render();
    });

    const descriptionFilterInput = this.querySelector('.description-filter-input');
    if (descriptionFilterInput) descriptionFilterInput.addEventListener('input', (event) => {
      const cursorPos = event.target.selectionStart;
      this._descriptionFilter = event.target.value;
      this.render();
      const newInput = this.querySelector('.description-filter-input');
      if (newInput) {
        newInput.focus();
        newInput.setSelectionRange(cursorPos, cursorPos);
      }
    });

    const descriptionFilterClear = this.querySelector('.description-filter-clear');
    if (descriptionFilterClear) descriptionFilterClear.addEventListener('click', () => {
      this._descriptionFilter = '';
      this.render();
    });

    this.querySelectorAll('.grid-row[data-index]').forEach((row) => {
      const index = Number(row.dataset.index);
      const prop = this._properties[index];

      if (row.classList.contains('row-clickable')) {
        row.addEventListener('click', (event) => {
          if (event.target.closest('.actions-cell')) return;
          // Whichever field the click actually landed in gets focused once the row opens, not
          // always Name.
          const clickedValue = event.target.closest('.read-only-value');
          const fieldClass = clickedValue ? [...clickedValue.classList].find((c) => c !== 'read-only-value') : null;
          const preferredSelector = PROPERTY_FIELD_FOCUS_TARGETS[fieldClass] ?? '.name-input';
          prop.isEditing = true;
          this.render();
          // Scoped by data-index, not a positional index into every matching field in the table --
          // these inputs now only exist for rows currently in edit mode (see render()), so with
          // more than one row open at once a plain querySelectorAll(...)[index] would grab the
          // wrong row's input.
          const target = this.querySelector(`.grid-row[data-index="${index}"] ${preferredSelector}`)
            ?? this.querySelector(`.grid-row[data-index="${index}"] .name-input`);
          if (target) Promise.resolve(target.updateComplete).then(() => target.focus());
        });
      }

      const doneButton = row.querySelector('.done-button');
      if (doneButton) doneButton.addEventListener('click', () => {
        prop.isEditing = false;
        this.render();
      });

      const nameInput = row.querySelector('.name-input');
      if (nameInput) nameInput.addEventListener('change', (event) => {
        prop.name = event.target.value;
        this.render();
        notifySchemaViewModelChange();
      });

      const descriptionInput = row.querySelector('.description-input');
      if (descriptionInput) descriptionInput.addEventListener('change', (event) => {
        prop.description = event.target.value || null;
        this.render();
        notifySchemaViewModelChange();
      });

      const typeSelect = row.querySelector('.type-select');
      if (typeSelect) typeSelect.addEventListener('change', (event) => {
        prop.updateType(event.target.value, this._propertyTypes);
        this.render();
        notifySchemaViewModelChange();
      });

      const cardinalitySelect = row.querySelector('.cardinality-select');
      if (cardinalitySelect) cardinalitySelect.addEventListener('change', (event) => {
        prop.cardinality = event.target.value;
        this.render();
        notifySchemaViewModelChange();
      });

      const usageSelect = row.querySelector('.usage-select');
      if (usageSelect) usageSelect.addEventListener('change', (event) => {
        prop.usage = event.target.value;
        this.render();
        notifySchemaViewModelChange();
      });

      const listButton = row.querySelector('.list-button');
      if (listButton) listButton.addEventListener('click', async () => {
        const pending = schemaViewModel.pendingControlledListReplacements.get(prop.id) ?? null;
        const result = await openControlledListDialog(prop, pending);
        if (result !== undefined) {
          schemaViewModel.setPendingControlledList(prop.id, result);
        }
      });

      const revertButton = row.querySelector('.revert-button');
      if (revertButton) revertButton.addEventListener('click', () => {
        prop.revert();
        this.render();
        notifySchemaViewModelChange();
      });

      const deleteButton = row.querySelector('.delete-button');
      if (deleteButton) deleteButton.addEventListener('click', () => {
        if (prop.isNew) {
          const idx = this._properties.indexOf(prop);
          if (idx !== -1) this._properties.splice(idx, 1);
        } else {
          prop.isDeleted = true;
        }
        this.render();
        notifySchemaViewModelChange();
      });

      const restoreButton = row.querySelector('.restore-button');
      if (restoreButton) restoreButton.addEventListener('click', () => {
        prop.isDeleted = false;
        this.render();
        notifySchemaViewModelChange();
      });
    });

    const addButton = this.querySelector('.add-property-button');
    if (addButton) addButton.addEventListener('click', () => {
      const newProp = PropertyDefinitionViewModel.create(this._propertyTypes);
      this._properties.push(newProp);
      // notifySchemaViewModelChange() is not fire-and-forget here -- every subscriber (currently
      // just ntrloc-schema-editor.js) reacts to it by synchronously rebuilding its entire
      // innerHTML, which tears down and recreates this component's own DOM subtree (a fresh
      // <ntrloc-property-table>, fresh rows) before this line returns. Capturing "the new row's
      // input" as a DOM node beforehand and focusing it afterward -- even after awaiting its
      // updateComplete -- silently does nothing, because by the time that promise resolves the
      // captured node is already disconnected; focus() on a disconnected element is a no-op, not
      // an error, which is why this looked like it worked but never actually moved focus.
      // Fixed by re-finding the row from scratch, from `document`, only after the rebuild has
      // already happened -- searching every ntrloc-property-table's *current* .data array for
      // the same newProp object (identity, not value, since a blank name isn't unique) rather
      // than assuming this instance's own subtree still exists.
      notifySchemaViewModelChange();
      for (const table of document.querySelectorAll('ntrloc-property-table')) {
        const index = table.data.indexOf(newProp);
        if (index === -1) continue;
        // Scoped by data-index -- see the row-click handler's own comment above for why a
        // positional index into every .name-input no longer works now that it's conditional on
        // edit mode.
        const target = table.querySelector(`.grid-row[data-index="${index}"] .name-input`);
        if (target) Promise.resolve(target.updateComplete).then(() => target.focus());
        break;
      }
    });
  }
}

function escapeHtml(value) {
  const div = document.createElement('div');
  div.textContent = String(value ?? '');
  return div.innerHTML;
}

customElements.define('ntrloc-property-table', NtrlocPropertyTable);
