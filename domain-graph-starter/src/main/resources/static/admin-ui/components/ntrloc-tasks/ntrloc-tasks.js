injectStyles('ntrloc-tasks-styles', `
  ntrloc-tasks {
    display: contents;
  }
  ntrloc-tasks main {
    flex: 1;
    overflow: auto;
    padding: 24px 32px;
  }
  ntrloc-tasks table.task-table {
    width: 100%;
    border-collapse: collapse;
  }
  ntrloc-tasks table.task-table th {
    text-align: left;
    font-size: 11px;
    color: var(--muted);
    font-weight: bold;
    letter-spacing: 0.05em;
    padding: 6px 8px;
    border-bottom: 1px solid var(--border);
  }
  ntrloc-tasks table.task-table td {
    padding: 8px;
    border-bottom: 1px solid transparent;
  }
  ntrloc-tasks table.task-table tbody tr {
    cursor: pointer;
  }
  ntrloc-tasks table.task-table tbody tr:hover {
    background: var(--panel-bg);
  }
  ntrloc-tasks .detail-toolbar {
    display: flex;
    align-items: center;
    gap: 12px;
    margin-bottom: 20px;
  }
  ntrloc-tasks .detail-field {
    margin-bottom: 12px;
  }
  ntrloc-tasks .detail-field label {
    display: block;
    font-size: 11px;
    font-weight: bold;
    letter-spacing: 0.05em;
    color: var(--muted);
    margin-bottom: 4px;
  }
  ntrloc-tasks textarea.variables-input {
    width: 100%;
    max-width: 480px;
    box-sizing: border-box;
    height: 120px;
    padding: 8px;
    border: 1px solid var(--border);
    border-radius: 4px;
    background: var(--bg);
    color: var(--text);
    font-family: monospace;
    font-size: 12px;
  }
`);

// Lists Flowable tasks visible to the current user (TaskAdminController filters to
// assignee/candidate-user/candidate-group unless the caller is a superuser) and lets them claim
// and complete one. Mirrors ntrloc-processes.js's list<->detail swap (an internal state switch,
// not a route change), but the "detail" here is a claim/complete action pane, not a full editor.
class NtrlocTasks extends HTMLElement {
  constructor() {
    super();
    this.tasks = [];
    this.loading = true;
    this.error = null;
    this.selectedTask = null;
    this.variablesText = '{}';
    this.actionStatus = null;
  }

  connectedCallback() {
    this.render();
    this.load();
    // Route elements stay mounted once shown (index.html's router just toggles a CSS class), so
    // connectedCallback only fires once for the whole app session -- without this, a task that
    // shows up after this element's first load (e.g. from running a process on the Processes tab)
    // would never appear until a full page reload.
    this._onHashChange = () => {
      if (location.hash.replace(/^#/, '') === this.dataset.route) {
        this.selectedTask = null;
        this.load();
      }
    };
    window.addEventListener('hashchange', this._onHashChange);
    // Live update: a task created/assigned/completed elsewhere (e.g. Run on the Processes tab)
    // shows up here without the user leaving and coming back. Skipped while viewing a task's
    // detail pane -- an unrelated event shouldn't yank someone out of a claim/complete in
    // progress; they'll see fresh data next time they return to the list regardless.
    this._unsubscribeTaskEvents = onTaskEvent(() => {
      if (!this.selectedTask) this.load();
    });
  }

  disconnectedCallback() {
    window.removeEventListener('hashchange', this._onHashChange);
    if (this._unsubscribeTaskEvents) this._unsubscribeTaskEvents();
  }

  async load() {
    this.loading = true;
    this.render();
    try {
      const response = await fetch('/api/admin/process/tasks', { credentials: 'include' });
      if (!response.ok) throw new Error('Request failed: ' + response.status);
      this.tasks = await response.json();
      this.error = null;
    } catch (e) {
      this.error = 'Failed to load tasks: ' + e.message;
    } finally {
      this.loading = false;
      this.render();
    }
  }

  async claim(taskId) {
    try {
      const response = await fetch(`/api/admin/process/tasks/${encodeURIComponent(taskId)}/claim`, {
        method: 'POST',
        credentials: 'include',
      });
      if (!response.ok) {
        const error = await response.json().catch(() => ({ message: 'Request failed: ' + response.status }));
        throw new Error(error.message || 'Request failed: ' + response.status);
      }
      this.selectedTask = await response.json();
      this.actionStatus = null;
    } catch (e) {
      this.actionStatus = 'Failed to claim: ' + e.message;
    }
    this.render();
  }

  async complete(taskId) {
    let variables;
    try {
      variables = this.variablesText.trim() ? JSON.parse(this.variablesText) : {};
    } catch (e) {
      this.actionStatus = 'Variables must be valid JSON: ' + e.message;
      this.render();
      return;
    }
    try {
      const response = await fetch(`/api/admin/process/tasks/${encodeURIComponent(taskId)}/complete`, {
        method: 'POST',
        credentials: 'include',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(variables),
      });
      if (!response.ok) {
        const error = await response.json().catch(() => ({ message: 'Request failed: ' + response.status }));
        throw new Error(error.message || 'Request failed: ' + response.status);
      }
      this.selectedTask = null;
      this.actionStatus = null;
      this.variablesText = '{}';
      this.render();
      this.load();
      return;
    } catch (e) {
      this.actionStatus = 'Failed to complete: ' + e.message;
    }
    this.render();
  }

  render() {
    if (this.selectedTask) {
      this.renderDetail();
      return;
    }

    this.innerHTML = `
      <main>
        ${this.loading ? '<p class="status">Loading...</p>' : ''}
        ${this.error ? `<p class="status">${escapeHtml(this.error)}</p>` : ''}
        ${!this.loading && !this.error ? (
          this.tasks.length === 0 ? '<p class="status">No tasks assigned or available to claim.</p>' : `
            <table class="task-table">
              <thead>
                <tr>
                  <th>Name</th>
                  <th>Process Instance</th>
                  <th>Assignee</th>
                  <th>Created</th>
                </tr>
              </thead>
              <tbody>
                ${this.tasks.map((t) => `
                  <tr data-id="${escapeHtml(t.id)}">
                    <td>${escapeHtml(t.name ?? t.id)}</td>
                    <td>${escapeHtml(t.processInstanceId)}</td>
                    <td>${escapeHtml(t.assignee ?? '(unassigned)')}</td>
                    <td>${t.createTime ? escapeHtml(new Date(t.createTime).toLocaleString()) : ''}</td>
                  </tr>
                `).join('')}
              </tbody>
            </table>
          `
        ) : ''}
      </main>
    `;

    this.querySelectorAll('tbody tr').forEach((row) => {
      row.addEventListener('click', () => {
        this.selectedTask = this.tasks.find((t) => t.id === row.dataset.id) || null;
        this.actionStatus = null;
        this.render();
      });
    });
  }

  renderDetail() {
    const task = this.selectedTask;
    this.innerHTML = `
      <main>
        <div class="detail-toolbar">
          <md-text-button class="back-button">&larr; Back to Tasks</md-text-button>
          ${this.actionStatus ? `<span class="status">${escapeHtml(this.actionStatus)}</span>` : ''}
        </div>
        <div class="detail-field">
          <label>Name</label>
          <div>${escapeHtml(task.name ?? task.id)}</div>
        </div>
        <div class="detail-field">
          <label>ID</label>
          <div>${escapeHtml(task.id)}</div>
        </div>
        <div class="detail-field">
          <label>Process Instance</label>
          <div>${escapeHtml(task.processInstanceId)}</div>
        </div>
        <div class="detail-field">
          <label>Assignee</label>
          <div>${escapeHtml(task.assignee ?? '(unassigned)')}</div>
        </div>
        ${!task.assignee ? '<md-filled-button class="claim-button">Claim</md-filled-button>' : ''}
        <div class="detail-field" style="margin-top: 20px;">
          <label>Complete with variables (JSON)</label>
          <textarea class="variables-input">${escapeHtml(this.variablesText)}</textarea>
        </div>
        <md-filled-button class="complete-button">Complete</md-filled-button>
      </main>
    `;

    this.querySelector('.back-button').addEventListener('click', () => {
      this.selectedTask = null;
      this.actionStatus = null;
      this.render();
      this.load();
    });
    const claimButton = this.querySelector('.claim-button');
    if (claimButton) claimButton.addEventListener('click', () => this.claim(task.id));
    this.querySelector('.variables-input').addEventListener('change', (event) => {
      this.variablesText = event.target.value;
    });
    this.querySelector('.complete-button').addEventListener('click', () => {
      this.variablesText = this.querySelector('.variables-input').value;
      this.complete(task.id);
    });
  }
}

function escapeHtml(value) {
  const div = document.createElement('div');
  div.textContent = String(value ?? '');
  return div.innerHTML;
}

customElements.define('ntrloc-tasks', NtrlocTasks);
