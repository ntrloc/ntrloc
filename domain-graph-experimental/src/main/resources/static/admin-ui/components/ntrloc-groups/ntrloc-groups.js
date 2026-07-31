injectStyles('ntrloc-groups-styles', `
  ntrloc-groups {
    display: flex;
    flex-direction: column;
    padding: 24px 32px;
    gap: 16px;
    overflow-y: auto;
  }
  .groups-header {
    display: flex;
    align-items: center;
    justify-content: space-between;
  }
  .groups-header h2 {
    margin: 0;
    font-size: 18px;
    font-weight: 600;
  }
  .groups-table {
    width: 100%;
    border-collapse: collapse;
    font-size: 14px;
  }
  .groups-table th,
  .groups-table td {
    text-align: left;
    padding: 10px 12px;
    border-bottom: 1px solid var(--border);
  }
  .groups-table th {
    color: var(--muted);
    font-weight: 500;
    font-size: 12px;
    text-transform: uppercase;
    letter-spacing: 0.5px;
  }
  .groups-table tr.clickable {
    cursor: pointer;
  }
  .groups-table tr.clickable:hover {
    background: var(--panel-bg);
  }
  .groups-table .badge-default {
    display: inline-block;
    font-size: 11px;
    padding: 2px 6px;
    border-radius: 4px;
    background: var(--accent);
    color: white;
  }
  .group-modal-overlay {
    position: fixed;
    inset: 0;
    background: rgba(0, 0, 0, 0.6);
    display: flex;
    align-items: center;
    justify-content: center;
    z-index: 1000;
  }
  .group-modal {
    background: var(--panel-bg);
    border: 1px solid var(--border);
    border-radius: 12px;
    padding: 24px;
    width: 600px;
    max-height: 85vh;
    overflow-y: auto;
    box-shadow: 0 8px 32px rgba(0, 0, 0, 0.4);
  }
  .group-modal h3 {
    margin: 0 0 16px 0;
    font-size: 17px;
    font-weight: 600;
  }
  .group-modal label {
    font-size: 12px;
    color: var(--muted);
    display: block;
    margin-bottom: 4px;
  }
  .group-modal input,
  .group-modal select {
    width: 100%;
    padding: 8px 10px;
    border: 1px solid var(--border);
    border-radius: 6px;
    background: var(--bg);
    color: var(--text);
    font-size: 14px;
    margin-bottom: 12px;
  }
  .group-modal .form-actions {
    display: flex;
    gap: 8px;
    margin-top: 4px;
  }
  .group-modal .btn {
    padding: 8px 16px;
    border: none;
    border-radius: 6px;
    cursor: pointer;
    font-size: 13px;
  }
  .group-modal .btn-primary {
    background: var(--accent);
    color: white;
  }
  .group-modal .btn-danger {
    background: #c33;
    color: white;
  }
  .group-modal .btn-cancel {
    background: var(--border);
    color: var(--text);
  }
  .group-modal .separator {
    border-top: 1px solid var(--border);
    margin: 16px 0;
  }
  .groups-error {
    color: #e55;
    font-size: 13px;
    margin-bottom: 8px;
  }
  .groups-success {
    color: #5c5;
    font-size: 13px;
  }
  .members-section {
    margin-top: 0;
  }
  .members-section h4 {
    margin: 0 0 10px 0;
    font-size: 14px;
    font-weight: 600;
  }
  .members-table {
    width: 100%;
    border-collapse: collapse;
    font-size: 13px;
  }
  .members-table th,
  .members-table td {
    text-align: left;
    padding: 6px 8px;
    border-bottom: 1px solid var(--border);
  }
  .members-table th {
    color: var(--muted);
    font-weight: 500;
    font-size: 11px;
    text-transform: uppercase;
  }
  .members-table .btn-remove {
    padding: 3px 8px;
    border: none;
    border-radius: 4px;
    background: #c33;
    color: white;
    font-size: 11px;
    cursor: pointer;
  }
  .members-table .btn-remove:hover {
    background: #a22;
  }
  .member-add-row {
    display: flex;
    gap: 8px;
    align-items: center;
    margin-top: 10px;
  }
  .member-add-row select {
    width: auto;
    flex: 1;
    margin-bottom: 0;
  }
  .member-add-row .btn-sm {
    padding: 6px 12px;
    border: none;
    border-radius: 6px;
    background: var(--accent);
    color: white;
    font-size: 12px;
    cursor: pointer;
    white-space: nowrap;
  }
`);

class NtrlocGroups extends HTMLElement {
  constructor() {
    super();
    this.groups = [];
    this.mode = 'list';
    this.editingGroup = null;
    this.members = [];
    this.allUsers = [];
    this.error = '';
    this.success = '';
  }

  connectedCallback() {
    this.fetchGroups();
  }

  async fetchGroups() {
    try {
      const res = await fetch('/api/admin/groups', { credentials: 'include' });
      if (!res.ok) throw new Error('Failed to load groups');
      this.groups = await res.json();
    } catch (e) {
      this.groups = [];
    }
    this.render();
  }

  async fetchMembers(groupId) {
    try {
      const res = await fetch(`/api/admin/groups/${groupId}/members`, { credentials: 'include' });
      if (!res.ok) throw new Error('Failed to load members');
      this.members = await res.json();
    } catch (e) {
      this.members = [];
    }
    this.render();
  }

  async fetchAllUsers() {
    try {
      const res = await fetch('/api/admin/users', { credentials: 'include' });
      if (!res.ok) throw new Error('Failed to load users');
      this.allUsers = await res.json();
    } catch (e) {
      this.allUsers = [];
    }
  }

  async createGroup() {
    this.error = '';
    const nameInput = this.querySelector('[name="groupName"]');
    const name = nameInput?.value.trim();
    if (!name) {
      this.error = 'Group name is required';
      this.render();
      return;
    }
    try {
      const res = await fetch('/api/admin/groups', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        credentials: 'include',
        body: JSON.stringify({ name })
      });
      if (!res.ok) {
        const text = await res.text();
        throw new Error(text || 'Failed to create group');
      }
      this.success = `Group "${name}" created.`;
      this.closeModal();
      await this.fetchGroups();
    } catch (e) {
      this.error = e.message;
      this.render();
    }
  }

  async updateGroup() {
    this.error = '';
    const nameInput = this.querySelector('[name="groupName"]');
    const name = nameInput?.value.trim();
    if (!name) {
      this.error = 'Group name is required';
      this.render();
      return;
    }
    try {
      const res = await fetch(`/api/admin/groups/${this.editingGroup.id}`, {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        credentials: 'include',
        body: JSON.stringify({ name })
      });
      if (!res.ok) {
        const text = await res.text();
        throw new Error(text || 'Failed to update group');
      }
      this.editingGroup = await res.json();
      this.success = `Group renamed to "${name}".`;
      await this.fetchGroups();
      this.render();
    } catch (e) {
      this.error = e.message;
      this.render();
    }
  }

  async deleteGroup() {
    if (!confirm(`Delete group "${this.editingGroup.name}"? Members will be removed from this group.`)) return;
    try {
      const res = await fetch(`/api/admin/groups/${this.editingGroup.id}`, {
        method: 'DELETE',
        credentials: 'include'
      });
      if (!res.ok) {
        const text = await res.text();
        throw new Error(text || 'Failed to delete group');
      }
      this.success = `Group "${this.editingGroup.name}" deleted.`;
      this.closeModal();
      await this.fetchGroups();
    } catch (e) {
      this.error = e.message;
      this.render();
    }
  }

  async addMember() {
    this.error = '';
    const select = this.querySelector('[name="addUserId"]');
    const userId = select?.value;
    if (!userId) return;
    try {
      const res = await fetch(`/api/admin/groups/${this.editingGroup.id}/members`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        credentials: 'include',
        body: JSON.stringify({ userId })
      });
      if (!res.ok) throw new Error('Failed to add member');
      await this.fetchMembers(this.editingGroup.id);
    } catch (e) {
      this.error = e.message;
      this.render();
    }
  }

  async removeMember(userId) {
    this.error = '';
    try {
      const res = await fetch(`/api/admin/groups/${this.editingGroup.id}/members/${userId}`, {
        method: 'DELETE',
        credentials: 'include'
      });
      if (!res.ok) throw new Error('Failed to remove member');
      await this.fetchMembers(this.editingGroup.id);
    } catch (e) {
      this.error = e.message;
      this.render();
    }
  }

  closeModal() {
    this.mode = 'list';
    this.editingGroup = null;
    this.members = [];
    this.error = '';
    this.render();
  }

  escapeHtml(value) {
    const div = document.createElement('div');
    div.textContent = String(value ?? '');
    return div.innerHTML;
  }

  render() {
    const rows = this.groups.map(g => `
      <tr class="clickable" data-group-id="${g.id}">
        <td>${this.escapeHtml(g.name)}${g.name === 'everyone' ? ' <span class="badge-default">default</span>' : ''}</td>
        <td>${g.memberCount}</td>
      </tr>
    `).join('');

    let modalHtml = '';
    if (this.mode === 'create') {
      modalHtml = `
        <div class="group-modal-overlay" data-action="overlay-close">
          <div class="group-modal">
            <h3>Create Group</h3>
            ${this.error ? `<div class="groups-error">${this.escapeHtml(this.error)}</div>` : ''}
            <label>Name</label>
            <input name="groupName" placeholder="Group name" autocomplete="off">
            <div class="form-actions">
              <button type="button" class="btn btn-primary" data-action="submit-create">Create</button>
              <button type="button" class="btn btn-cancel" data-action="cancel">Cancel</button>
            </div>
          </div>
        </div>
      `;
    } else if (this.mode === 'edit' && this.editingGroup) {
      const g = this.editingGroup;
      const isDefault = g.name === 'everyone';
      const memberRows = this.members.map(m => `
        <tr>
          <td>${this.escapeHtml(m.externalId)}</td>
          <td>${this.escapeHtml(m.displayName)}</td>
          <td>${this.escapeHtml(m.email || '')}</td>
          <td><button class="btn-remove" data-user-id="${m.id}">Remove</button></td>
        </tr>
      `).join('');

      const memberIds = new Set(this.members.map(m => m.id));
      const availableUsers = this.allUsers.filter(u => !memberIds.has(u.id));
      const userOptions = availableUsers.map(u =>
        `<option value="${u.id}">${this.escapeHtml(u.displayName)} (${this.escapeHtml(u.externalId)})</option>`
      ).join('');

      modalHtml = `
        <div class="group-modal-overlay" data-action="overlay-close">
          <div class="group-modal">
            <h3>Edit Group</h3>
            ${this.error ? `<div class="groups-error">${this.escapeHtml(this.error)}</div>` : ''}
            <label>Name</label>
            <input name="groupName" value="${this.escapeHtml(g.name)}" autocomplete="off">
            <div class="form-actions">
              <button type="button" class="btn btn-primary" data-action="submit-update">Save Name</button>
              ${!isDefault ? `<button type="button" class="btn btn-danger" data-action="delete-group">Delete Group</button>` : ''}
              <button type="button" class="btn btn-cancel" data-action="cancel">Close</button>
            </div>
            <div class="separator"></div>
            <div class="members-section">
              <h4>Members (${this.members.length})</h4>
              <table class="members-table">
                <thead><tr><th>Username</th><th>Display Name</th><th>Email</th><th></th></tr></thead>
                <tbody>${memberRows || '<tr><td colspan="4" class="status">No members</td></tr>'}</tbody>
              </table>
              ${availableUsers.length > 0 ? `
                <div class="member-add-row">
                  <select name="addUserId">
                    <option value="">-- Select user --</option>
                    ${userOptions}
                  </select>
                  <button type="button" class="btn-sm" data-action="add-member">Add</button>
                </div>
              ` : ''}
            </div>
          </div>
        </div>
      `;
    }

    this.innerHTML = `
      <div class="groups-header">
        <h2>Groups</h2>
        <md-filled-button data-action="show-create">Create Group</md-filled-button>
      </div>
      ${this.mode === 'list' && this.success ? `<div class="groups-success">${this.escapeHtml(this.success)}</div>` : ''}
      <table class="groups-table">
        <thead><tr><th>Name</th><th>Members</th></tr></thead>
        <tbody>${rows || '<tr><td colspan="2" class="status">No groups found</td></tr>'}</tbody>
      </table>
      ${modalHtml}
    `;

    this.querySelector('[data-action="show-create"]')?.addEventListener('click', () => {
      this.mode = 'create';
      this.editingGroup = null;
      this.error = '';
      this.success = '';
      this.render();
    });
    this.querySelector('[data-action="overlay-close"]')?.addEventListener('click', (e) => {
      if (e.target.classList.contains('group-modal-overlay')) this.closeModal();
    });
    this.querySelector('[data-action="cancel"]')?.addEventListener('click', () => this.closeModal());
    this.querySelector('[data-action="submit-create"]')?.addEventListener('click', () => this.createGroup());
    this.querySelector('[data-action="submit-update"]')?.addEventListener('click', () => this.updateGroup());
    this.querySelector('[data-action="delete-group"]')?.addEventListener('click', () => this.deleteGroup());
    this.querySelector('[data-action="add-member"]')?.addEventListener('click', () => this.addMember());
    this.querySelectorAll('.btn-remove').forEach(btn => {
      btn.addEventListener('click', () => this.removeMember(btn.dataset.userId));
    });
    this.querySelectorAll('tr.clickable').forEach(row => {
      row.addEventListener('click', async () => {
        const groupId = row.dataset.groupId;
        const group = this.groups.find(g => g.id === groupId);
        if (!group) return;
        this.editingGroup = group;
        this.mode = 'edit';
        this.error = '';
        this.success = '';
        await this.fetchAllUsers();
        await this.fetchMembers(groupId);
      });
    });
  }
}

customElements.define('ntrloc-groups', NtrlocGroups);
