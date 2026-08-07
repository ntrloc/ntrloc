injectStyles('ntrloc-confirm-dialog-styles', `
  .confirm-dialog .confirm-message {
    margin: 0;
    color: var(--text);
  }
  /* Reuses this file's established destructive-red (#ef5350/#f85149 elsewhere in this app) on the
     filled button itself, via Material's own container/label tokens -- md-filled-button has no
     built-in "danger" variant, so this is the same technique as overriding any other component
     token, just scoped to .destructive-button rather than applied globally. */
  .confirm-dialog .destructive-button {
    --md-filled-button-container-color: #f85149;
    --md-filled-button-label-text-color: #fff;
    --md-filled-button-hover-container-elevation: 1;
    --md-filled-button-hover-label-text-color: #fff;
    --md-filled-button-pressed-label-text-color: #fff;
  }
`);

// Promise-based wrapper around a transient <md-dialog>, same shape as
// ntrloc-save-confirm-dialog.js's openSaveConfirmDialog() -- a generic replacement for native
// confirm(), which every dialog/component in this app already avoids in favor of md-dialog except
// for a handful of confirm() holdouts (this was one of them, see ntrloc-search.js's deleteItem/
// unlinkItem). Resolves true on confirm, false on cancel -- deliberately not undefined-on-cancel
// like the other open*Dialog functions here, since a plain yes/no question has no third "no
// selection made" state to distinguish from "no".
function openConfirmDialog({ title, message, confirmLabel = 'Confirm', destructive = false }) {
  return new Promise((resolve) => {
    const dialog = document.createElement('md-dialog');
    dialog.className = 'confirm-dialog';
    dialog.innerHTML = `
      <div slot="headline">${escapeHtml(title)}</div>
      <div slot="content"><p class="confirm-message">${escapeHtml(message)}</p></div>
      <div slot="actions">
        <md-text-button class="cancel-button">Cancel</md-text-button>
        <md-filled-button class="confirm-button ${destructive ? 'destructive-button' : ''}">${escapeHtml(confirmLabel)}</md-filled-button>
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
