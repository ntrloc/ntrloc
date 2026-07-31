injectStyles('ntrloc-users-styles', `
  ntrloc-users {
    display: flex;
    flex-direction: column;
    padding: 24px 32px;
    gap: 16px;
    overflow-y: auto;
  }
  .users-header {
    display: flex;
    align-items: center;
    justify-content: space-between;
  }
  .users-header h2 {
    margin: 0;
    font-size: 18px;
    font-weight: 600;
  }
  .users-table {
    width: 100%;
    border-collapse: collapse;
    font-size: 14px;
  }
  .users-table th,
  .users-table td {
    text-align: left;
    padding: 10px 12px;
    border-bottom: 1px solid var(--border);
  }
  .users-table th {
    color: var(--muted);
    font-weight: 500;
    font-size: 12px;
    text-transform: uppercase;
    letter-spacing: 0.5px;
  }
  .users-table tr.clickable {
    cursor: pointer;
  }
  .users-table tr.clickable:hover {
    background: var(--panel-bg);
  }
  .users-table .badge-admin {
    display: inline-block;
    font-size: 11px;
    padding: 2px 6px;
    border-radius: 4px;
    background: var(--accent);
    color: white;
  }
  .users-table .badge-user {
    display: inline-block;
    font-size: 11px;
    padding: 2px 6px;
    border-radius: 4px;
    border: 1px solid var(--border);
    color: var(--muted);
  }
  .user-modal-overlay {
    position: fixed;
    inset: 0;
    background: rgba(0, 0, 0, 0.6);
    display: flex;
    align-items: center;
    justify-content: center;
    z-index: 1000;
  }
  .user-modal {
    background: var(--panel-bg);
    border: 1px solid var(--border);
    border-radius: 12px;
    padding: 24px;
    width: 520px;
    max-height: 85vh;
    overflow-y: auto;
    box-shadow: 0 8px 32px rgba(0, 0, 0, 0.4);
  }
  .user-modal h3 {
    margin: 0 0 16px 0;
    font-size: 17px;
    font-weight: 600;
  }
  .user-modal label {
    font-size: 12px;
    color: var(--muted);
    display: block;
    margin-bottom: 4px;
  }
  .user-modal input,
  .user-modal select {
    width: 100%;
    padding: 8px 10px;
    border: 1px solid var(--border);
    border-radius: 6px;
    background: var(--bg);
    color: var(--text);
    font-size: 14px;
    margin-bottom: 12px;
  }
  .user-modal input:disabled {
    opacity: 0.6;
  }
  .user-modal .form-actions {
    display: flex;
    gap: 8px;
    margin-top: 4px;
  }
  .user-modal .btn {
    padding: 8px 16px;
    border: none;
    border-radius: 6px;
    cursor: pointer;
    font-size: 13px;
  }
  .user-modal .btn-primary {
    background: var(--accent);
    color: white;
  }
  .user-modal .btn-danger {
    background: #c33;
    color: white;
  }
  .user-modal .btn-cancel {
    background: var(--border);
    color: var(--text);
  }
  .user-modal .separator {
    border-top: 1px solid var(--border);
    margin: 16px 0;
  }
  .users-error {
    color: #e55;
    font-size: 13px;
  }
  .users-success {
    color: #5c5;
    font-size: 13px;
  }
  .tokens-section {
    margin-top: 0;
  }
  .tokens-section h4 {
    margin: 0 0 10px 0;
    font-size: 14px;
    font-weight: 600;
  }
  .tokens-table {
    width: 100%;
    border-collapse: collapse;
    font-size: 13px;
  }
  .tokens-table th,
  .tokens-table td {
    text-align: left;
    padding: 6px 8px;
    border-bottom: 1px solid var(--border);
  }
  .tokens-table th {
    color: var(--muted);
    font-weight: 500;
    font-size: 11px;
    text-transform: uppercase;
  }
  .tokens-table .btn-revoke {
    padding: 3px 8px;
    border: none;
    border-radius: 4px;
    background: #c33;
    color: white;
    font-size: 11px;
    cursor: pointer;
  }
  .tokens-table .btn-revoke:hover {
    background: #a22;
  }
  .token-create-row {
    display: flex;
    gap: 8px;
    align-items: center;
    margin-top: 10px;
  }
  .token-create-row input {
    width: auto;
    margin-bottom: 0;
  }
  .token-create-row .btn-sm {
    padding: 6px 12px;
    border: none;
    border-radius: 6px;
    background: var(--accent);
    color: white;
    font-size: 12px;
    cursor: pointer;
    white-space: nowrap;
  }
  .token-reveal {
    margin-top: 10px;
    padding: 10px;
    border: 1px solid var(--accent);
    border-radius: 6px;
    background: var(--bg);
  }
  .token-reveal code {
    display: block;
    font-size: 12px;
    word-break: break-all;
    user-select: all;
    margin-bottom: 4px;
  }
  .token-reveal .hint {
    font-size: 11px;
    color: var(--muted);
  }
`);

class NtrlocUsers extends HTMLElement {
  constructor() {
    super();
    this.users = [];
    this.mode = 'list'; // 'list' | 'create' | 'edit'
    this.editingUser = null;
    this.error = '';
    this.success = '';
    this.tokens = [];
    this.createdToken = null;
  }

  connectedCallback() {
    this.fetchUsers();
  }

  async fetchUsers() {
    try {
      const res = await fetch('/api/admin/users', { credentials: 'include' });
      if (!res.ok) throw new Error('Failed to load users');
      this.users = await res.json();
      this.render();
    } catch (e) {
      this.error = e.message;
      this.render();
    }
  }

  async fetchTokens(userId) {
    try {
      const res = await fetch(`/api/admin/users/${userId}/tokens`, { credentials: 'include' });
      if (!res.ok) throw new Error('Failed to load tokens');
      this.tokens = await res.json();
      this.render();
    } catch (e) {
      this.error = e.message;
      this.render();
    }
  }

  async createUser() {
    this.error = '';
    this.success = '';
    const modal = this.querySelector('.user-modal');
    const body = {
      externalId: modal.querySelector('[name="externalId"]').value.trim(),
      displayName: modal.querySelector('[name="displayName"]').value.trim(),
      email: modal.querySelector('[name="email"]').value.trim(),
      password: modal.querySelector('[name="password"]').value,
      role: modal.querySelector('[name="role"]').value
    };
    if (!body.externalId || !body.displayName || !body.email || !body.password) {
      this.error = 'All fields are required';
      this.render();
      return;
    }
    try {
      const res = await fetch('/api/admin/users', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        credentials: 'include',
        body: JSON.stringify(body)
      });
      if (res.status === 409) {
        this.error = 'A user with that ID already exists';
        this.render();
        return;
      }
      if (!res.ok) throw new Error('Failed to create user');
      this.success = `User "${body.externalId}" created`;
      this.mode = 'list';
      await this.fetchUsers();
    } catch (e) {
      this.error = e.message;
      this.render();
    }
  }

  async updateUser() {
    this.error = '';
    this.success = '';
    const modal = this.querySelector('.user-modal');
    const body = {
      displayName: modal.querySelector('[name="displayName"]').value.trim(),
      email: modal.querySelector('[name="email"]').value.trim(),
      role: modal.querySelector('[name="role"]').value
    };
    if (!body.displayName || !body.email) {
      this.error = 'Display name and email are required';
      this.render();
      return;
    }
    try {
      const res = await fetch(`/api/admin/users/${this.editingUser.id}`, {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        credentials: 'include',
        body: JSON.stringify(body)
      });
      if (!res.ok) throw new Error('Failed to update user');
      this.success = `User "${this.editingUser.externalId}" updated`;
      this.mode = 'list';
      this.editingUser = null;
      await this.fetchUsers();
    } catch (e) {
      this.error = e.message;
      this.render();
    }
  }

  async resetPassword() {
    this.error = '';
    this.success = '';
    const modal = this.querySelector('.user-modal');
    const newPassword = modal.querySelector('[name="newPassword"]').value;
    if (!newPassword) {
      this.error = 'Password is required';
      this.render();
      return;
    }
    try {
      const res = await fetch(`/api/admin/users/${this.editingUser.id}/password`, {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        credentials: 'include',
        body: JSON.stringify({ newPassword })
      });
      if (!res.ok) throw new Error('Failed to reset password');
      this.success = `Password reset for "${this.editingUser.externalId}"`;
      this.mode = 'list';
      this.editingUser = null;
      this.render();
    } catch (e) {
      this.error = e.message;
      this.render();
    }
  }

  async createToken() {
    this.error = '';
    this.createdToken = null;
    const nameInput = this.querySelector('[name="newTokenName"]');
    const expiresInput = this.querySelector('[name="newTokenExpires"]');
    const name = nameInput?.value.trim();
    const expiresInDays = parseInt(expiresInput?.value, 10) || null;
    if (!name) {
      this.error = 'Token name is required';
      this.render();
      return;
    }
    try {
      const res = await fetch(`/api/admin/users/${this.editingUser.id}/tokens`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        credentials: 'include',
        body: JSON.stringify({ name, expiresInDays })
      });
      if (!res.ok) throw new Error('Failed to create token');
      this.createdToken = await res.json();
      await this.fetchTokens(this.editingUser.id);
    } catch (e) {
      this.error = e.message;
      this.render();
    }
  }

  async revokeToken(tokenId) {
    this.error = '';
    this.createdToken = null;
    try {
      const res = await fetch(`/api/admin/users/${this.editingUser.id}/tokens/${tokenId}`, {
        method: 'DELETE',
        credentials: 'include'
      });
      if (!res.ok) throw new Error('Failed to revoke token');
      await this.fetchTokens(this.editingUser.id);
    } catch (e) {
      this.error = e.message;
      this.render();
    }
  }

  formatDate(iso) {
    if (!iso) return 'Never';
    const d = new Date(iso);
    return d.toLocaleDateString() + ' ' + d.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
  }

  closeModal() {
    this.mode = 'list';
    this.editingUser = null;
    this.tokens = [];
    this.createdToken = null;
    this.error = '';
    this.render();
  }

  render() {
    const rows = this.users.map(u => `
      <tr class="clickable" data-user-id="${u.id}">
        <td>${this.escapeHtml(u.externalId)}</td>
        <td>${this.escapeHtml(u.displayName)}</td>
        <td>${this.escapeHtml(u.email || '')}</td>
        <td><span class="${u.isSuperuser ? 'badge-admin' : 'badge-user'}">${u.isSuperuser ? 'Admin' : 'User'}</span></td>
      </tr>
    `).join('');

    let modalHtml = '';
    if (this.mode === 'create') {
      modalHtml = `
        <div class="user-modal-overlay" data-action="overlay-close">
          <div class="user-modal">
            <h3>Create User</h3>
            ${this.error ? `<div class="users-error">${this.error}</div>` : ''}
            <label>Username</label>
            <input name="externalId" placeholder="Login ID" autocomplete="off">
            <label>Display Name</label>
            <input name="displayName" placeholder="Full name" autocomplete="off">
            <label>Email</label>
            <input name="email" type="email" placeholder="Email" autocomplete="off">
            <label>Password</label>
            <input name="password" type="password" placeholder="Password" autocomplete="new-password">
            <label>Role</label>
            <select name="role">
              <option value="USER">User</option>
              <option value="ADMIN">Admin</option>
            </select>
            <div class="form-actions">
              <button type="button" class="btn btn-primary" data-action="submit-create">Create</button>
              <button type="button" class="btn btn-cancel" data-action="cancel">Cancel</button>
            </div>
          </div>
        </div>
      `;
    } else if (this.mode === 'edit' && this.editingUser) {
      const u = this.editingUser;
      const tokenRows = this.tokens.map(t => `
        <tr>
          <td>${this.escapeHtml(t.name)}</td>
          <td>${this.formatDate(t.createdAt)}</td>
          <td>${this.formatDate(t.expiresAt)}</td>
          <td><button class="btn-revoke" data-token-id="${t.id}">Revoke</button></td>
        </tr>
      `).join('');

      let tokenRevealHtml = '';
      if (this.createdToken) {
        tokenRevealHtml = `
          <div class="token-reveal">
            <code>${this.escapeHtml(this.createdToken.token)}</code>
            <div class="hint">Copy this token now — it won't be shown again.</div>
          </div>
        `;
      }

      modalHtml = `
        <div class="user-modal-overlay" data-action="overlay-close">
          <div class="user-modal">
            <h3>Edit User</h3>
            ${this.error ? `<div class="users-error">${this.error}</div>` : ''}
            <label>Username</label>
            <input name="externalId" value="${this.escapeHtml(u.externalId)}" disabled>
            <label>Display Name</label>
            <input name="displayName" value="${this.escapeHtml(u.displayName)}" autocomplete="off">
            <label>Email</label>
            <input name="email" type="email" value="${this.escapeHtml(u.email || '')}" autocomplete="off">
            <label>Role</label>
            <select name="role">
              <option value="USER" ${!u.isSuperuser ? 'selected' : ''}>User</option>
              <option value="ADMIN" ${u.isSuperuser ? 'selected' : ''}>Admin</option>
            </select>
            <div class="form-actions">
              <button type="button" class="btn btn-primary" data-action="submit-update">Save Changes</button>
              <button type="button" class="btn btn-cancel" data-action="cancel">Cancel</button>
            </div>
            <div class="separator"></div>
            <label>Reset Password</label>
            <input name="newPassword" type="password" placeholder="New password" autocomplete="new-password">
            <div class="form-actions">
              <button type="button" class="btn btn-danger" data-action="submit-reset-password">Reset Password</button>
            </div>
            <div class="separator"></div>
            <div class="tokens-section">
              <h4>Personal Access Tokens</h4>
              <table class="tokens-table">
                <thead><tr><th>Name</th><th>Created</th><th>Expires</th><th></th></tr></thead>
                <tbody>${tokenRows || '<tr><td colspan="4" class="status">No tokens</td></tr>'}</tbody>
              </table>
              ${tokenRevealHtml}
              <div class="token-create-row">
                <input name="newTokenName" placeholder="Token name" autocomplete="off">
                <input name="newTokenExpires" type="number" placeholder="Days" min="1" style="width:70px">
                <button type="button" class="btn-sm" data-action="create-token">Create Token</button>
              </div>
            </div>
          </div>
        </div>
      `;
    }

    this.innerHTML = `
      <div class="users-header">
        <h2>Users</h2>
        <md-filled-button data-action="show-create">Create User</md-filled-button>
      </div>
      ${this.mode === 'list' && this.success ? `<div class="users-success">${this.success}</div>` : ''}
      <table class="users-table">
        <thead><tr><th>Username</th><th>Display Name</th><th>Email</th><th>Role</th></tr></thead>
        <tbody>${rows || '<tr><td colspan="4" class="status">No users found</td></tr>'}</tbody>
      </table>
      ${modalHtml}
    `;

    this.querySelector('[data-action="show-create"]')?.addEventListener('click', () => {
      this.mode = 'create';
      this.editingUser = null;
      this.error = '';
      this.success = '';
      this.render();
    });
    this.querySelector('[data-action="overlay-close"]')?.addEventListener('click', (e) => {
      if (e.target.classList.contains('user-modal-overlay')) this.closeModal();
    });
    this.querySelector('[data-action="cancel"]')?.addEventListener('click', () => this.closeModal());
    this.querySelector('[data-action="submit-create"]')?.addEventListener('click', () => this.createUser());
    this.querySelector('[data-action="submit-update"]')?.addEventListener('click', () => this.updateUser());
    this.querySelector('[data-action="submit-reset-password"]')?.addEventListener('click', () => this.resetPassword());
    this.querySelector('[data-action="create-token"]')?.addEventListener('click', () => this.createToken());
    this.querySelectorAll('.btn-revoke').forEach(btn => {
      btn.addEventListener('click', () => this.revokeToken(btn.dataset.tokenId));
    });
    this.querySelectorAll('tr.clickable').forEach(row => {
      row.addEventListener('click', () => {
        const userId = row.dataset.userId;
        this.editingUser = this.users.find(u => u.id === userId);
        this.mode = 'edit';
        this.error = '';
        this.success = '';
        this.tokens = [];
        this.createdToken = null;
        this.render();
        this.fetchTokens(userId);
      });
    });
  }

  escapeHtml(value) {
    const div = document.createElement('div');
    div.textContent = String(value ?? '');
    return div.innerHTML;
  }
}

customElements.define('ntrloc-users', NtrlocUsers);
