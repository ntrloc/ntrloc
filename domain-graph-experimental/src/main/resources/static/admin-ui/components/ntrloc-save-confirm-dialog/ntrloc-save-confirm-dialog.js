injectStyles('ntrloc-save-confirm-dialog-styles', `
  .save-confirm-dialog .change-group {
    margin-bottom: 12px;
  }
  .save-confirm-dialog .group-label {
    font-weight: bold;
    margin-bottom: 4px;
  }
  .save-confirm-dialog .change-list {
    margin: 0;
    padding-left: 20px;
    color: var(--muted);
    font-size: 13px;
  }
`);

// Promise-based wrapper around a transient <md-dialog>, built and appended to document.body on
// open and removed on close -- the simplest match for how the Angular reference's onSave() uses
// MatDialog.open(...).afterClosed() as a single await point. Not a persistent custom element
// with event listeners, since nothing else in this app needs to hold a reference to this dialog
// between opens.
function openSaveConfirmDialog(summary) {
  return new Promise((resolve) => {
    const dialog = document.createElement('md-dialog');
    dialog.className = 'save-confirm-dialog';
    dialog.innerHTML = `
      <div slot="headline">Save Changes</div>
      <div slot="content">
        ${summary.map((group) => `
          <div class="change-group">
            <div class="group-label">${escapeHtml(group.label)}</div>
            <ul class="change-list">
              ${group.changes.map((change) => `<li>${escapeHtml(change)}</li>`).join('')}
            </ul>
          </div>
        `).join('')}
      </div>
      <div slot="actions">
        <md-text-button class="cancel-button">Cancel</md-text-button>
        <md-filled-button class="confirm-button">Save Changes</md-filled-button>
      </div>
    `;
    document.body.appendChild(dialog);

    dialog.addEventListener('closed', () => {
      resolve(dialog.returnValue === 'confirm');
      dialog.remove();
    });

    dialog.querySelector('.cancel-button').addEventListener('click', () => dialog.close('cancel'));
    dialog.querySelector('.confirm-button').addEventListener('click', () => dialog.close('confirm'));

    dialog.open = true;
  });
}

function escapeHtml(value) {
  const div = document.createElement('div');
  div.textContent = String(value ?? '');
  return div.innerHTML;
}
