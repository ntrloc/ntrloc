// Prompts for a process's declared variables (readProcessVariables, bpmn-io.js) before Run
// actually starts it -- same promise-based transient <md-dialog> shape as
// ntrloc-item-mutation-dialog.js's openItemMutationDialog, and the same per-type input-widget/
// coercion approach (STRING/INT/LONG/BOOLEAN/DATE/DATETIME/OBJECT), deliberately kept in sync with
// it rather than factored into one shared helper -- the two dialogs' rows come from different
// sources (a fetched property-definition list vs. an in-memory {name, type, required} array) and
// have differently-shaped surrounding chrome, so the duplication is cheaper than the coupling.
//
// injectStyles/escapeHtml are globals from inject-styles.js (a classic <script>, loaded before
// any module script per index.html) -- reachable here without an import the same way
// ntrloc-process-editor.js itself reaches them, since ES modules only isolate their own top-level
// declarations, not access to the shared global scope.
injectStyles('ntrloc-run-process-dialog-styles', `
  .run-process-dialog table {
    width: 100%;
    border-collapse: collapse;
    margin-top: 4px;
  }
  .run-process-dialog th {
    text-align: left;
    font-size: 11px;
    color: var(--muted);
    font-weight: bold;
    letter-spacing: 0.05em;
    padding: 4px 6px;
    border-bottom: 1px solid var(--border);
  }
  .run-process-dialog td {
    padding: 6px;
    vertical-align: middle;
  }
  .run-process-dialog input, .run-process-dialog textarea {
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
  .run-process-dialog .required-mark {
    color: #f85149;
    margin-left: 2px;
  }
  .run-process-dialog .error-list {
    margin: 12px 0 0 0;
    padding-left: 20px;
    color: #f85149;
    font-size: 13px;
  }
`);

export function openRunProcessDialog(variables) {
  return new Promise((resolve) => {
    // value starts '' for every type here (not e.g. false for BOOLEAN, the way
    // ntrloc-item-mutation-dialog.js seeds a freshly-added row) -- a Run dialog has no "existing
    // item" to prefill from, and an unchecked/blank control is the correct "not yet supplied"
    // state for a *required* field to visibly start in, so validate() below has something real to
    // catch on first render rather than every checkbox silently defaulting to a valid false.
    const state = {
      values: Object.fromEntries(variables.map((v) => [v.name, ''])),
      errors: [],
    };

    const dialog = document.createElement('md-dialog');
    dialog.className = 'run-process-dialog';

    function valueInputHtml(variable) {
      const value = state.values[variable.name];
      switch (variable.type) {
        case 'BOOLEAN':
          return `<md-checkbox class="value-input" data-name="${escapeHtml(variable.name)}" ${value ? 'checked' : ''}></md-checkbox>`;
        case 'INT':
        case 'LONG':
          return `<input type="number" step="1" class="value-input" data-name="${escapeHtml(variable.name)}" value="${escapeHtml(value)}">`;
        case 'DATE':
          return `<input type="date" class="value-input" data-name="${escapeHtml(variable.name)}" value="${escapeHtml(value)}">`;
        case 'DATETIME':
          return `<input type="datetime-local" class="value-input" data-name="${escapeHtml(variable.name)}" value="${escapeHtml(value)}">`;
        case 'OBJECT':
          return `<textarea rows="2" class="value-input" data-name="${escapeHtml(variable.name)}" placeholder='{"key": "value"}'>${escapeHtml(value)}</textarea>`;
        default:
          return `<input type="text" class="value-input" data-name="${escapeHtml(variable.name)}" value="${escapeHtml(value)}">`;
      }
    }

    function renderContent() {
      dialog.querySelector('[slot=content]').innerHTML = `
        <table>
          <thead><tr><th>Variable</th><th>Type</th><th>Value</th></tr></thead>
          <tbody>
            ${variables.map((v) => `
              <tr>
                <td>${escapeHtml(v.name)}${v.required ? '<span class="required-mark">*</span>' : ''}</td>
                <td>${escapeHtml(v.type)}</td>
                <td>${valueInputHtml(v)}</td>
              </tr>
            `).join('')}
          </tbody>
        </table>
        ${state.errors.length > 0 ? `
          <ul class="error-list">${state.errors.map((e) => `<li>${escapeHtml(e)}</li>`).join('')}</ul>
        ` : ''}
      `;
      dialog.querySelectorAll('.value-input').forEach((input) => {
        input.addEventListener('change', () => {
          state.values[input.dataset.name] = input.tagName === 'MD-CHECKBOX' ? input.checked : input.value;
        });
      });
    }

    // Mirrors ntrloc-item-mutation-dialog.js's coerceValue -- same type vocabulary
    // (PROCESS_VARIABLE_TYPES, bpmn-io.js, matches PropertyType's own strings), same per-type
    // parsing. Returns { values, errors }: values only has entries for variables actually
    // supplied (an optional variable left blank is omitted entirely, not sent as ""), errors
    // covers both "required but blank" and "supplied but doesn't parse as its declared type".
    function coerceValues() {
      const values = {};
      const errors = [];
      for (const variable of variables) {
        const raw = state.values[variable.name];
        const blank = variable.type === 'BOOLEAN' ? false : !raw;
        if (blank) {
          if (variable.required) errors.push(`${variable.name} is required`);
          continue;
        }
        try {
          values[variable.name] = coerceValue(variable.type, raw);
        } catch (e) {
          errors.push(`${variable.name}: ${e.message}`);
        }
      }
      return { values, errors };
    }

    dialog.innerHTML = `
      <div slot="headline">Run Process</div>
      <div slot="content"></div>
      <div slot="actions">
        <md-text-button class="cancel-button">Cancel</md-text-button>
        <md-filled-button class="run-button">Run</md-filled-button>
      </div>
    `;
    document.body.appendChild(dialog);

    let result;
    dialog.addEventListener('closed', () => {
      resolve(dialog.returnValue === 'run' ? result : undefined);
      dialog.remove();
    });

    dialog.querySelector('.cancel-button').addEventListener('click', () => dialog.close('cancel'));
    dialog.querySelector('.run-button').addEventListener('click', () => {
      const { values, errors } = coerceValues();
      if (errors.length > 0) {
        state.errors = errors;
        renderContent();
        return;
      }
      result = values;
      dialog.close('run');
    });

    renderContent();
    dialog.open = true;
  });
}

function escapeHtml(value) {
  const div = document.createElement('div');
  div.textContent = String(value ?? '');
  return div.innerHTML;
}

function coerceValue(type, rawValue) {
  switch (type) {
    case 'INT':
    case 'LONG': {
      const n = Number(rawValue);
      if (Number.isNaN(n)) throw new Error('Expected a number');
      return n;
    }
    case 'BOOLEAN':
      return !!rawValue;
    case 'DATE':
      return rawValue;
    case 'DATETIME':
      // Same local-time-to-UTC-instant conversion as ntrloc-item-mutation-dialog.js -- a
      // datetime-local input's value has no timezone, and Flowable/Jackson expect a real ISO
      // instant string ("...Z") for a Date-typed process variable, not a bare local timestamp.
      return new Date(rawValue).toISOString();
    case 'OBJECT':
      return JSON.parse(rawValue);
    default:
      return rawValue;
  }
}
