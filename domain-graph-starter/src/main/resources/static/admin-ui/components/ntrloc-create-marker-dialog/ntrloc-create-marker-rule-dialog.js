injectStyles('ntrloc-create-marker-rule-dialog-styles', `
  .marker-rule-dialog .marker-rule-dialog-content {
    display: flex;
    flex-direction: column;
    gap: 14px;
    min-width: 360px;
  }
  .marker-rule-dialog label {
    display: block;
    font-size: 11px;
    font-weight: bold;
    letter-spacing: 0.05em;
    color: var(--muted);
    margin-bottom: 4px;
  }
  .marker-rule-dialog input {
    width: 100%;
    box-sizing: border-box;
    background: var(--panel-bg);
    border: 1px solid var(--border);
    border-radius: 6px;
    color: var(--text);
    padding: 8px 10px;
    font-size: 14px;
  }
  .marker-rule-dialog input:focus {
    outline: none;
    border-color: var(--accent);
  }
  .marker-rule-dialog .scope-readout {
    color: var(--muted);
    font-size: 13px;
  }
  .marker-rule-dialog .field-hint {
    color: var(--muted);
    font-size: 12px;
    margin: 4px 0 0 0;
  }
  .marker-rule-dialog .marker-rule-dialog-error {
    color: #f85149;
    font-size: 13px;
    margin: 0;
  }
`);

// Promise-based, same shape as openCreateMarkerDialog (ntrloc-create-marker-dialog.js). itemTypeId
// is fixed by the caller (whichever item type's "Marker Assignment Rules" subsection this was
// opened from), same reasoning as that dialog's own fixed scope.
//
// decisionKey is a plain text field, not a picker into deployed decision tables: the rule can be
// created before its DMN table exists (MarkerRuleEvaluationService treats an undeployed key as
// "this rule has nothing to say yet," not an error -- see its own comment), so typing a
// not-yet-deployed key here and building the matching decision table afterward in the Processes
// tab is a normal, supported order of operations, not just a fallback. existingKeys (best-effort,
// fetched by the caller) backs a <datalist> purely as a typing aid for the common case of
// attaching a second rule to an already-deployed table.
function openCreateMarkerRuleDialog({ itemTypeId, itemTypeLabel, existingKeys }) {
  return new Promise((resolve) => {
    const dialog = document.createElement('md-dialog');
    dialog.className = 'marker-rule-dialog';
    const datalistId = 'marker-rule-decision-key-options';
    dialog.innerHTML = `
      <div slot="headline">New Assignment Rule</div>
      <div slot="content" class="marker-rule-dialog-content">
        <div>
          <label for="marker-rule-name-input">Name</label>
          <input id="marker-rule-name-input" class="marker-rule-name-input" placeholder="e.g. Confidential priority rule" />
        </div>
        <div>
          <label for="marker-rule-decision-key-input">Decision Key</label>
          <input id="marker-rule-decision-key-input" class="marker-rule-decision-key-input" placeholder="e.g. demoWorkflowItemConfidentialMarker" list="${datalistId}" />
          <datalist id="${datalistId}">
            ${(existingKeys || []).map((k) => `<option value="${escapeHtml(k)}"></option>`).join('')}
          </datalist>
          <p class="field-hint">Must match a Decision Table's Key in the Processes tab. Pick an existing one, or type a new key and build the table there afterward.</p>
        </div>
        <div>
          <label>Item Type</label>
          <div class="scope-readout">${escapeHtml(itemTypeLabel)}</div>
        </div>
        <p class="marker-rule-dialog-error" hidden></p>
      </div>
      <div slot="actions">
        <md-text-button class="cancel-button">Cancel</md-text-button>
        <md-filled-button class="create-button">Create</md-filled-button>
      </div>
    `;
    document.body.appendChild(dialog);

    const errorEl = dialog.querySelector('.marker-rule-dialog-error');

    let resolved = false;
    dialog.addEventListener('closed', () => {
      if (!resolved) resolve(null);
      dialog.remove();
    });

    dialog.querySelector('.cancel-button').addEventListener('click', () => dialog.close('cancel'));
    dialog.querySelector('.create-button').addEventListener('click', () => {
      const name = dialog.querySelector('.marker-rule-name-input').value.trim();
      const decisionKey = dialog.querySelector('.marker-rule-decision-key-input').value.trim();

      if (!name) {
        errorEl.textContent = 'Name is required.';
        errorEl.hidden = false;
        return;
      }
      if (!decisionKey) {
        errorEl.textContent = 'Decision Key is required.';
        errorEl.hidden = false;
        return;
      }

      resolved = true;
      dialog.close('create');
      resolve({ name, itemTypeId, decisionKey });
    });

    dialog.open = true;
  });
}

function escapeHtml(value) {
  const div = document.createElement('div');
  div.textContent = String(value ?? '');
  return div.innerHTML;
}
