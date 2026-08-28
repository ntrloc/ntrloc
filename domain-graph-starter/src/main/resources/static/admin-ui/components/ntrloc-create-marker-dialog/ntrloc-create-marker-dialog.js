injectStyles('ntrloc-create-marker-dialog-styles', `
  .marker-dialog .marker-dialog-content {
    display: flex;
    flex-direction: column;
    gap: 14px;
    min-width: 320px;
  }
  .marker-dialog label {
    display: block;
    font-size: 11px;
    font-weight: bold;
    letter-spacing: 0.05em;
    color: var(--muted);
    margin-bottom: 4px;
  }
  .marker-dialog input {
    width: 100%;
    box-sizing: border-box;
    background: var(--panel-bg);
    border: 1px solid var(--border);
    border-radius: 6px;
    color: var(--text);
    padding: 8px 10px;
    font-size: 14px;
  }
  .marker-dialog input:focus {
    outline: none;
    border-color: var(--accent);
  }
  .marker-dialog .scope-readout {
    color: var(--muted);
    font-size: 13px;
  }
  .marker-dialog .marker-dialog-error {
    color: #f85149;
    font-size: 13px;
    margin: 0;
  }
`);

// Promise-based, same shape as openConfirmDialog (ntrloc-confirm-dialog.js) -- resolves with
// { name, description, scopeKind, scopeId } on success, null on cancel. Scope is fixed by the
// caller (whichever item type's "Access Markers" panel this was opened from -- see
// ntrloc-item-detail.js), not picked here: a marker's scope determines which item instances it's
// eligible to be assigned to and which properties/transitions its grants may reference (see
// docs/ntrloc-marker-admin-ui-design-notes.md), and that's already unambiguous from where the
// admin clicked "+ New Marker" -- re-asking would just be a chance to pick the wrong one.
function openCreateMarkerDialog({ scopeKind, scopeId, scopeLabel }) {
  return new Promise((resolve) => {
    const dialog = document.createElement('md-dialog');
    dialog.className = 'marker-dialog';
    dialog.innerHTML = `
      <div slot="headline">New Marker</div>
      <div slot="content" class="marker-dialog-content">
        <div>
          <label for="marker-name-input">Name</label>
          <input id="marker-name-input" class="marker-name-input" placeholder="e.g. Confidential" />
        </div>
        <div>
          <label for="marker-description-input">Description</label>
          <input id="marker-description-input" class="marker-description-input" placeholder="Optional" />
        </div>
        <div>
          <label>Scope</label>
          <div class="scope-readout">${escapeHtml(scopeLabel)}</div>
        </div>
        <p class="marker-dialog-error" hidden></p>
      </div>
      <div slot="actions">
        <md-text-button class="cancel-button">Cancel</md-text-button>
        <md-filled-button class="create-button">Create</md-filled-button>
      </div>
    `;
    document.body.appendChild(dialog);

    const errorEl = dialog.querySelector('.marker-dialog-error');

    let resolved = false;
    dialog.addEventListener('closed', () => {
      if (!resolved) resolve(null);
      dialog.remove();
    });

    dialog.querySelector('.cancel-button').addEventListener('click', () => dialog.close('cancel'));
    dialog.querySelector('.create-button').addEventListener('click', () => {
      const name = dialog.querySelector('.marker-name-input').value.trim();
      const description = dialog.querySelector('.marker-description-input').value.trim();

      if (!name) {
        errorEl.textContent = 'Name is required.';
        errorEl.hidden = false;
        return;
      }

      resolved = true;
      dialog.close('create');
      resolve({ name, description: description || null, scopeKind, scopeId });
    });

    dialog.open = true;
  });
}

// Same promise-based shape as openCreateMarkerDialog, prefilled from the existing marker.
// Scope is shown but never editable here -- see the design doc's reasoning that scope is what a
// marker's own grants are validated against, so changing it after grants exist would silently
// invalidate them (AuthorizationRepository.updateMarker only ever touches name/description).
// Resolves with { id, name, description }, or null on cancel.
function openEditMarkerDialog({ id, name, description, scopeLabel }) {
  return new Promise((resolve) => {
    const dialog = document.createElement('md-dialog');
    dialog.className = 'marker-dialog';
    dialog.innerHTML = `
      <div slot="headline">Edit Marker</div>
      <div slot="content" class="marker-dialog-content">
        <div>
          <label for="marker-name-input">Name</label>
          <input id="marker-name-input" class="marker-name-input" value="${escapeHtml(name)}" />
        </div>
        <div>
          <label for="marker-description-input">Description</label>
          <input id="marker-description-input" class="marker-description-input" value="${escapeHtml(description ?? '')}" placeholder="Optional" />
        </div>
        <div>
          <label>Scope</label>
          <div class="scope-readout">${escapeHtml(scopeLabel)}</div>
        </div>
        <p class="marker-dialog-error" hidden></p>
      </div>
      <div slot="actions">
        <md-text-button class="cancel-button">Cancel</md-text-button>
        <md-filled-button class="save-button">Save</md-filled-button>
      </div>
    `;
    document.body.appendChild(dialog);

    const errorEl = dialog.querySelector('.marker-dialog-error');

    let resolved = false;
    dialog.addEventListener('closed', () => {
      if (!resolved) resolve(null);
      dialog.remove();
    });

    dialog.querySelector('.cancel-button').addEventListener('click', () => dialog.close('cancel'));
    dialog.querySelector('.save-button').addEventListener('click', () => {
      const newName = dialog.querySelector('.marker-name-input').value.trim();
      const newDescription = dialog.querySelector('.marker-description-input').value.trim();

      if (!newName) {
        errorEl.textContent = 'Name is required.';
        errorEl.hidden = false;
        return;
      }

      resolved = true;
      dialog.close('save');
      resolve({ id, name: newName, description: newDescription || null });
    });

    dialog.open = true;
  });
}

function escapeHtml(value) {
  const div = document.createElement('div');
  div.textContent = String(value ?? '');
  return div.innerHTML;
}
