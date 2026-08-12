injectStyles('ntrloc-item-mutation-dialog-styles', `
  .item-mutation-dialog {
    --md-dialog-container-min-width: 560px;
  }
  /* Create mode's all-properties-at-once grid -- deliberately the same filter+grid pattern as
     ntrloc-property-table.js's schema-editor property list (filter-input/filter-clear-button/
     filter-empty-status class names carried over as-is), so the two "browse a property list"
     experiences in this app look and behave the same, not a bespoke variant. Edit mode keeps its
     own separate <table class="property-rows"> below entirely unchanged. */
  .item-mutation-dialog .property-grid {
    display: grid;
    grid-template-columns: 1fr 1.5fr 1fr;
    gap: 4px 12px;
    align-items: center;
    width: 100%;
    margin-top: 8px;
  }
  .item-mutation-dialog .property-grid .grid-row {
    display: contents;
  }
  .item-mutation-dialog .property-grid .grid-header {
    font-size: 11px;
    color: var(--muted);
    font-weight: bold;
    letter-spacing: 0.05em;
    padding-bottom: 6px;
    border-bottom: 1px solid var(--border);
    align-self: end;
  }
  .item-mutation-dialog .property-grid .grid-cell {
    padding: 4px 0;
    align-self: center;
    font-size: 13px;
  }
  .item-mutation-dialog .property-grid .property-description-cell {
    color: var(--muted);
  }
  .item-mutation-dialog .property-grid .filter-row .grid-cell {
    padding-bottom: 6px;
  }
  .item-mutation-dialog .property-grid .filter-cell {
    position: relative;
  }
  .item-mutation-dialog .property-grid .filter-input {
    width: 100%;
    box-sizing: border-box;
    padding: 4px 24px 4px 6px;
    font-size: 13px;
    font-family: inherit;
    background: var(--bg);
    border: 1px solid var(--border);
    border-radius: 4px;
    color: var(--text);
    outline: none;
  }
  .item-mutation-dialog .property-grid .filter-input:focus {
    border-color: var(--accent);
  }
  .item-mutation-dialog .property-grid .filter-clear-button {
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
  .item-mutation-dialog .property-grid .filter-clear-button:hover {
    color: var(--text);
  }
  .item-mutation-dialog .property-grid .filter-empty-status {
    grid-column: 1 / -1;
  }
  .item-mutation-dialog .field-value {
    padding: 8px 0;
    color: var(--muted);
  }
  .item-mutation-dialog .property-rows {
    width: 100%;
    border-collapse: collapse;
    margin-top: 8px;
  }
  .item-mutation-dialog .property-rows th {
    text-align: left;
    font-size: 11px;
    color: var(--muted);
    font-weight: bold;
    letter-spacing: 0.05em;
    padding: 4px 6px;
    border-bottom: 1px solid var(--border);
  }
  .item-mutation-dialog .property-rows td {
    padding: 6px;
    vertical-align: middle;
  }
  .item-mutation-dialog .property-name-cell {
    white-space: nowrap;
  }
  .item-mutation-dialog input.value-input, .item-mutation-dialog textarea.value-input {
    width: 100%;
    box-sizing: border-box;
    background: var(--bg);
    color: var(--text);
    border: 1px solid var(--border);
    border-radius: 4px;
    padding: 4px 6px;
    font-size: 13px;
    font-family: inherit;
  }
  .item-mutation-dialog .add-row {
    display: flex;
    gap: 8px;
    margin-top: 12px;
    align-items: center;
  }
  .item-mutation-dialog .add-row select {
    flex: 1;
    background: var(--bg);
    color: var(--text);
    border: 1px solid var(--border);
    border-radius: 4px;
    padding: 6px;
    font-size: 13px;
  }
  .item-mutation-dialog .error-list {
    margin: 12px 0 0 0;
    padding-left: 20px;
    color: #f85149;
    font-size: 13px;
  }
`);

// Which PropertyType values a value can even be entered/submitted for here -- BINARY is rejected
// outright by MutationRequestProcessor.validatePropertyValue ("binary properties cannot be set
// via mutation"), so it's never offered. Cardinality is filtered separately (see
// addableProperties/createProperties below): only SINGLE-cardinality properties are offered in
// this first cut -- LIST/SET would need a multi-value editor this dialog doesn't have yet, not a
// fundamental backend limit.
const VALUE_TYPES = ['STRING', 'INT', 'LONG', 'DOUBLE', 'DATE', 'DATETIME', 'BOOLEAN', 'OBJECT'];

// Promise-based wrapper around a transient <md-dialog>, same shape as
// ntrloc-save-confirm-dialog.js/ntrloc-controlled-list-dialog.js's open*Dialog functions. Handles
// both item creation (mode: 'create', no `item`) and editing an existing item's properties
// (mode: 'edit', item: { itemId, itemType, properties } -- ProjectedItem's own shape, see
// EntityController's /api/entity/projection) through the same POST /api/mutation endpoint
// (ItemCreateMutation vs ItemUpdateMutation), but with genuinely different property-editing UIs
// for the two modes now (see renderContent): edit mode keeps the original "add one property at a
// time" row list (state.rows/addableProperties), since an existing item typically only has a
// handful of properties actually set and the rest are deliberately not shown. Create mode instead
// shows every eligible property of the chosen type up front, filterable by name/description
// (state.createValues/createProperties), so filling out a new item with several properties
// doesn't require repeating an "add property" round trip per field -- see the design discussion
// this replaced (screenshots of the intended create-mode layout) for why the two modes diverged.
// Resolves the created/updated MutationResponse on success, undefined on cancel.
function openItemMutationDialog({ mode = 'create', item } = {}) {
  return new Promise((resolve) => {
    const state = {
      mode,
      itemId: item ? item.itemId : null,
      itemTypeName: item ? item.itemType : null,
      availableTypes: [],
      loadingTypes: true,
      // {name, value} pairs -- value is whatever shape the input control naturally produces
      // (string for text/date/textarea inputs, boolean for the checkbox); coerced to the type
      // the mutation actually expects only at submit time (see coerceValue below). Edit mode only.
      rows: item ? Object.entries(item.properties || {}).map(([name, value]) => ({ name, value })) : [],
      // { [propertyName]: value } -- create mode only, one slot per eligible property whether or
      // not the user has actually typed into it (unlike `rows` above, there's no separate "add"
      // step here: every eligible property always has a row, see createProperties/renderContent).
      // A property never touched, or explicitly cleared back to blank, is simply absent from the
      // submitted properties at coercion time (see coerceCreateValues) -- "blank means omit", the
      // same convention ntrloc-link-create-dialog.js's own confirm handler already uses.
      createValues: {},
      nameFilter: '',
      descriptionFilter: '',
      submitting: false,
      errors: [],
    };

    const dialog = document.createElement('md-dialog');
    dialog.className = 'item-mutation-dialog';

    function currentType() {
      return state.availableTypes.find((t) => t.name === state.itemTypeName);
    }

    function propertyDef(name) {
      const type = currentType();
      return type && type.properties.find((p) => p.name === name);
    }

    function addableProperties() {
      const type = currentType();
      if (!type) return [];
      const used = new Set(state.rows.map((r) => r.name));
      return type.properties.filter((p) => !used.has(p.name)
        && VALUE_TYPES.includes(p.type) && p.cardinality === 'SINGLE');
    }

    // Every property the create-mode grid could ever show for the current type, unfiltered --
    // reused both to render the grid (after applying the live name/description filter) and to
    // know what to coerce at submit time (coerceCreateValues iterates this exact list, not
    // whatever happens to be in state.createValues, so a property the user never touched is still
    // considered -- and correctly omitted, not skipped by omission from some other list).
    function createProperties() {
      const type = currentType();
      if (!type) return [];
      return type.properties.filter((p) => VALUE_TYPES.includes(p.type) && p.cardinality === 'SINGLE');
    }

    function filteredCreateProperties() {
      const nameFilter = state.nameFilter.trim().toLowerCase();
      const descriptionFilter = state.descriptionFilter.trim().toLowerCase();
      return createProperties()
        .filter((p) => {
          const nameMatches = !nameFilter || p.name.toLowerCase().includes(nameFilter);
          const descriptionMatches = !descriptionFilter || (p.description ?? '').toLowerCase().includes(descriptionFilter);
          return nameMatches && descriptionMatches;
        })
        .sort((a, b) => a.name.localeCompare(b.name));
    }

    // dataAttrName/dataAttrValue parameterize which data-* attribute wireContent() can later
    // query the input back by -- data-index (a position in state.rows) for edit mode, data-prop-
    // name (a property name, into state.createValues) for create mode. Shared instead of forked
    // because the actual per-type input markup (the switch below) is identical either way.
    function valueInputHtml(property, value, dataAttrName, dataAttrValue) {
      const v = value ?? '';
      const dataAttr = `data-${dataAttrName}="${escapeHtml(String(dataAttrValue))}"`;
      switch (property.type) {
        case 'BOOLEAN':
          return `<md-checkbox class="value-input" ${dataAttr} ${value ? 'checked' : ''}></md-checkbox>`;
        case 'INT':
        case 'LONG':
          return `<input type="number" step="1" class="value-input" ${dataAttr} value="${escapeHtml(v)}">`;
        case 'DOUBLE':
          return `<input type="number" step="any" class="value-input" ${dataAttr} value="${escapeHtml(v)}">`;
        case 'DATE':
          return `<input type="date" class="value-input" ${dataAttr} value="${escapeHtml(v)}">`;
        case 'DATETIME':
          return `<input type="datetime-local" class="value-input" ${dataAttr} value="${escapeHtml(v)}">`;
        case 'OBJECT':
          return `<textarea rows="2" class="value-input" ${dataAttr} placeholder='{"key": "value"}'>${escapeHtml(typeof v === 'string' ? v : JSON.stringify(v))}</textarea>`;
        default:
          return `<input type="text" class="value-input" ${dataAttr} value="${escapeHtml(v)}">`;
      }
    }

    // Create mode only -- the filterable all-properties grid the "New Item" screenshots called
    // for, styled after ntrloc-property-table.js's own filter+grid (see this file's own CSS
    // block's comment on why). Every eligible property of the current type gets a row up front,
    // not just ones the user explicitly chose to add.
    function createPropertiesGridHtml() {
      const eligible = createProperties();
      if (eligible.length === 0) return '<p class="status">No properties available for this type.</p>';
      const filtered = filteredCreateProperties();
      return `
        <div class="property-grid" role="grid" aria-label="Properties">
          <div class="grid-row filter-row" role="row">
            <div class="grid-cell filter-cell" role="gridcell">
              <input type="text" class="filter-input name-filter-input" placeholder="Filter by name" value="${escapeHtml(state.nameFilter)}">
              ${state.nameFilter ? '<button class="filter-clear-button name-filter-clear" title="Clear filter" aria-label="Clear name filter">✕</button>' : ''}
            </div>
            <div class="grid-cell filter-cell" role="gridcell">
              <input type="text" class="filter-input description-filter-input" placeholder="Filter by description" value="${escapeHtml(state.descriptionFilter)}">
              ${state.descriptionFilter ? '<button class="filter-clear-button description-filter-clear" title="Clear filter" aria-label="Clear description filter">✕</button>' : ''}
            </div>
            <div class="grid-cell" role="gridcell"></div>
          </div>
          <div class="grid-row" role="row">
            <div class="grid-header" role="columnheader">Name</div>
            <div class="grid-header" role="columnheader">Description</div>
            <div class="grid-header" role="columnheader">Value</div>
          </div>
          ${filtered.length === 0 ? '<p class="status filter-empty-status">No properties match the current filter.</p>' : filtered.map((p) => `
            <div class="grid-row" role="row">
              <div class="grid-cell" role="gridcell">${escapeHtml(p.name)}</div>
              <div class="grid-cell property-description-cell" role="gridcell">${escapeHtml(p.description ?? '')}</div>
              <div class="grid-cell" role="gridcell">${valueInputHtml(p, state.createValues[p.name], 'prop-name', p.name)}</div>
            </div>
          `).join('')}
        </div>
      `;
    }

    function renderContent() {
      dialog.querySelector('[slot=headline]').textContent = mode === 'edit' ? 'Edit Item' : 'New Item';

      const typeSelectHtml = mode === 'edit'
        ? `<div class="field-value">${escapeHtml(state.itemTypeName || '')}</div>`
        : `
          <md-filled-select class="item-type-select">
            <md-select-option value="" ${!state.itemTypeName ? 'selected' : ''}>
              <div slot="headline">Select an item type…</div>
            </md-select-option>
            ${state.availableTypes.map((t) => `
              <md-select-option value="${escapeHtml(t.name)}" ${t.name === state.itemTypeName ? 'selected' : ''}>
                <div slot="headline">${escapeHtml(t.name)}</div>
              </md-select-option>
            `).join('')}
          </md-filled-select>
        `;

      const addable = addableProperties();

      dialog.querySelector('[slot=content]').innerHTML = `
        ${state.loadingTypes ? '<p class="status">Loading…</p>' : `
          <label>Item Type</label>
          ${typeSelectHtml}
          ${state.itemTypeName ? (mode === 'edit' ? `
            ${state.rows.length > 0 ? `
              <table class="property-rows">
                <thead><tr><th>Property</th><th>Value</th><th></th></tr></thead>
                <tbody>
                  ${state.rows.map((row, index) => `
                    <tr data-index="${index}">
                      <td class="property-name-cell">${escapeHtml(row.name)}</td>
                      <td>${propertyDef(row.name) ? valueInputHtml(propertyDef(row.name), row.value, 'index', index) : escapeHtml(String(row.value ?? ''))}</td>
                      <td><md-text-button class="remove-property-button">Remove</md-text-button></td>
                    </tr>
                  `).join('')}
                </tbody>
              </table>
            ` : '<p class="status">No properties set.</p>'}
            ${addable.length > 0 ? `
              <div class="add-row">
                <select class="add-property-select">
                  <option value="" selected>Add property…</option>
                  ${addable.map((p) => `<option value="${escapeHtml(p.name)}">${escapeHtml(p.name)} (${p.type})</option>`).join('')}
                </select>
                <md-outlined-button class="add-property-button">Add</md-outlined-button>
              </div>
            ` : ''}
          ` : createPropertiesGridHtml()) : ''}
        `}
        ${state.errors.length > 0 ? `
          <ul class="error-list">
            ${state.errors.map((e) => `<li>${escapeHtml(e.path ? e.path + ': ' + e.message : e.message)}</li>`).join('')}
          </ul>
        ` : ''}
      `;
      dialog.querySelector('.submit-button').disabled = !state.itemTypeName || state.submitting;
      wireContent();
    }

    function wireContent() {
      const typeSelect = dialog.querySelector('.item-type-select');
      if (typeSelect) {
        typeSelect.addEventListener('change', (event) => {
          state.itemTypeName = event.target.value || null;
          state.rows = [];
          state.createValues = {};
          state.nameFilter = '';
          state.descriptionFilter = '';
          renderContent();
        });
        // Newly-inserted <md-select-option> children need one microtask to finish upgrading
        // before setting .value actually updates the closed-state display (same fix as
        // ntrloc-process-editor.js's assignee-select/decision-select).
        queueMicrotask(() => { typeSelect.value = state.itemTypeName || ''; });
      }

      dialog.querySelectorAll('.value-input[data-index]').forEach((input) => {
        const index = Number(input.dataset.index);
        input.addEventListener('change', () => {
          state.rows[index].value = input.tagName === 'MD-CHECKBOX' ? input.checked : input.value;
        });
      });

      dialog.querySelectorAll('.value-input[data-prop-name]').forEach((input) => {
        const name = input.dataset.propName;
        input.addEventListener('change', () => {
          state.createValues[name] = input.tagName === 'MD-CHECKBOX' ? input.checked : input.value;
        });
      });

      // Plain native <input>s, not Material fields -- .focus() is synchronous and immediate, no
      // shadow-DOM-upgrade delay to work around. Still needed at all because renderContent()
      // replaces [slot=content]'s innerHTML on every keystroke (to re-filter live): the input
      // that had focus is destroyed and a new one created with the updated value, so without
      // re-focusing (and restoring the caret position, not just focus) only one character could
      // ever be typed before focus fell out of the field. Same technique, same reason, as
      // ntrloc-property-table.js's own name/description filter inputs.
      const nameFilterInput = dialog.querySelector('.name-filter-input');
      if (nameFilterInput) nameFilterInput.addEventListener('input', (event) => {
        const cursorPos = event.target.selectionStart;
        state.nameFilter = event.target.value;
        renderContent();
        const newInput = dialog.querySelector('.name-filter-input');
        if (newInput) {
          newInput.focus();
          newInput.setSelectionRange(cursorPos, cursorPos);
        }
      });

      const nameFilterClear = dialog.querySelector('.name-filter-clear');
      if (nameFilterClear) nameFilterClear.addEventListener('click', () => {
        state.nameFilter = '';
        renderContent();
      });

      const descriptionFilterInput = dialog.querySelector('.description-filter-input');
      if (descriptionFilterInput) descriptionFilterInput.addEventListener('input', (event) => {
        const cursorPos = event.target.selectionStart;
        state.descriptionFilter = event.target.value;
        renderContent();
        const newInput = dialog.querySelector('.description-filter-input');
        if (newInput) {
          newInput.focus();
          newInput.setSelectionRange(cursorPos, cursorPos);
        }
      });

      const descriptionFilterClear = dialog.querySelector('.description-filter-clear');
      if (descriptionFilterClear) descriptionFilterClear.addEventListener('click', () => {
        state.descriptionFilter = '';
        renderContent();
      });

      dialog.querySelectorAll('.remove-property-button').forEach((button) => {
        button.addEventListener('click', () => {
          state.rows.splice(Number(button.closest('tr').dataset.index), 1);
          renderContent();
        });
      });

      const addSelect = dialog.querySelector('.add-property-select');
      const addButton = dialog.querySelector('.add-property-button');
      if (addSelect && addButton) {
        addButton.addEventListener('click', () => {
          const name = addSelect.value;
          if (!name) return;
          const property = propertyDef(name);
          state.rows.push({ name, value: property.type === 'BOOLEAN' ? false : '' });
          renderContent();
        });
      }
    }

    dialog.innerHTML = `
      <div slot="headline"></div>
      <div slot="content"></div>
      <div slot="actions">
        <md-text-button class="cancel-button">Cancel</md-text-button>
        <md-filled-button class="submit-button">${mode === 'edit' ? 'Save' : 'Create'}</md-filled-button>
      </div>
    `;
    document.body.appendChild(dialog);

    let result;
    dialog.addEventListener('closed', () => {
      resolve(dialog.returnValue === 'submit' ? result : undefined);
      dialog.remove();
    });

    dialog.querySelector('.cancel-button').addEventListener('click', () => dialog.close('cancel'));
    dialog.querySelector('.submit-button').addEventListener('click', async () => {
      let properties;
      if (state.mode === 'edit') {
        state.errors = coerceAllRows(state, propertyDef);
        if (state.errors.length > 0) {
          renderContent();
          return;
        }
        properties = {};
        state.rows.forEach((row) => { properties[row.name] = row.coercedValue; });
      } else {
        const coerced = coerceCreateValues(createProperties(), state.createValues);
        state.errors = coerced.errors;
        if (state.errors.length > 0) {
          renderContent();
          return;
        }
        properties = coerced.properties;
      }

      state.submitting = true;
      renderContent();
      try {
        const mutation = state.mode === 'edit'
          ? { type: 'UPDATE', itemId: state.itemId, properties }
          : { type: 'CREATE', itemTypeName: state.itemTypeName, properties };
        const response = await fetch('/api/mutation', {
          method: 'POST',
          credentials: 'include',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ items: [mutation] }),
        });
        if (response.status === 400) {
          const body = await response.json();
          state.errors = body.errors || [{ path: '', message: 'Request failed validation.' }];
          state.submitting = false;
          renderContent();
          return;
        }
        if (!response.ok) throw new Error('Request failed: ' + response.status);
        result = await response.json();
        dialog.close('submit');
      } catch (e) {
        state.errors = [{ path: '', message: e.message }];
        state.submitting = false;
        renderContent();
      }
    });

    renderContent();
    dialog.open = true;

    // globalSchemaModel.load() (not a direct fetch) -- shares the same cache as every other
    // schema consumer instead of duplicating this fetch, and is already current thanks to its own
    // onSchemaEvent subscription (see global-schema-model.js) rather than relying solely on this
    // being a fresh fetch on every open, as it was before.
    globalSchemaModel.load()
      .then((data) => {
        // Abstract types exist only to be extended -- exclude them here, where the picker is for
        // direct instantiation. Not filtered in ntrloc-search.js's type picker, where searching an
        // abstract root type is exactly the useful polymorphic case, not a mistake to prevent.
        state.availableTypes = (data.items || [])
          .filter((t) => !t.abstractType)
          .sort((a, b) => a.name.localeCompare(b.name));
        state.loadingTypes = false;
        renderContent();
      })
      .catch(() => {
        state.loadingTypes = false;
        state.errors = [{ path: '', message: 'Failed to load item types.' }];
        renderContent();
      });
  });
}

// Converts each row's raw input-control value to what the mutation actually expects (see
// MutationRequestProcessor.validateScalar on the backend for the exact target shapes), writing
// the result onto row.coercedValue rather than returning a parallel array -- the submit handler
// above reads it back off each row once this returns no errors. Returns the accumulated error
// list (empty means every row coerced cleanly); INT/DATE/DATETIME/OBJECT can each fail to parse
// client-side before ever reaching the server's own validation.
function coerceAllRows(state, propertyDef) {
  const errors = [];
  state.rows.forEach((row) => {
    const property = propertyDef(row.name);
    if (!property) {
      row.coercedValue = row.value;
      return;
    }
    try {
      row.coercedValue = coerceValue(property, row.value);
    } catch (e) {
      errors.push({ path: row.name, message: e.message });
    }
  });
  return errors;
}

// Create mode's own coercion pass -- can't reuse coerceAllRows as-is because that one assumes
// every row it sees was deliberately added by the user (so a blank value is a mistake worth
// erroring on, e.g. an INT left empty). Here EVERY eligible property always has a row whether or
// not the user wants to set it, so a blank/untouched value has to mean "omit this property" rather
// than "error" -- coerceValue itself is still reused unchanged (and still throws on a genuinely
// invalid non-blank value, e.g. "abc" typed into an INT field), just never called at all for a
// value that's blank to begin with. BOOLEAN is the one exception: a checkbox has no "blank" state,
// so it's always included (unchecked coerces to false), matching how link-property dialogs already
// treat booleans.
function coerceCreateValues(properties, values) {
  const errors = [];
  const result = {};
  properties.forEach((property) => {
    const raw = values[property.name];
    if (property.type === 'BOOLEAN') {
      result[property.name] = !!raw;
      return;
    }
    if (raw === undefined || raw === null || raw === '') return;
    try {
      result[property.name] = coerceValue(property, raw);
    } catch (e) {
      errors.push({ path: property.name, message: e.message });
    }
  });
  return { properties: result, errors };
}

function coerceValue(property, rawValue) {
  switch (property.type) {
    case 'INT':
    case 'LONG':
    case 'DOUBLE': {
      const n = Number(rawValue);
      if (rawValue === '' || Number.isNaN(n)) throw new Error('Expected a number');
      return n;
    }
    case 'BOOLEAN':
      return !!rawValue;
    case 'DATE':
      return rawValue;
    case 'DATETIME':
      // <input type=datetime-local>'s value carries no timezone -- interpreted as the browser's
      // local time and converted to a proper UTC instant string ("...Z"), matching what
      // MutationRequestProcessor.isValidDateTime actually parses (OffsetDateTime/Instant, both
      // of which require an explicit offset or "Z").
      if (!rawValue) throw new Error('Expected a date/time');
      return new Date(rawValue).toISOString();
    case 'OBJECT':
      return rawValue ? JSON.parse(rawValue) : {};
    default:
      return rawValue;
  }
}

function escapeHtml(value) {
  const div = document.createElement('div');
  div.textContent = String(value ?? '');
  return div.innerHTML;
}
