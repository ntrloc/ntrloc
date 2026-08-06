injectStyles('ntrloc-item-mutation-dialog-styles', `
  .item-mutation-dialog {
    --md-dialog-container-min-width: 480px;
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
// via mutation"), so it's never offered. Cardinality is filtered separately (see addableProperties
// below): only SINGLE-cardinality properties are offered in this first cut -- LIST/SET would need
// a multi-value editor this dialog doesn't have yet, not a fundamental backend limit.
const VALUE_TYPES = ['STRING', 'INT', 'LONG', 'DATE', 'DATETIME', 'BOOLEAN', 'OBJECT'];

// Promise-based wrapper around a transient <md-dialog>, same shape as
// ntrloc-save-confirm-dialog.js/ntrloc-controlled-list-dialog.js's open*Dialog functions. Handles
// both item creation (mode: 'create', no `item`) and, later, editing an existing item's properties
// (mode: 'edit', item: { itemId, itemType, properties } -- ProjectedItem's own shape, see
// EntityController's /api/entity/projection) through the same POST /api/mutation endpoint
// (ItemCreateMutation vs ItemUpdateMutation) and the same row-editing UI -- only the item-type
// picker (fixed once an item exists) and the initial row set differ between the two modes.
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
      // the mutation actually expects only at submit time (see coerceValue below).
      rows: item ? Object.entries(item.properties || {}).map(([name, value]) => ({ name, value })) : [],
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

    function valueInputHtml(property, value, index) {
      const v = value ?? '';
      switch (property.type) {
        case 'BOOLEAN':
          return `<md-checkbox class="value-input" data-index="${index}" ${value ? 'checked' : ''}></md-checkbox>`;
        case 'INT':
        case 'LONG':
          return `<input type="number" step="1" class="value-input" data-index="${index}" value="${escapeHtml(v)}">`;
        case 'DATE':
          return `<input type="date" class="value-input" data-index="${index}" value="${escapeHtml(v)}">`;
        case 'DATETIME':
          return `<input type="datetime-local" class="value-input" data-index="${index}" value="${escapeHtml(v)}">`;
        case 'OBJECT':
          return `<textarea rows="2" class="value-input" data-index="${index}" placeholder='{"key": "value"}'>${escapeHtml(typeof v === 'string' ? v : JSON.stringify(v))}</textarea>`;
        default:
          return `<input type="text" class="value-input" data-index="${index}" value="${escapeHtml(v)}">`;
      }
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
          ${state.itemTypeName ? `
            ${state.rows.length > 0 ? `
              <table class="property-rows">
                <thead><tr><th>Property</th><th>Value</th><th></th></tr></thead>
                <tbody>
                  ${state.rows.map((row, index) => `
                    <tr data-index="${index}">
                      <td class="property-name-cell">${escapeHtml(row.name)}</td>
                      <td>${propertyDef(row.name) ? valueInputHtml(propertyDef(row.name), row.value, index) : escapeHtml(String(row.value ?? ''))}</td>
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
          ` : ''}
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
          renderContent();
        });
        // Newly-inserted <md-select-option> children need one microtask to finish upgrading
        // before setting .value actually updates the closed-state display (same fix as
        // ntrloc-process-editor.js's assignee-select/decision-select).
        queueMicrotask(() => { typeSelect.value = state.itemTypeName || ''; });
      }

      dialog.querySelectorAll('.value-input').forEach((input) => {
        const index = Number(input.dataset.index);
        input.addEventListener('change', () => {
          state.rows[index].value = input.tagName === 'MD-CHECKBOX' ? input.checked : input.value;
        });
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
      state.errors = coerceAllRows(state, propertyDef);
      if (state.errors.length > 0) {
        renderContent();
        return;
      }
      const properties = {};
      state.rows.forEach((row) => { properties[row.name] = row.coercedValue; });

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
        state.availableTypes = data.items || [];
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

function coerceValue(property, rawValue) {
  switch (property.type) {
    case 'INT':
    case 'LONG': {
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
