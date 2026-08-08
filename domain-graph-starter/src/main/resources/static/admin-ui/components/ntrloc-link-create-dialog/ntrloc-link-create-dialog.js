injectStyles('ntrloc-link-create-dialog-styles', `
  .link-create-dialog {
    --md-dialog-container-min-width: 420px;
  }
  .link-create-dialog .candidate-list {
    display: flex;
    flex-direction: column;
    gap: 6px;
  }
  .link-create-dialog .candidate-option {
    display: flex;
    align-items: center;
    padding: 10px 12px;
    border: 1px solid var(--border);
    border-radius: 6px;
    background: var(--bg);
    cursor: pointer;
    text-align: left;
    width: 100%;
    font-size: 13px;
    color: var(--text);
  }
  .link-create-dialog .candidate-option:hover {
    border-color: var(--accent);
  }
  .link-create-dialog .candidate-option .perspective-names {
    display: flex;
    flex-direction: column;
    gap: 2px;
  }
  .link-create-dialog .candidate-option .perspective-arrow {
    color: var(--muted);
  }
  .link-create-dialog .property-rows {
    width: 100%;
    border-collapse: collapse;
    margin-top: 4px;
  }
  .link-create-dialog .property-rows th {
    text-align: left;
    font-size: 11px;
    color: var(--muted);
    font-weight: bold;
    letter-spacing: 0.05em;
    padding: 4px 6px;
    border-bottom: 1px solid var(--border);
  }
  .link-create-dialog .property-rows td {
    padding: 6px;
    vertical-align: middle;
  }
  .link-create-dialog input.value-input, .link-create-dialog textarea.value-input {
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
  .link-create-dialog .error-list {
    margin: 12px 0 0 0;
    padding-left: 20px;
    color: #f85149;
    font-size: 13px;
  }
`);

// Promise-based wrapper around a transient <md-dialog>, same shape as
// ntrloc-item-mutation-dialog.js's openItemMutationDialog(). Called from ntrloc-search.js's
// drag-to-link resolution (resolveAndCreateLink) only when a choice or a property value is
// actually needed -- a single zero-property candidate links immediately without ever opening
// this dialog. `candidates` is the array computeLinkCandidates() produced:
// {linkId, fromPerspective, toPerspective, propertyDefs}. Resolves the chosen candidate plus a
// `properties` map, or undefined on cancel.
function openLinkCreateDialog(candidates) {
  return new Promise((resolve) => {
    const state = {
      // Pre-chosen only when there's nothing to choose between -- a single candidate that still
      // needs its properties filled in before it can be confirmed.
      chosen: candidates.length === 1 ? candidates[0] : null,
      values: {},
      errors: [],
    };

    const dialog = document.createElement('md-dialog');
    dialog.className = 'link-create-dialog';

    let result;

    function choose(candidate) {
      state.chosen = candidate;
      state.values = {};
      state.errors = [];
      if (candidate.propertyDefs.length === 0) {
        result = { ...candidate, properties: {} };
        dialog.close('submit');
        return;
      }
      renderContent();
    }

    function valueInputHtml(property) {
      const v = state.values[property.name] ?? '';
      switch (property.type) {
        case 'BOOLEAN':
          return `<md-checkbox class="value-input" data-name="${escapeHtml(property.name)}" ${state.values[property.name] ? 'checked' : ''}></md-checkbox>`;
        case 'INT':
        case 'LONG':
          return `<input type="number" step="1" class="value-input" data-name="${escapeHtml(property.name)}" value="${escapeHtml(v)}">`;
        case 'DATE':
          return `<input type="date" class="value-input" data-name="${escapeHtml(property.name)}" value="${escapeHtml(v)}">`;
        case 'DATETIME':
          return `<input type="datetime-local" class="value-input" data-name="${escapeHtml(property.name)}" value="${escapeHtml(v)}">`;
        case 'OBJECT':
          return `<textarea rows="2" class="value-input" data-name="${escapeHtml(property.name)}" placeholder='{"key": "value"}'>${escapeHtml(typeof v === 'string' ? v : JSON.stringify(v))}</textarea>`;
        default:
          return `<input type="text" class="value-input" data-name="${escapeHtml(property.name)}" value="${escapeHtml(v)}">`;
      }
    }

    function renderContent() {
      const choosing = !state.chosen;
      dialog.querySelector('[slot=headline]').textContent = choosing ? 'Choose Link Type' : 'Link Properties';
      dialog.querySelector('[slot=content]').innerHTML = choosing ? `
        <div class="candidate-list">
          ${candidates.map((c, i) => `
            <button class="candidate-option" data-index="${i}">
              <span class="perspective-names">
                <span>${escapeHtml(c.fromPerspective)}</span>
                <span class="perspective-arrow">&#8594; ${escapeHtml(c.toPerspective)}</span>
              </span>
            </button>
          `).join('')}
        </div>
      ` : `
        <table class="property-rows">
          <thead><tr><th>Property</th><th>Value</th></tr></thead>
          <tbody>
            ${state.chosen.propertyDefs.map((p) => `
              <tr><td>${escapeHtml(p.name)}</td><td>${valueInputHtml(p)}</td></tr>
            `).join('')}
          </tbody>
        </table>
        ${state.errors.length > 0 ? `
          <ul class="error-list">${state.errors.map((e) => `<li>${escapeHtml(e)}</li>`).join('')}</ul>
        ` : ''}
      `;
      dialog.querySelector('.back-button').style.display = !choosing && candidates.length > 1 ? '' : 'none';
      dialog.querySelector('.confirm-button').style.display = choosing ? 'none' : '';
      wireContent();
    }

    function wireContent() {
      dialog.querySelectorAll('.candidate-option').forEach((button) => {
        button.addEventListener('click', () => choose(candidates[Number(button.dataset.index)]));
      });
      dialog.querySelectorAll('.value-input').forEach((input) => {
        input.addEventListener('change', () => {
          state.values[input.dataset.name] = input.tagName === 'MD-CHECKBOX' ? input.checked : input.value;
        });
      });
    }

    dialog.innerHTML = `
      <div slot="headline"></div>
      <div slot="content"></div>
      <div slot="actions">
        <md-text-button class="cancel-button">Cancel</md-text-button>
        <md-text-button class="back-button">Back</md-text-button>
        <md-filled-button class="confirm-button">Link</md-filled-button>
      </div>
    `;
    document.body.appendChild(dialog);

    dialog.addEventListener('closed', () => {
      resolve(dialog.returnValue === 'submit' ? result : undefined);
      dialog.remove();
    });

    dialog.querySelector('.cancel-button').addEventListener('click', () => dialog.close('cancel'));
    dialog.querySelector('.back-button').addEventListener('click', () => {
      state.chosen = null;
      renderContent();
    });
    dialog.querySelector('.confirm-button').addEventListener('click', () => {
      const errors = [];
      const properties = {};
      state.chosen.propertyDefs.forEach((p) => {
        try {
          const coerced = coerceValue(p, state.values[p.name] ?? (p.type === 'BOOLEAN' ? false : ''));
          if (coerced !== '' && coerced !== null) properties[p.name] = coerced;
        } catch (e) {
          errors.push(`${p.name}: ${e.message}`);
        }
      });
      if (errors.length > 0) {
        state.errors = errors;
        renderContent();
        return;
      }
      result = { ...state.chosen, properties };
      dialog.close('submit');
    });

    renderContent();
    dialog.open = true;
  });
}

// Promise-based wrapper around a transient <md-dialog>, for editing an EXISTING link's own
// properties -- unlike openLinkCreateDialog's candidate-then-properties flow for a brand new
// link, there's no candidate to resolve here, just the one already-known link type's properties
// step. Reachable from the "Edit link" pencil icon on a linked item's nested card in the search
// view's Formatted mode (ntrloc-search.js's editLinkProperties), only shown at all when that link
// type actually has properties to edit. Pre-fills every field from `initialValues`. Resolves a
// `properties` map of every non-blank coerced value (same "blank means omit" convention as
// openLinkCreateDialog's own confirm handler -- editLinkProperties diffs this against the link's
// current properties to build the actual UPDATE delta, turning an omitted-but-previously-set
// field into an explicit null), or undefined on cancel.
function openLinkPropertiesDialog({ title, propertyDefs, initialValues }) {
  return new Promise((resolve) => {
    const state = { values: { ...initialValues }, errors: [] };
    const dialog = document.createElement('md-dialog');
    dialog.className = 'link-create-dialog';

    function valueInputHtml(property) {
      const v = state.values[property.name] ?? '';
      switch (property.type) {
        case 'BOOLEAN':
          return `<md-checkbox class="value-input" data-name="${escapeHtml(property.name)}" ${state.values[property.name] ? 'checked' : ''}></md-checkbox>`;
        case 'INT':
        case 'LONG':
          return `<input type="number" step="1" class="value-input" data-name="${escapeHtml(property.name)}" value="${escapeHtml(v)}">`;
        case 'DATE':
          return `<input type="date" class="value-input" data-name="${escapeHtml(property.name)}" value="${escapeHtml(v)}">`;
        case 'DATETIME':
          return `<input type="datetime-local" class="value-input" data-name="${escapeHtml(property.name)}" value="${escapeHtml(v)}">`;
        case 'OBJECT':
          return `<textarea rows="2" class="value-input" data-name="${escapeHtml(property.name)}" placeholder='{"key": "value"}'>${escapeHtml(typeof v === 'string' ? v : JSON.stringify(v))}</textarea>`;
        default:
          return `<input type="text" class="value-input" data-name="${escapeHtml(property.name)}" value="${escapeHtml(v)}">`;
      }
    }

    function renderContent() {
      dialog.querySelector('[slot=headline]').textContent = title;
      dialog.querySelector('[slot=content]').innerHTML = `
        <table class="property-rows">
          <thead><tr><th>Property</th><th>Value</th></tr></thead>
          <tbody>
            ${propertyDefs.map((p) => `
              <tr><td>${escapeHtml(p.name)}</td><td>${valueInputHtml(p)}</td></tr>
            `).join('')}
          </tbody>
        </table>
        ${state.errors.length > 0 ? `
          <ul class="error-list">${state.errors.map((e) => `<li>${escapeHtml(e)}</li>`).join('')}</ul>
        ` : ''}
      `;
      wireContent();
    }

    function wireContent() {
      dialog.querySelectorAll('.value-input').forEach((input) => {
        input.addEventListener('change', () => {
          state.values[input.dataset.name] = input.tagName === 'MD-CHECKBOX' ? input.checked : input.value;
        });
      });
    }

    dialog.innerHTML = `
      <div slot="headline"></div>
      <div slot="content"></div>
      <div slot="actions">
        <md-text-button class="cancel-button">Cancel</md-text-button>
        <md-filled-button class="confirm-button">Save</md-filled-button>
      </div>
    `;
    document.body.appendChild(dialog);

    let result;
    dialog.addEventListener('closed', () => {
      resolve(dialog.returnValue === 'submit' ? result : undefined);
      dialog.remove();
    });

    dialog.querySelector('.cancel-button').addEventListener('click', () => dialog.close('cancel'));
    dialog.querySelector('.confirm-button').addEventListener('click', () => {
      const errors = [];
      const properties = {};
      propertyDefs.forEach((p) => {
        try {
          const coerced = coerceValue(p, state.values[p.name] ?? (p.type === 'BOOLEAN' ? false : ''));
          if (coerced !== '' && coerced !== null) properties[p.name] = coerced;
        } catch (e) {
          errors.push(`${p.name}: ${e.message}`);
        }
      });
      if (errors.length > 0) {
        state.errors = errors;
        renderContent();
        return;
      }
      result = properties;
      dialog.close('submit');
    });

    renderContent();
    dialog.open = true;
  });
}

// Same coercion rules as ntrloc-item-mutation-dialog.js's own coerceValue -- kept as an
// independent copy (this app's established convention for these small per-dialog helpers, see
// escapeHtml below) rather than a shared import, since these are plain scripts, not modules.
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
