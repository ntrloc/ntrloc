injectStyles('ntrloc-controlled-list-detail-styles', `
  ntrloc-controlled-list-detail {
    display: contents;
  }
  input.cl-name-input {
    background: transparent;
    border: none;
    border-bottom: 1px solid transparent;
    color: inherit;
    font: inherit;
    font-size: 1.5rem;
    font-weight: 500;
    flex: 1;
    outline: none;
    padding: 2px 0;
  }
  input.cl-name-input:focus {
    border-bottom-color: rgba(255, 255, 255, 0.4);
  }
  .controlled-list-detail .cl-meta {
    font-size: 12px;
    color: var(--muted);
    margin: 12px 0 4px 0;
  }
  .controlled-list-detail .cl-usage {
    font-size: 13px;
    margin: 8px 0 20px 0;
  }
  .controlled-list-detail .cl-usage ul {
    list-style: none;
    margin: 6px 0 0 0;
    padding: 0;
  }
  .controlled-list-detail .cl-usage li {
    padding: 3px 0;
    border-bottom: 1px solid var(--border);
    color: var(--muted);
  }
  .controlled-list-detail .cl-detach-warning {
    color: var(--dirty-color, #e3b341);
    font-size: 12px;
    font-style: italic;
    margin: 4px 0 0 0;
  }
  .controlled-list-detail .values-table {
    width: 100%;
    border-collapse: collapse;
  }
  .controlled-list-detail .values-table th {
    text-align: left;
    font-size: 11px;
    color: var(--muted);
    font-weight: bold;
    letter-spacing: 0.05em;
    padding: 4px 6px;
    border-bottom: 1px solid var(--border);
  }
  .controlled-list-detail .values-table td {
    padding: 4px 6px;
  }
  .controlled-list-detail input.inline-input, .controlled-list-detail input.add-input {
    width: 100%;
    box-sizing: border-box;
    background: var(--bg);
    color: var(--text);
    border: 1px solid var(--border);
    border-radius: 4px;
    padding: 4px 6px;
    font-size: 13px;
  }
  .controlled-list-detail .add-row {
    display: flex;
    gap: 8px;
    margin-top: 12px;
    align-items: center;
  }
  .controlled-list-detail .add-row .add-input {
    flex: 1;
  }
  .controlled-list-detail .values-section-header {
    font-size: 11px;
    font-weight: bold;
    letter-spacing: 0.05em;
    color: var(--muted);
    margin: 24px 0 8px 0;
  }
`);

// Detail editor for a reusable controlled list (schema_controlled_list), the third schema element
// alongside item types and traits. Mutates the passed-in ControlledListViewModel directly by
// reference and notifies schemaViewModel listeners after every edit -- same convention as
// ntrloc-item-detail.js. Nothing here calls the API to write; all edits stage on the view-model
// and commit through the schema editor's existing Save flow (CREATE/UPDATE/DELETE_CONTROLLED_LIST).
// Values are lazy-loaded once from GET /controlled-lists/{id} the first time a saved list is
// opened (AdminSchemaView.controlledLists carries only name/valueCount/usedBy).
class NtrlocControlledListDetail extends HTMLElement {
  configure({ list }) {
    this._list = list;
    this.render();
  }

  connectedCallback() {
    this.render();
  }

  maybeLoadValues() {
    const list = this._list;
    if (!list || list.isNew || list.valuesLoaded || list._valuesFetchInFlight) return;
    list._valuesFetchInFlight = true;
    schemaService.getControlledListById(list.id)
      .then((response) => {
        list.markValuesLoaded(response.values.map((v) => ({ value: String(v.value), label: v.label })));
      })
      .catch((e) => {
        console.error('[controlled-list] failed to load values:', e);
        list.markValuesLoaded([]);
      })
      .finally(() => {
        list._valuesFetchInFlight = false;
        notifySchemaViewModelChange();
      });
  }

  render() {
    if (!this._list) {
      this.innerHTML = '<p class="status">Select an item, trait, or controlled list to view its details.</p>';
      return;
    }
    this.maybeLoadValues();

    const list = this._list;
    const nameDirty = !list.isNew && list.name !== list.originalName;

    this.innerHTML = `
      <div class="controlled-list-detail">
        <div class="item-header">
          <div class="header-top-row">
            <div class="eyebrow">CONTROLLED LIST</div>
            ${list.isDeleted
              ? '<span class="status">Marked for deletion -- Save to confirm.</span>'
              : '<md-text-button class="delete-list-button">Delete Controlled List</md-text-button>'}
          </div>

          <div class="field-row">
            ${list.isNew || nameDirty ? `<span class="dirty-dot ${list.isNew ? 'is-new' : ''}">&#9679;</span>` : ''}
            <input class="cl-name-input" value="${escapeHtml(list.name)}" placeholder="Name" />
            ${nameDirty ? '<md-text-button class="revert-name-button">Revert</md-text-button>' : ''}
          </div>
          ${nameDirty && list.originalName ? `<div class="original-value">${escapeHtml(list.originalName)}</div>` : ''}

          <div class="cl-meta">Value type: ${escapeHtml(list.valueType)}</div>

          ${list.isNew
            ? '<p class="cl-usage status">Save to make this list attachable to properties.</p>'
            : `<div class="cl-usage">
                 Used by ${list.usedBy.length} propert${list.usedBy.length === 1 ? 'y' : 'ies'}.
                 ${list.usedBy.length > 0 ? `<ul>${list.usedBy.map((u) => `<li>${escapeHtml(u.ownerLabel)} &middot; ${escapeHtml(u.propertyName)}</li>`).join('')}</ul>` : ''}
                 ${list.isDeleted && list.usedBy.length > 0
                   ? `<p class="cl-detach-warning">${list.usedBy.length} propert${list.usedBy.length === 1 ? 'y' : 'ies'} will be detached on Save.</p>`
                   : ''}
               </div>`}
        </div>

        <div class="values-section-header">VALUES</div>
        ${!list.isNew && !list.valuesLoaded ? '<p class="status">Loading...</p>' : `
          ${list.values.length > 0 ? `
            <table class="values-table">
              <thead><tr><th>Value</th><th>Label</th><th></th></tr></thead>
              <tbody>
                ${list.values.map((entry, index) => `
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
            <input class="add-input new-value-input" placeholder="Value" />
            <input class="add-input new-label-input" placeholder="Label (optional)" />
            <md-outlined-button class="add-value-button">Add</md-outlined-button>
          </div>
        `}
      </div>
    `;

    this.wireUp();
  }

  wireUp() {
    const list = this._list;

    const nameInput = this.querySelector('.cl-name-input');
    if (nameInput) {
      nameInput.addEventListener('change', (event) => {
        list.name = event.target.value;
        notifySchemaViewModelChange();
      });
    }

    const revertName = this.querySelector('.revert-name-button');
    if (revertName) {
      revertName.addEventListener('click', () => {
        list.name = list.originalName;
        notifySchemaViewModelChange();
      });
    }

    const deleteButton = this.querySelector('.delete-list-button');
    if (deleteButton) {
      deleteButton.addEventListener('click', () => schemaViewModel.deleteControlledList(list));
    }

    this.querySelectorAll('.value-input').forEach((input) => {
      input.addEventListener('change', (event) => {
        list.values[Number(input.closest('tr').dataset.index)].value = event.target.value;
        notifySchemaViewModelChange();
      });
    });
    this.querySelectorAll('.label-input').forEach((input) => {
      input.addEventListener('change', (event) => {
        list.values[Number(input.closest('tr').dataset.index)].label = event.target.value || null;
        notifySchemaViewModelChange();
      });
    });
    this.querySelectorAll('.remove-value-button').forEach((button) => {
      button.addEventListener('click', () => {
        list.removeValue(Number(button.closest('tr').dataset.index));
        notifySchemaViewModelChange();
      });
    });

    const newValueInput = this.querySelector('.new-value-input');
    const newLabelInput = this.querySelector('.new-label-input');
    if (newValueInput && newLabelInput) {
      const addValue = () => {
        const value = newValueInput.value.trim();
        if (!value) return;
        list.values = [...list.values, { value, label: newLabelInput.value.trim() || null }];
        notifySchemaViewModelChange();
      };
      newValueInput.addEventListener('keydown', (event) => { if (event.key === 'Enter') addValue(); });
      newLabelInput.addEventListener('keydown', (event) => { if (event.key === 'Enter') addValue(); });
      this.querySelector('.add-value-button').addEventListener('click', addValue);
    }
  }
}

function escapeHtml(value) {
  const div = document.createElement('div');
  div.textContent = String(value ?? '');
  return div.innerHTML;
}

customElements.define('ntrloc-controlled-list-detail', NtrlocControlledListDetail);
