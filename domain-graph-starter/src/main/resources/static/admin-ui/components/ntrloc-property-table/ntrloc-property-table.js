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
  .add-property-row {
    margin-top: 8px;
  }
`);

// Reusable editable property grid -- used both for an item/trait's own properties and for a
// link's nested properties (previously duplicated markup in each place). Given { properties,
// propertyTypes, allowAdd } via the .data setter; mutates the PropertyDefinitionViewModel objects
// in `properties` directly by reference (they belong to whatever ItemDefinitionViewModel/
// TraitDefinitionViewModel/LinkViewModel the caller holds) and calls notifySchemaViewModelChange()
// after every edit -- this component does not call the mutation API itself, matching the
// established "dumb presentation component" convention from before this rewrite.
class NtrlocPropertyTable extends HTMLElement {
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
    this.innerHTML = `
      ${props.length === 0 ? '<p class="status">No properties defined.</p>' : `
        <div class="property-grid" role="grid" aria-label="Properties">
          <div class="grid-row" role="row">
            <div class="grid-header" role="columnheader"></div>
            <div class="grid-header" role="columnheader">Name</div>
            <div class="grid-header" role="columnheader">Description</div>
            <div class="grid-header" role="columnheader">Type</div>
            <div class="grid-header" role="columnheader">Cardinality</div>
            <div class="grid-header" role="columnheader">Usage</div>
            <div class="grid-header" role="columnheader"></div>
          </div>
          ${props.map((prop, index) => `
            <div class="grid-row ${prop.isDeleted ? 'row-deleted' : ''}" role="row" data-index="${index}">
              <div class="grid-cell" role="gridcell">${prop.isDirty ? `<span class="prop-grid-dirty-dot ${prop.isNew ? 'is-new' : ''} ${prop.isDeleted ? 'is-deleted' : ''}">●</span>` : ''}</div>
              <div class="grid-cell" role="gridcell">
                <md-filled-text-field class="editable-field name-input" value="${escapeHtml(prop.name)}" ${prop.isDeleted || prop.isReadonly ? 'disabled' : ''}></md-filled-text-field>
                ${!prop.isNew && prop.name !== prop.originalName && prop.originalName ? `<div class="original-value">${escapeHtml(prop.originalName)}</div>` : ''}
                ${prop.definedIn ? `<span class="defined-in-badge">via ${escapeHtml(prop.definedIn.entityName)}</span>` : ''}
              </div>
              <div class="grid-cell" role="gridcell">
                <md-filled-text-field class="editable-field description-input" value="${escapeHtml(prop.description ?? '')}" ${prop.isDeleted || prop.isReadonly ? 'disabled' : ''}></md-filled-text-field>
                ${!prop.isNew && prop.description !== prop.originalDescription && prop.originalDescription ? `<div class="original-value">${escapeHtml(prop.originalDescription)}</div>` : ''}
              </div>
              <div class="grid-cell" role="gridcell">
                ${prop.isNew ? `
                  <md-filled-select class="editable-field type-select">
                    ${this._propertyTypes.map((t) => `<md-select-option value="${t.type}" ${t.type === prop.type ? 'selected' : ''}><div slot="headline">${t.type}</div></md-select-option>`).join('')}
                  </md-filled-select>
                ` : `<md-filled-text-field class="editable-field" value="${escapeHtml(prop.type)}" disabled></md-filled-text-field>`}
              </div>
              <div class="grid-cell" role="gridcell">
                ${prop.validCardinalities.length > 1 && !prop.isDeleted && !prop.isReadonly ? `
                  <md-filled-select class="editable-field cardinality-select">
                    ${prop.validCardinalities.map((c) => `<md-select-option value="${c}" ${c === prop.cardinality ? 'selected' : ''}><div slot="headline">${c}</div></md-select-option>`).join('')}
                  </md-filled-select>
                ` : `<md-filled-text-field class="editable-field" value="${escapeHtml(prop.cardinality)}" disabled></md-filled-text-field>`}
                ${!prop.isNew && prop.cardinality !== prop.originalCardinality && prop.originalCardinality ? `<div class="original-value">${escapeHtml(prop.originalCardinality)}</div>` : ''}
              </div>
              <div class="grid-cell" role="gridcell">
                <md-filled-select class="editable-field usage-select" ${prop.isDeleted || prop.isReadonly ? 'disabled' : ''}>
                  ${['OPTIONAL', 'REQUIRED', 'DEPRECATED'].map((u) => `<md-select-option value="${u}" ${u === prop.usage ? 'selected' : ''}><div slot="headline">${u}</div></md-select-option>`).join('')}
                </md-filled-select>
                ${!prop.isNew && prop.usage !== prop.originalUsage && prop.originalUsage ? `<div class="original-value">${escapeHtml(prop.originalUsage)}</div>` : ''}
              </div>
              <div class="grid-cell actions-cell" role="gridcell">
                ${this.hasControlledList(prop) ? '<md-text-button class="list-button">List</md-text-button>' : ''}
                ${!prop.isReadonly ? (
                  prop.isDeleted
                    ? '<md-text-button class="restore-button">Restore</md-text-button>'
                    : `
                      ${prop.isDirty && !prop.isNew ? '<md-text-button class="revert-button">Revert</md-text-button>' : ''}
                      <md-text-button class="delete-button">Delete</md-text-button>
                    `
                ) : ''}
              </div>
            </div>
          `).join('')}
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
    this.querySelectorAll('.grid-row[data-index]').forEach((row) => {
      const index = Number(row.dataset.index);
      const prop = this._properties[index];

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
        const target = table.querySelectorAll('.name-input')[index];
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
