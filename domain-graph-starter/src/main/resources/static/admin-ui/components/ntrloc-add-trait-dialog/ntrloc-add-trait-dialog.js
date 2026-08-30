injectStyles('ntrloc-add-trait-dialog-styles', `
  .add-trait-dialog .add-trait-dialog-content {
    display: flex;
    flex-direction: column;
    gap: 4px;
    min-width: 280px;
    max-height: 320px;
    overflow-y: auto;
  }
  .trait-picker-list {
    list-style: none;
    margin: 0;
    padding: 0;
  }
  .trait-picker-item {
    display: block;
    width: 100%;
    text-align: left;
    background: none;
    border: none;
    border-radius: 6px;
    padding: 8px 10px;
    font-size: 14px;
    color: var(--text);
    cursor: pointer;
  }
  .trait-picker-item:hover {
    background: rgba(74, 158, 255, 0.08);
  }
`);

// Promise-based, same shape as openCreateMarkerDialog -- resolves with the chosen trait's id on
// click, or null on cancel/close. Click-to-choose (no separate "Add" confirm step) since this
// replaces a single-select <md-filled-select> that likewise assigned immediately on selection
// (see ntrloc-item-detail.js's own comment on why the Traits column moved out of a whole panel);
// a modal list is just a bigger, easier-to-scan version of the same one-click affordance, not a
// multi-select picker.
function openAddTraitDialog({ traits }) {
  return new Promise((resolve) => {
    const dialog = document.createElement('md-dialog');
    dialog.className = 'add-trait-dialog';
    dialog.innerHTML = `
      <div slot="headline">Add Trait</div>
      <div slot="content" class="add-trait-dialog-content">
        ${traits.length > 0 ? `
          <ul class="trait-picker-list">
            ${traits.map((t) => `<li><button type="button" class="trait-picker-item" data-trait-id="${escapeHtml(t.id)}">${escapeHtml(t.name)}</button></li>`).join('')}
          </ul>
        ` : '<p class="status">No traits available to add.</p>'}
      </div>
      <div slot="actions">
        <md-text-button class="cancel-button">Cancel</md-text-button>
      </div>
    `;
    document.body.appendChild(dialog);

    let resolved = false;
    dialog.addEventListener('closed', () => {
      if (!resolved) resolve(null);
      dialog.remove();
    });

    dialog.querySelector('.cancel-button').addEventListener('click', () => dialog.close('cancel'));
    dialog.querySelectorAll('.trait-picker-item').forEach((button) => {
      button.addEventListener('click', () => {
        resolved = true;
        dialog.close('add');
        resolve(button.dataset.traitId);
      });
    });

    dialog.open = true;
  });
}

function escapeHtml(value) {
  const div = document.createElement('div');
  div.textContent = String(value ?? '');
  return div.innerHTML;
}
