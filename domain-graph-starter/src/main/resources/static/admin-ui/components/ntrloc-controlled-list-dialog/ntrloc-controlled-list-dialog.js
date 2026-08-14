injectStyles('ntrloc-controlled-list-dialog-styles', `
  .controlled-list-dialog .values-table {
    width: 100%;
    border-collapse: collapse;
  }
  .controlled-list-dialog .values-table th {
    text-align: left;
    font-size: 11px;
    color: var(--muted);
    font-weight: bold;
    letter-spacing: 0.05em;
    padding: 4px 6px;
    border-bottom: 1px solid var(--border);
  }
  .controlled-list-dialog .values-table td {
    padding: 4px 6px;
  }
  .controlled-list-dialog input.inline-input, .controlled-list-dialog input.add-input {
    width: 100%;
    box-sizing: border-box;
    background: var(--bg);
    color: var(--text);
    border: 1px solid var(--border);
    border-radius: 4px;
    padding: 4px 6px;
    font-size: 13px;
  }
  .controlled-list-dialog .add-row {
    display: flex;
    gap: 8px;
    margin-top: 12px;
    align-items: center;
  }
  .controlled-list-dialog .add-row .add-input {
    flex: 1;
  }
`);

// Promise-based wrapper around a transient <md-dialog>, same shape as
// ntrloc-save-confirm-dialog.js's openSaveConfirmDialog(). Loads existing controlled-list values
// from the server unless `pendingValues` is already staged on schemaViewModel (re-opening the
// dialog before Save shows the in-progress edit, matching the Angular reference). Resolves
// `undefined` on cancel, the current values array on "Stage Changes" -- the caller
// (ntrloc-property-table.js) is what actually calls schemaViewModel.setPendingControlledList().
function openControlledListDialog(prop, pendingValues) {
  return new Promise((resolve) => {
    const state = {
      listName: prop.name,
      values: pendingValues !== null ? pendingValues.map((e) => ({ ...e })) : [],
      loading: pendingValues === null,
      newValue: '',
      newLabel: '',
    };

    const dialog = document.createElement('md-dialog');
    dialog.className = 'controlled-list-dialog';

    function renderContent() {
      dialog.querySelector('[slot=headline]').textContent = `Controlled List: ${state.listName}`;
      dialog.querySelector('[slot=content]').innerHTML = `
        ${state.loading ? '<p class="status">Loading...</p>' : `
          ${state.values.length > 0 ? `
            <table class="values-table">
              <thead><tr><th>Value</th><th>Label</th><th></th></tr></thead>
              <tbody>
                ${state.values.map((entry, index) => `
                  <tr data-index="${index}">
                    <td><input class="inline-input value-input" value="${escapeHtml(entry.value)}" /></td>
                    <td><input class="inline-input label-input" value="${escapeHtml(entry.label ?? '')}" placeholder="(same as value)" /></td>
                    <td><md-text-button class="remove-value-button">Remove</md-text-button></td>
                  </tr>
                `).join('')}
              </tbody>
            </table>
          ` : '<p class="status">No values defined.</p>'}
          <div class="add-row">
            <input class="add-input new-value-input" placeholder="Value" value="${escapeHtml(state.newValue)}" />
            <input class="add-input new-label-input" placeholder="Label (optional)" value="${escapeHtml(state.newLabel)}" />
            <md-outlined-button class="add-value-button">Add</md-outlined-button>
          </div>
        `}
      `;
      wireContent();
    }

    function wireContent() {
      dialog.querySelectorAll('.value-input').forEach((input) => {
        input.addEventListener('change', (event) => {
          state.values[Number(input.closest('tr').dataset.index)].value = event.target.value;
        });
      });
      dialog.querySelectorAll('.label-input').forEach((input) => {
        input.addEventListener('change', (event) => {
          state.values[Number(input.closest('tr').dataset.index)].label = event.target.value || null;
        });
      });
      dialog.querySelectorAll('.remove-value-button').forEach((button) => {
        button.addEventListener('click', () => {
          state.values.splice(Number(button.closest('tr').dataset.index), 1);
          renderContent();
        });
      });

      // Only present once state.loading is false -- renderContent()'s markup renders "Loading..."
      // in place of the whole add-row while the initial fetch is in flight (see below), so the
      // very first wireContent() call (always while still loading, for any property with no
      // pendingValues already staged) used to crash here unconditionally, before dialog.open =
      // true ever ran -- the dialog was created and populated but never actually shown, and the
      // click handler's await threw a silent, unhandled rejection. Guarding this block is what
      // lets that first call return cleanly; the second renderContent() call once the fetch
      // resolves finds the add-row present and wires it normally.
      const newValueInput = dialog.querySelector('.new-value-input');
      const newLabelInput = dialog.querySelector('.new-label-input');
      if (newValueInput && newLabelInput) {
        const addValue = () => {
          const value = newValueInput.value.trim();
          if (!value) return;
          state.values.push({ value, label: newLabelInput.value.trim() || null });
          state.newValue = '';
          state.newLabel = '';
          renderContent();
        };
        newValueInput.addEventListener('keydown', (event) => { if (event.key === 'Enter') addValue(); });
        newLabelInput.addEventListener('keydown', (event) => { if (event.key === 'Enter') addValue(); });
        dialog.querySelector('.add-value-button').addEventListener('click', addValue);
      }
    }

    dialog.innerHTML = `
      <div slot="headline"></div>
      <div slot="content"></div>
      <div slot="actions">
        <md-text-button class="cancel-button">Cancel</md-text-button>
        <md-filled-button class="stage-button">Stage Changes</md-filled-button>
      </div>
    `;
    document.body.appendChild(dialog);

    dialog.addEventListener('closed', () => {
      resolve(dialog.returnValue === 'stage' ? state.values : undefined);
      dialog.remove();
    });

    dialog.querySelector('.cancel-button').addEventListener('click', () => dialog.close('cancel'));
    dialog.querySelector('.stage-button').addEventListener('click', () => dialog.close('stage'));

    renderContent();
    dialog.open = true;

    if (pendingValues === null) {
      schemaService.getControlledList(prop.id)
        .then((response) => {
          state.listName = response.name;
          state.values = response.values.map((v) => ({ value: String(v.value), label: v.label }));
          state.loading = false;
          renderContent();
        })
        .catch(() => {
          state.loading = false;
          renderContent();
        });
    }
  });
}

function escapeHtml(value) {
  const div = document.createElement('div');
  div.textContent = String(value ?? '');
  return div.innerHTML;
}
