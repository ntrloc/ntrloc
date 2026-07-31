injectStyles('ntrloc-access-styles', `
  ntrloc-access[data-route].current {
    flex-direction: row;
  }
  ntrloc-access {
    display: flex;
    flex-direction: row;
    flex: 1;
    min-height: 0;
  }

  /* Sidebar */
  .access-sidebar {
    width: 280px;
    border-right: 1px solid var(--border);
    display: flex;
    flex-direction: column;
    overflow: hidden;
    flex-shrink: 0;
  }
  .access-sidebar-section {
    display: flex;
    flex-direction: column;
    min-height: 0;
    flex: 1 1 50%;
    overflow: hidden;
  }
  .access-sidebar-section + .access-sidebar-section {
    border-top: 1px solid var(--border);
  }
  .access-sidebar-header {
    padding: 10px 16px;
    border-bottom: 1px solid var(--border);
    display: flex;
    align-items: center;
    justify-content: space-between;
    flex-shrink: 0;
  }
  .access-sidebar-header h3 {
    margin: 0;
    font-size: 12px;
    text-transform: uppercase;
    letter-spacing: 0.5px;
    color: var(--muted);
  }
  .access-sidebar-list {
    flex: 1;
    overflow-y: auto;
  }
  .access-sidebar-item {
    padding: 8px 16px;
    cursor: pointer;
    border-bottom: 1px solid var(--border);
    display: flex;
    justify-content: space-between;
    align-items: center;
  }
  .access-sidebar-item:hover { background: var(--panel-bg); }
  .access-sidebar-item.selected {
    background: var(--panel-bg);
    border-left: 3px solid var(--accent);
    padding-left: 13px;
  }
  .access-sidebar-item .item-name { font-weight: 500; font-size: 13px; }
  .access-sidebar-item .item-sub { color: var(--muted); font-size: 11px; }
  .access-sidebar-item .item-count { color: var(--muted); font-size: 11px; }
  .access-sidebar-item .badge-default {
    font-size: 9px;
    background: var(--accent);
    color: white;
    padding: 1px 5px;
    border-radius: 3px;
    margin-left: 6px;
  }
  .access-sidebar-item .badge-admin {
    font-size: 9px;
    background: #e8a735;
    color: #1a1a1a;
    padding: 1px 5px;
    border-radius: 3px;
    margin-left: 6px;
  }

  /* Detail panel */
  .access-detail {
    flex: 1;
    display: flex;
    flex-direction: column;
    overflow: hidden;
  }
  .access-detail-header {
    padding: 16px 24px;
    border-bottom: 1px solid var(--border);
    display: flex;
    align-items: center;
    gap: 12px;
    flex-shrink: 0;
  }
  .access-detail-header h2 { margin: 0; font-size: 18px; }
  .access-detail-header .type-badge {
    font-size: 11px;
    padding: 2px 8px;
    border-radius: 4px;
    border: 1px solid var(--border);
    color: var(--muted);
  }
  .access-detail-header .actions { margin-left: auto; display: flex; gap: 8px; }

  .access-detail-tabs {
    display: flex;
    gap: 0;
    border-bottom: 1px solid var(--border);
    padding: 0 24px;
    flex-shrink: 0;
  }
  .access-detail-tab {
    padding: 10px 16px;
    cursor: pointer;
    color: var(--muted);
    border-bottom: 2px solid transparent;
    font-size: 13px;
  }
  .access-detail-tab.active {
    color: var(--text);
    border-bottom-color: var(--accent);
  }

  .access-detail-content {
    flex: 1;
    padding: 20px 24px;
    overflow-y: auto;
  }

  .access-section {
    margin-bottom: 24px;
  }
  .access-section h4 {
    margin: 0 0 10px 0;
    font-size: 12px;
    text-transform: uppercase;
    letter-spacing: 0.5px;
    color: var(--muted);
  }

  .access-table {
    width: 100%;
    border-collapse: collapse;
    font-size: 13px;
  }
  .access-table th {
    text-align: left;
    padding: 6px 8px;
    color: var(--muted);
    font-size: 11px;
    text-transform: uppercase;
    border-bottom: 1px solid var(--border);
  }
  .access-table td {
    padding: 8px;
    border-bottom: 1px solid var(--border);
  }
  .access-table tr:hover { background: var(--panel-bg); }

  .access-error {
    color: #e55;
    font-size: 13px;
    margin-bottom: 8px;
  }

  .access-btn {
    padding: 4px 10px;
    border: none;
    border-radius: 4px;
    font-size: 11px;
    cursor: pointer;
  }
  .access-btn.primary { background: var(--accent); color: white; }
  .access-btn.danger { background: #c33; color: white; }
  .access-btn.ghost { background: none; border: 1px solid var(--border); color: var(--muted); }

  .access-add-row {
    display: flex;
    gap: 8px;
    align-items: center;
    margin-top: 10px;
  }
  .access-add-row select,
  .access-add-row input {
    padding: 6px 8px;
    border: 1px solid var(--border);
    border-radius: 4px;
    background: var(--bg);
    color: var(--text);
    font-size: 12px;
    flex: 1;
  }

  .access-chip {
    display: inline-block;
    padding: 3px 8px;
    border-radius: 12px;
    font-size: 11px;
    border: 1px solid var(--accent);
    background: rgba(74, 158, 255, 0.15);
    color: var(--accent);
    margin: 2px 4px 2px 0;
    cursor: pointer;
  }
  .access-chip::after { content: ' \\00d7'; font-weight: bold; }

  .perm-check {
    width: 18px;
    height: 18px;
    border-radius: 4px;
    border: 1px solid var(--border);
    display: inline-flex;
    align-items: center;
    justify-content: center;
    cursor: pointer;
    font-size: 12px;
    color: transparent;
    background: none;
    padding: 0;
  }
  .perm-check.granted {
    background: rgba(74, 158, 255, 0.15);
    border-color: var(--accent);
    color: var(--accent);
  }

  .access-empty {
    color: var(--muted);
    font-style: italic;
    text-align: center;
    padding: 40px 0;
  }

  .access-profile-table td:first-child {
    color: var(--muted);
    width: 120px;
  }
`);

class NtrlocAccess extends HTMLElement {
  constructor() {
    super();
    this.users = [];
    this.groups = [];
    this.selectedType = null; // 'user' | 'group'
    this.selectedId = null;
    this.selectedData = null;
    this.activeTab = 'members'; // for groups: 'members' | 'permissions'
    this.members = [];
    this.permissions = [];
    this.userGroups = [];
    this.userPermissions = [];
    this.tokens = [];
    this.itemTypes = [];
    this.error = '';
  }

  connectedCallback() {
    this.fetchAll();
  }

  async fetchAll() {
    await Promise.all([this.fetchUsers(), this.fetchGroups(), this.fetchItemTypes()]);
    this.render();
  }

  async fetchUsers() {
    try {
      const res = await fetch('/api/admin/users', { credentials: 'include' });
      if (res.ok) this.users = await res.json();
    } catch (e) { /* best effort */ }
  }

  async fetchGroups() {
    try {
      const res = await fetch('/api/admin/groups', { credentials: 'include' });
      if (res.ok) this.groups = await res.json();
    } catch (e) { /* best effort */ }
  }

  async fetchItemTypes() {
    try {
      const res = await fetch('/api/admin/schema/item-types', { credentials: 'include' });
      if (res.ok) this.itemTypes = await res.json();
    } catch (e) { /* best effort */ }
  }

  async selectGroup(groupId) {
    const group = this.groups.find(g => g.id === groupId);
    if (!group) return;
    this.selectedType = 'group';
    this.selectedId = groupId;
    this.selectedData = group;
    this.activeTab = 'members';
    this.error = '';
    await this.fetchGroupMembers();
    await this.fetchGroupPermissions();
    this.render();
  }

  async selectUser(userId) {
    const user = this.users.find(u => u.id === userId);
    if (!user) return;
    this.selectedType = 'user';
    this.selectedId = userId;
    this.selectedData = user;
    this.error = '';
    await Promise.all([
      this.fetchUserGroups(),
      this.fetchUserPermissions(),
      this.fetchUserTokens()
    ]);
    this.render();
  }

  async fetchGroupMembers() {
    try {
      const res = await fetch(`/api/admin/groups/${this.selectedId}/members`, { credentials: 'include' });
      this.members = res.ok ? await res.json() : [];
    } catch (e) { this.members = []; }
  }

  async fetchGroupPermissions() {
    try {
      const res = await fetch(`/api/admin/groups/${this.selectedId}/permissions`, { credentials: 'include' });
      this.permissions = res.ok ? await res.json() : [];
    } catch (e) { this.permissions = []; }
  }

  async fetchUserGroups() {
    try {
      const res = await fetch(`/api/admin/users/${this.selectedId}/groups`, { credentials: 'include' });
      this.userGroups = res.ok ? await res.json() : [];
    } catch (e) { this.userGroups = []; }
  }

  async fetchUserPermissions() {
    try {
      const res = await fetch(`/api/admin/users/${this.selectedId}/permissions`, { credentials: 'include' });
      this.userPermissions = res.ok ? await res.json() : [];
    } catch (e) { this.userPermissions = []; }
  }

  async fetchUserTokens() {
    try {
      const res = await fetch(`/api/admin/users/${this.selectedId}/tokens`, { credentials: 'include' });
      this.tokens = res.ok ? await res.json() : [];
    } catch (e) { this.tokens = []; }
  }

  // --- Group actions ---

  async addMemberToGroup(userId) {
    if (!userId) return;
    try {
      await fetch(`/api/admin/groups/${this.selectedId}/members`, {
        method: 'POST', headers: { 'Content-Type': 'application/json' },
        credentials: 'include', body: JSON.stringify({ userId })
      });
      await this.fetchGroupMembers();
      await this.fetchGroups();
      this.render();
    } catch (e) { this.error = e.message; this.render(); }
  }

  async removeMemberFromGroup(userId) {
    try {
      await fetch(`/api/admin/groups/${this.selectedId}/members/${userId}`, {
        method: 'DELETE', credentials: 'include'
      });
      await this.fetchGroupMembers();
      await this.fetchGroups();
      this.render();
    } catch (e) { this.error = e.message; this.render(); }
  }

  async toggleGroupPermission(itemTypeId, operation) {
    const existing = this.permissions.find(p => p.itemTypeId === itemTypeId);
    const hasOp = existing && existing.operations.includes(operation);
    try {
      if (hasOp) {
        await fetch(`/api/admin/groups/${this.selectedId}/permissions`, {
          method: 'DELETE', headers: { 'Content-Type': 'application/json' },
          credentials: 'include', body: JSON.stringify({ itemTypeId, operation })
        });
      } else {
        await fetch(`/api/admin/groups/${this.selectedId}/permissions`, {
          method: 'POST', headers: { 'Content-Type': 'application/json' },
          credentials: 'include', body: JSON.stringify({ itemTypeId, operation })
        });
      }
      await this.fetchGroupPermissions();
      this.render();
    } catch (e) { this.error = e.message; this.render(); }
  }

  async renameGroup() {
    const input = this.querySelector('[name="rename-group"]');
    const name = input?.value.trim();
    if (!name) return;
    try {
      const res = await fetch(`/api/admin/groups/${this.selectedId}`, {
        method: 'PUT', headers: { 'Content-Type': 'application/json' },
        credentials: 'include', body: JSON.stringify({ name })
      });
      if (!res.ok) throw new Error(await res.text());
      this.selectedData = await res.json();
      await this.fetchGroups();
      this.render();
    } catch (e) { this.error = e.message; this.render(); }
  }

  async deleteGroup() {
    if (!confirm(`Delete group "${this.selectedData.name}"?`)) return;
    try {
      const res = await fetch(`/api/admin/groups/${this.selectedId}`, {
        method: 'DELETE', credentials: 'include'
      });
      if (!res.ok) throw new Error(await res.text());
      this.selectedType = null;
      this.selectedId = null;
      this.selectedData = null;
      await this.fetchGroups();
      this.render();
    } catch (e) { this.error = e.message; this.render(); }
  }

  async createGroup() {
    const input = this.querySelector('[name="new-group-name"]');
    const name = input?.value.trim();
    if (!name) return;
    try {
      const res = await fetch('/api/admin/groups', {
        method: 'POST', headers: { 'Content-Type': 'application/json' },
        credentials: 'include', body: JSON.stringify({ name })
      });
      if (!res.ok) throw new Error(await res.text());
      const group = await res.json();
      await this.fetchGroups();
      this.selectGroup(group.id);
    } catch (e) { this.error = e.message; this.render(); }
  }

  // --- User actions ---

  async addUserToGroup(groupId) {
    if (!groupId) return;
    try {
      await fetch(`/api/admin/groups/${groupId}/members`, {
        method: 'POST', headers: { 'Content-Type': 'application/json' },
        credentials: 'include', body: JSON.stringify({ userId: this.selectedId })
      });
      await Promise.all([this.fetchUserGroups(), this.fetchUserPermissions(), this.fetchGroups()]);
      this.render();
    } catch (e) { this.error = e.message; this.render(); }
  }

  async removeUserFromGroup(groupId) {
    try {
      await fetch(`/api/admin/groups/${groupId}/members/${this.selectedId}`, {
        method: 'DELETE', credentials: 'include'
      });
      await Promise.all([this.fetchUserGroups(), this.fetchUserPermissions(), this.fetchGroups()]);
      this.render();
    } catch (e) { this.error = e.message; this.render(); }
  }

  async createToken() {
    const nameInput = this.querySelector('[name="token-name"]');
    const daysInput = this.querySelector('[name="token-days"]');
    const name = nameInput?.value.trim();
    const expiresInDays = parseInt(daysInput?.value, 10) || null;
    if (!name) { this.error = 'Token name is required'; this.render(); return; }
    try {
      const res = await fetch(`/api/admin/users/${this.selectedId}/tokens`, {
        method: 'POST', headers: { 'Content-Type': 'application/json' },
        credentials: 'include', body: JSON.stringify({ name, expiresInDays })
      });
      if (!res.ok) throw new Error('Failed to create token');
      this._createdToken = await res.json();
      await this.fetchUserTokens();
      this.render();
    } catch (e) { this.error = e.message; this.render(); }
  }

  async revokeToken(tokenId) {
    try {
      await fetch(`/api/admin/users/${this.selectedId}/tokens/${tokenId}`, {
        method: 'DELETE', credentials: 'include'
      });
      await this.fetchUserTokens();
      this.render();
    } catch (e) { this.error = e.message; this.render(); }
  }

  async createUser() {
    const get = name => this.querySelector(`[name="${name}"]`)?.value.trim();
    const body = { externalId: get('new-username'), displayName: get('new-displayname'),
                   email: get('new-email'), password: get('new-password'), role: get('new-role') };
    if (!body.externalId || !body.password) { this.error = 'Username and password required'; this.render(); return; }
    try {
      const res = await fetch('/api/admin/users', {
        method: 'POST', headers: { 'Content-Type': 'application/json' },
        credentials: 'include', body: JSON.stringify(body)
      });
      if (!res.ok) throw new Error(await res.text());
      const user = await res.json();
      await this.fetchUsers();
      await this.fetchGroups();
      this.selectUser(user.id);
    } catch (e) { this.error = e.message; this.render(); }
  }

  async resetPassword() {
    const input = this.querySelector('[name="new-password-reset"]');
    const pw = input?.value.trim();
    if (!pw) { this.error = 'Password is required'; this.render(); return; }
    try {
      const res = await fetch(`/api/admin/users/${this.selectedId}/password`, {
        method: 'PUT', headers: { 'Content-Type': 'application/json' },
        credentials: 'include', body: JSON.stringify({ newPassword: pw })
      });
      if (!res.ok) throw new Error('Failed to reset password');
      input.value = '';
      this.error = '';
      this.render();
    } catch (e) { this.error = e.message; this.render(); }
  }

  escapeHtml(v) {
    const d = document.createElement('div');
    d.textContent = String(v ?? '');
    return d.innerHTML;
  }

  formatDate(iso) {
    if (!iso) return 'Never';
    const d = new Date(iso);
    return d.toLocaleDateString();
  }

  render() {
    const userItems = this.users.map(u => `
      <div class="access-sidebar-item ${this.selectedType === 'user' && this.selectedId === u.id ? 'selected' : ''}" data-select-user="${u.id}">
        <div>
          <span class="item-name">${this.escapeHtml(u.externalId)}</span>${u.isSuperuser ? '<span class="badge-admin">admin</span>' : ''}
          <br><span class="item-sub">${this.escapeHtml(u.displayName)}</span>
        </div>
      </div>
    `).join('');

    const groupItems = this.groups.map(g => `
      <div class="access-sidebar-item ${this.selectedType === 'group' && this.selectedId === g.id ? 'selected' : ''}" data-select-group="${g.id}">
        <span><span class="item-name">${this.escapeHtml(g.name)}</span>${g.name === 'everyone' ? '<span class="badge-default">default</span>' : ''}</span>
        <span class="item-count">${g.memberCount}</span>
      </div>
    `).join('');

    let detailHtml = '<div class="access-empty">Select a user or group from the sidebar.</div>';

    if (this.selectedType === 'group' && this.selectedData) {
      detailHtml = this.renderGroupDetail();
    } else if (this.selectedType === 'user' && this.selectedData) {
      detailHtml = this.renderUserDetail();
    } else if (this.selectedType === 'create-user') {
      detailHtml = this.renderCreateUser();
    } else if (this.selectedType === 'create-group') {
      detailHtml = this.renderCreateGroup();
    }

    this.innerHTML = `
      <div class="access-sidebar">
        <div class="access-sidebar-section">
          <div class="access-sidebar-header">
            <h3>Users</h3>
            <button class="access-btn primary" data-action="show-create-user">+ New</button>
          </div>
          <div class="access-sidebar-list">${userItems}</div>
        </div>
        <div class="access-sidebar-section">
          <div class="access-sidebar-header">
            <h3>Groups</h3>
            <button class="access-btn primary" data-action="show-create-group">+ New</button>
          </div>
          <div class="access-sidebar-list">${groupItems}</div>
        </div>
      </div>
      <div class="access-detail">
        ${this.error ? `<div class="access-error" style="padding:12px 24px;">${this.escapeHtml(this.error)}</div>` : ''}
        ${detailHtml}
      </div>
    `;

    this.bindEvents();
  }

  renderGroupDetail() {
    const g = this.selectedData;
    const isDefault = g.name === 'everyone';
    const tabContent = this.activeTab === 'members' ? this.renderGroupMembers() : this.renderGroupPermissions();

    return `
      <div class="access-detail-header">
        <h2>${this.escapeHtml(g.name)}</h2>
        <span class="type-badge">group</span>
        <div class="actions">
          <button class="access-btn ghost" data-action="rename-group">Rename</button>
          ${!isDefault ? `<button class="access-btn danger" data-action="delete-group">Delete</button>` : ''}
        </div>
      </div>
      <div class="access-detail-tabs">
        <div class="access-detail-tab ${this.activeTab === 'members' ? 'active' : ''}" data-tab="members">Members</div>
        <div class="access-detail-tab ${this.activeTab === 'permissions' ? 'active' : ''}" data-tab="permissions">Permissions</div>
      </div>
      <div class="access-detail-content">${tabContent}</div>
    `;
  }

  renderGroupMembers() {
    const memberIds = new Set(this.members.map(m => m.id));
    const available = this.users.filter(u => !memberIds.has(u.id));
    const rows = this.members.map(m => `
      <tr>
        <td>${this.escapeHtml(m.externalId)}</td>
        <td>${this.escapeHtml(m.displayName)}</td>
        <td>${this.escapeHtml(m.email || '')}</td>
        <td><button class="access-btn danger" data-remove-member="${m.id}">Remove</button></td>
      </tr>
    `).join('');

    const options = available.map(u =>
      `<option value="${u.id}">${this.escapeHtml(u.displayName)} (${this.escapeHtml(u.externalId)})</option>`
    ).join('');

    return `
      <div class="access-section">
        <table class="access-table">
          <thead><tr><th>Username</th><th>Display Name</th><th>Email</th><th></th></tr></thead>
          <tbody>${rows || '<tr><td colspan="4" class="access-empty">No members</td></tr>'}</tbody>
        </table>
        ${available.length > 0 ? `
          <div class="access-add-row">
            <select name="add-member-select"><option value="">-- Add user --</option>${options}</select>
            <button class="access-btn primary" data-action="add-member">Add</button>
          </div>
        ` : ''}
      </div>
    `;
  }

  renderGroupPermissions() {
    const operations = ['item:read', 'item:create', 'item:delete'];
    const opLabels = { 'item:read': 'Read', 'item:create': 'Write', 'item:delete': 'Delete' };

    const rows = this.itemTypes.map(it => {
      const perm = this.permissions.find(p => p.itemTypeId === it.id);
      const ops = perm ? perm.operations : [];
      const cells = operations.map(op => {
        const granted = ops.includes(op);
        return `<td><button class="perm-check ${granted ? 'granted' : ''}" data-perm-item="${it.id}" data-perm-op="${op}">${granted ? '&#10003;' : ''}</button></td>`;
      }).join('');
      return `<tr><td>${this.escapeHtml(it.name)}</td>${cells}</tr>`;
    }).join('');

    return `
      <div class="access-section">
        <table class="access-table">
          <thead><tr><th>Item Type</th>${operations.map(op => `<th>${opLabels[op]}</th>`).join('')}</tr></thead>
          <tbody>${rows || '<tr><td colspan="4" class="access-empty">No item types defined</td></tr>'}</tbody>
        </table>
      </div>
    `;
  }

  renderUserDetail() {
    const u = this.selectedData;
    const groupChips = this.userGroups.map(g =>
      `<span class="access-chip" data-remove-user-group="${g.id}">${this.escapeHtml(g.name)}</span>`
    ).join('');

    const availableGroups = this.groups.filter(g => !this.userGroups.find(ug => ug.id === g.id));
    const groupOptions = availableGroups.map(g =>
      `<option value="${g.id}">${this.escapeHtml(g.name)}</option>`
    ).join('');

    const operations = ['item:read', 'item:create', 'item:delete'];
    const opLabels = { 'item:read': 'Read', 'item:create': 'Write', 'item:delete': 'Delete' };
    const permRows = this.userPermissions.map(p => {
      const cells = operations.map(op => {
        const entry = p.operations.find(o => o.operation === op);
        const granted = !!entry;
        const via = entry ? entry.via.join(', ') : '';
        return `<td><span class="perm-check ${granted ? 'granted' : ''}">${granted ? '&#10003;' : ''}</span></td>`;
      }).join('');
      const viaCol = operations.map(op => {
        const entry = p.operations.find(o => o.operation === op);
        return entry ? entry.via.join(', ') : '';
      }).filter(Boolean);
      const viaText = [...new Set(viaCol.flatMap(v => v.split(', ')))].join(', ');
      return `<tr><td>${this.escapeHtml(p.itemTypeName)}</td>${cells}<td style="font-size:11px;color:var(--muted);">${this.escapeHtml(viaText)}</td></tr>`;
    }).join('');

    const tokenRows = this.tokens.map(t => `
      <tr>
        <td>${this.escapeHtml(t.name)}</td>
        <td style="color:var(--muted);">${this.formatDate(t.createdAt)}</td>
        <td style="color:var(--muted);">${this.formatDate(t.expiresAt)}</td>
        <td><button class="access-btn danger" data-revoke-token="${t.id}">Revoke</button></td>
      </tr>
    `).join('');

    let tokenRevealHtml = '';
    if (this._createdToken) {
      tokenRevealHtml = `
        <div style="margin-top:10px;padding:10px;border:1px solid var(--accent);border-radius:6px;background:var(--bg);">
          <code style="display:block;font-size:12px;word-break:break-all;user-select:all;margin-bottom:4px;">${this.escapeHtml(this._createdToken.token)}</code>
          <div style="font-size:11px;color:var(--muted);">Copy this token now — it won't be shown again.</div>
        </div>
      `;
    }

    return `
      <div class="access-detail-header">
        <h2>${this.escapeHtml(u.externalId)}</h2>
        <span class="type-badge">user</span>
        <div class="actions">
          <button class="access-btn ghost" data-action="show-reset-password">Reset Password</button>
        </div>
      </div>
      <div class="access-detail-content">
        <div class="access-section">
          <h4>Profile</h4>
          <table class="access-table access-profile-table">
            <tbody>
              <tr><td>Username</td><td>${this.escapeHtml(u.externalId)}</td></tr>
              <tr><td>Display Name</td><td>${this.escapeHtml(u.displayName)}</td></tr>
              <tr><td>Email</td><td>${this.escapeHtml(u.email || '')}</td></tr>
              <tr><td>Role</td><td>${u.isSuperuser ? 'Admin' : 'User'}</td></tr>
            </tbody>
          </table>
        </div>

        <div class="access-section">
          <h4>Group Memberships</h4>
          <div style="display:flex;flex-wrap:wrap;gap:6px;margin-bottom:10px;">
            ${groupChips || '<span style="color:var(--muted);font-style:italic;">No groups</span>'}
          </div>
          ${availableGroups.length > 0 ? `
            <div class="access-add-row">
              <select name="add-user-group"><option value="">-- Add to group --</option>${groupOptions}</select>
              <button class="access-btn primary" data-action="add-user-to-group">Add</button>
            </div>
          ` : ''}
        </div>

        <div class="access-section">
          <h4>Effective Permissions</h4>
          ${this.userPermissions.length > 0 ? `
            <table class="access-table">
              <thead><tr><th>Item Type</th>${operations.map(op => `<th>${opLabels[op]}</th>`).join('')}<th style="color:var(--muted);font-size:10px;">Via</th></tr></thead>
              <tbody>${permRows}</tbody>
            </table>
          ` : '<p style="color:var(--muted);font-style:italic;">No permissions (user has no group grants).</p>'}
        </div>

        <div class="access-section">
          <h4>Personal Access Tokens</h4>
          <table class="access-table">
            <thead><tr><th>Name</th><th>Created</th><th>Expires</th><th></th></tr></thead>
            <tbody>${tokenRows || '<tr><td colspan="4" style="color:var(--muted);font-style:italic;">No tokens</td></tr>'}</tbody>
          </table>
          ${tokenRevealHtml}
          <div class="access-add-row">
            <input name="token-name" placeholder="Token name" autocomplete="off">
            <input name="token-days" type="number" placeholder="Days" min="1" style="width:70px;flex:none;">
            <button class="access-btn primary" data-action="create-token">Create</button>
          </div>
        </div>

        <div class="access-section" id="reset-password-section" style="display:none;">
          <h4>Reset Password</h4>
          <div class="access-add-row">
            <input name="new-password-reset" type="password" placeholder="New password" autocomplete="new-password">
            <button class="access-btn danger" data-action="reset-password">Reset</button>
          </div>
        </div>
      </div>
    `;
  }

  renderCreateUser() {
    return `
      <div class="access-detail-header">
        <h2>Create User</h2>
      </div>
      <div class="access-detail-content">
        <div class="access-section">
          <table class="access-table access-profile-table">
            <tbody>
              <tr><td>Username</td><td><input name="new-username" placeholder="Login ID" autocomplete="off" style="padding:6px 8px;border:1px solid var(--border);border-radius:4px;background:var(--bg);color:var(--text);font-size:13px;width:100%;"></td></tr>
              <tr><td>Display Name</td><td><input name="new-displayname" placeholder="Full name" autocomplete="off" style="padding:6px 8px;border:1px solid var(--border);border-radius:4px;background:var(--bg);color:var(--text);font-size:13px;width:100%;"></td></tr>
              <tr><td>Email</td><td><input name="new-email" type="email" placeholder="Email" autocomplete="off" style="padding:6px 8px;border:1px solid var(--border);border-radius:4px;background:var(--bg);color:var(--text);font-size:13px;width:100%;"></td></tr>
              <tr><td>Password</td><td><input name="new-password" type="password" placeholder="Password" autocomplete="new-password" style="padding:6px 8px;border:1px solid var(--border);border-radius:4px;background:var(--bg);color:var(--text);font-size:13px;width:100%;"></td></tr>
              <tr><td>Role</td><td><select name="new-role" style="padding:6px 8px;border:1px solid var(--border);border-radius:4px;background:var(--bg);color:var(--text);font-size:13px;"><option value="USER">User</option><option value="ADMIN">Admin</option></select></td></tr>
            </tbody>
          </table>
          <div style="margin-top:16px;display:flex;gap:8px;">
            <button class="access-btn primary" data-action="create-user">Create User</button>
          </div>
        </div>
      </div>
    `;
  }

  renderCreateGroup() {
    return `
      <div class="access-detail-header">
        <h2>Create Group</h2>
      </div>
      <div class="access-detail-content">
        <div class="access-section">
          <div class="access-add-row">
            <input name="new-group-name" placeholder="Group name" autocomplete="off">
            <button class="access-btn primary" data-action="create-group">Create Group</button>
          </div>
        </div>
      </div>
    `;
  }

  bindEvents() {
    this.querySelectorAll('[data-select-user]').forEach(el => {
      el.addEventListener('click', () => this.selectUser(el.dataset.selectUser));
    });
    this.querySelectorAll('[data-select-group]').forEach(el => {
      el.addEventListener('click', () => this.selectGroup(el.dataset.selectGroup));
    });
    this.querySelectorAll('[data-tab]').forEach(el => {
      el.addEventListener('click', () => { this.activeTab = el.dataset.tab; this.render(); });
    });
    this.querySelectorAll('[data-remove-member]').forEach(el => {
      el.addEventListener('click', () => this.removeMemberFromGroup(el.dataset.removeMember));
    });
    this.querySelector('[data-action="add-member"]')?.addEventListener('click', () => {
      this.addMemberToGroup(this.querySelector('[name="add-member-select"]')?.value);
    });
    this.querySelectorAll('[data-perm-item]').forEach(el => {
      el.addEventListener('click', () => this.toggleGroupPermission(el.dataset.permItem, el.dataset.permOp));
    });
    this.querySelector('[data-action="rename-group"]')?.addEventListener('click', () => {
      const name = prompt('New group name:', this.selectedData.name);
      if (name && name.trim()) {
        this.querySelector('[name="rename-group"]') || (() => {
          const input = document.createElement('input');
          input.name = 'rename-group';
          input.value = name.trim();
          input.style.display = 'none';
          this.appendChild(input);
        })();
        const fakeInput = this.querySelector('[name="rename-group"]');
        if (fakeInput) fakeInput.value = name.trim();
        else {
          const i = document.createElement('input');
          i.name = 'rename-group'; i.value = name.trim(); i.style.display = 'none';
          this.appendChild(i);
        }
        this.renameGroup();
      }
    });
    this.querySelector('[data-action="delete-group"]')?.addEventListener('click', () => this.deleteGroup());
    this.querySelectorAll('[data-remove-user-group]').forEach(el => {
      el.addEventListener('click', () => this.removeUserFromGroup(el.dataset.removeUserGroup));
    });
    this.querySelector('[data-action="add-user-to-group"]')?.addEventListener('click', () => {
      this.addUserToGroup(this.querySelector('[name="add-user-group"]')?.value);
    });
    this.querySelector('[data-action="create-token"]')?.addEventListener('click', () => this.createToken());
    this.querySelectorAll('[data-revoke-token]').forEach(el => {
      el.addEventListener('click', () => this.revokeToken(el.dataset.revokeToken));
    });
    this.querySelector('[data-action="show-reset-password"]')?.addEventListener('click', () => {
      const sec = this.querySelector('#reset-password-section');
      if (sec) sec.style.display = sec.style.display === 'none' ? 'block' : 'none';
    });
    this.querySelector('[data-action="reset-password"]')?.addEventListener('click', () => this.resetPassword());
    this.querySelector('[data-action="show-create-user"]')?.addEventListener('click', () => {
      this.selectedType = 'create-user';
      this.selectedData = {};
      this.error = '';
      this.render();
    });
    this.querySelector('[data-action="show-create-group"]')?.addEventListener('click', () => {
      this.selectedType = 'create-group';
      this.selectedData = {};
      this.error = '';
      this.render();
    });
    this.querySelector('[data-action="create-user"]')?.addEventListener('click', () => this.createUser());
    this.querySelector('[data-action="create-group"]')?.addEventListener('click', () => this.createGroup());
  }
}

customElements.define('ntrloc-access', NtrlocAccess);
