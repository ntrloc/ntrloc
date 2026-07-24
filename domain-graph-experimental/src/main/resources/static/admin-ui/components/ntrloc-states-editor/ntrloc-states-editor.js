injectStyles('ntrloc-states-editor-styles', `
  ntrloc-states-editor {
    display: contents;
  }
  .state-card {
    background: var(--bg);
    border: 1px solid var(--border);
    border-radius: 8px;
    padding: 12px 16px;
    margin-bottom: 12px;
  }
  .state-card.state-new {
    border-color: var(--new-color, #3fb950);
  }
  .state-card.state-deleted {
    opacity: 0.5;
  }
  .state-header-row {
    display: flex;
    align-items: center;
    gap: 8px;
  }
  .state-header-row .name-input {
    background: transparent;
    border: none;
    border-bottom: 1px solid var(--border);
    color: var(--text);
    font: inherit;
    font-weight: 500;
    flex: 1;
    outline: none;
    padding: 4px 0;
  }
  .state-header-row .name-input:focus {
    border-bottom-color: var(--accent);
  }
  .state-header-row .name-input:disabled {
    opacity: 0.6;
  }
  .state-initial-label {
    display: flex;
    align-items: center;
    gap: 4px;
    font-size: 13px;
    color: var(--muted);
    white-space: nowrap;
  }
  .states-dirty-dot {
    color: var(--dirty-color, #e3b341);
  }
  .states-dirty-dot.is-new {
    color: var(--new-color, #3fb950);
  }
  .states-dirty-dot.is-deleted {
    color: var(--deleted-color, #f85149);
  }
  .state-card .actions-row md-text-button {
    --md-text-button-container-height: 30px;
  }
  .original-value {
    font-size: 11px;
    color: var(--muted);
    text-decoration: line-through;
    margin: 2px 0 0 0;
  }
  .state-description-input {
    background: transparent;
    border: none;
    border-bottom: 1px solid transparent;
    color: var(--text);
    opacity: 0.8;
    font: inherit;
    width: 100%;
    outline: none;
    padding: 4px 0;
    margin-top: 4px;
  }
  .state-description-input:focus {
    border-bottom-color: var(--accent);
    opacity: 1;
  }
  .state-process-row {
    display: flex;
    gap: 16px;
    margin-top: 10px;
  }
  .state-process-field {
    flex: 1;
    min-width: 0;
  }
  .state-process-field label {
    display: block;
    font-size: 11px;
    font-weight: bold;
    letter-spacing: 0.05em;
    color: var(--muted);
    margin-bottom: 4px;
  }
  .state-process-field md-filled-select {
    width: 100%;
    --md-filled-field-top-space: 4px;
    --md-filled-field-bottom-space: 4px;
  }
  .transitions-section {
    margin-top: 14px;
    padding-top: 10px;
    border-top: 1px solid var(--border);
  }
  .transitions-label {
    font-size: 11px;
    font-weight: bold;
    letter-spacing: 0.05em;
    color: var(--muted);
    margin-bottom: 8px;
  }
  .transition-row {
    background: var(--panel-bg);
    border-radius: 6px;
    padding: 10px 12px;
    margin-bottom: 8px;
  }
  .transition-row.transition-deleted {
    opacity: 0.5;
  }
  .transition-top-row {
    display: flex;
    align-items: center;
    gap: 8px;
  }
  .transition-top-row .name-input {
    background: transparent;
    border: none;
    border-bottom: 1px solid var(--border);
    color: var(--text);
    font: inherit;
    flex: 1;
    outline: none;
    padding: 3px 0;
  }
  .transition-top-row .name-input:focus {
    border-bottom-color: var(--accent);
  }
  .transition-top-row .name-input:disabled {
    opacity: 0.6;
  }
  .transition-arrow {
    color: var(--muted);
    flex-shrink: 0;
  }
  .transition-top-row md-filled-select {
    flex: 1;
    --md-filled-field-top-space: 2px;
    --md-filled-field-bottom-space: 2px;
  }
  .transition-to-state {
    flex: 1;
    color: var(--text);
    font-size: 13px;
  }
  .transition-second-row {
    display: flex;
    gap: 12px;
    margin-top: 8px;
  }
  .transition-second-row .description-input {
    flex: 2;
    background: transparent;
    border: none;
    border-bottom: 1px solid transparent;
    color: var(--text);
    opacity: 0.8;
    font: inherit;
    outline: none;
    padding: 3px 0;
  }
  .transition-second-row .description-input:focus {
    border-bottom-color: var(--accent);
    opacity: 1;
  }
  .transition-second-row md-filled-select {
    flex: 1;
    --md-filled-field-top-space: 2px;
    --md-filled-field-bottom-space: 2px;
  }
  .guard-condition-field {
    margin-top: 8px;
  }
  .guard-condition-label {
    font-size: 11px;
    font-weight: bold;
    letter-spacing: 0.05em;
    color: var(--muted);
    margin-bottom: 6px;
  }
  .add-transition-hint, .add-state-hint {
    color: var(--muted);
    font-size: 12px;
    font-style: italic;
    margin: 4px 0 8px 0;
  }
  .init-process-section {
    margin-top: 16px;
    padding-top: 12px;
    border-top: 1px solid var(--border);
  }
  .init-process-section label {
    display: block;
    font-size: 11px;
    font-weight: bold;
    letter-spacing: 0.05em;
    color: var(--muted);
    margin-bottom: 4px;
  }
  .init-process-section md-filled-select {
    width: 100%;
    max-width: 320px;
  }
  .init-process-hint {
    color: var(--muted);
    font-size: 12px;
    margin-top: 4px;
  }
`);

// States/transitions editor for an item type -- lives inside ntrloc-item-detail.js's "States"
// panel the same way <ntrloc-property-table>/<ntrloc-links-table> live inside its other panels.
// Mutates the StateViewModel/TransitionViewModel objects on the passed-in
// ItemDefinitionViewModel directly by reference and calls notifySchemaViewModelChange() after
// every edit -- same "dumb presentation component" convention as the rest of this app.
//
// A brand-new state has no real id yet (CREATE_STATE hasn't run), so it can't be the fromStateId
// of a CREATE_TRANSITION in the same save batch -- "+ Add Transition" is only offered once a state
// is saved, mirroring the existing "save this item type before adding links" restriction on
// CREATE_LINK's itemId. Likewise, a transition's target state is only picked while the transition
// itself is new: UpdateTransitionMutation has no toStateId field, so it's locked once saved.
class NtrlocStatesEditor extends HTMLElement {
  set data({ item, processOptions }) {
    this._item = item;
    this._processOptions = processOptions || [];
    this.render();
  }

  get data() {
    return this._item;
  }

  connectedCallback() {
    this.render();
  }

  get savedStates() {
    return (this._item.states || []).filter((s) => !s.isNew && s.id);
  }

  get initialStateCount() {
    return (this._item.states || []).filter((s) => s.isInitial && !s.isDeleted).length;
  }

  processSelect(className, currentValue, disabled) {
    return `
      <md-filled-select class="${className}" ${disabled ? 'disabled' : ''}>
        <md-select-option value="" ${!currentValue ? 'selected' : ''}><div slot="headline">(none)</div></md-select-option>
        ${this._processOptions.map((p) => `<md-select-option value="${escapeHtml(p.key)}" ${p.key === currentValue ? 'selected' : ''}><div slot="headline">${escapeHtml(p.name ?? p.key)}</div></md-select-option>`).join('')}
      </md-filled-select>
    `;
  }

  transitionRow(state, transition, index) {
    return `
      <div class="transition-row ${transition.isDeleted ? 'transition-deleted' : ''}" data-index="${index}">
        <div class="transition-top-row">
          ${transition.isDirty ? `<span class="states-dirty-dot ${transition.isNew ? 'is-new' : ''} ${transition.isDeleted ? 'is-deleted' : ''}">●</span>` : ''}
          <input class="name-input transition-name-input" value="${escapeHtml(transition.name)}" placeholder="Transition name (e.g. Approve)" ${transition.isDeleted ? 'disabled' : ''} />
          <span class="transition-arrow">→</span>
          ${transition.isNew ? `
            <md-filled-select class="transition-to-state-select">
              <md-select-option value="" ${!transition.toStateId ? 'selected' : ''}><div slot="headline">Target state…</div></md-select-option>
              ${this.savedStates.map((s) => `<md-select-option value="${escapeHtml(s.id)}" ${s.id === transition.toStateId ? 'selected' : ''}><div slot="headline">${escapeHtml(s.name)}</div></md-select-option>`).join('')}
            </md-filled-select>
          ` : `<span class="transition-to-state">${escapeHtml(transition.toStateName ?? this.savedStates.find((s) => s.id === transition.toStateId)?.name ?? '?')}</span>`}
          ${!transition.isDeleted ? (
            transition.isNew
              ? '<md-text-button class="remove-new-transition-button">Remove</md-text-button>'
              : `${transition.isDirty ? '<md-text-button class="revert-transition-button">Revert</md-text-button>' : ''}<md-text-button class="delete-transition-button">Delete</md-text-button>`
          ) : '<md-text-button class="restore-transition-button">Restore</md-text-button>'}
        </div>
        <div class="transition-second-row">
          <input class="description-input transition-description-input" value="${escapeHtml(transition.description ?? '')}" placeholder="Description (optional)" ${transition.isDeleted ? 'disabled' : ''} />
          ${this.processSelect('transition-process-select', transition.processId, transition.isDeleted)}
        </div>
        ${!transition.isDeleted ? `
          <div class="guard-condition-field">
            <div class="guard-condition-label">Guard condition</div>
            <ntrloc-predicate-builder class="transition-guard-builder"></ntrloc-predicate-builder>
          </div>
        ` : ''}
      </div>
    `;
  }

  stateCard(state, index) {
    return `
      <div class="state-card ${state.isNew ? 'state-new' : ''} ${state.isDeleted ? 'state-deleted' : ''}" data-index="${index}">
        <div class="state-header-row">
          ${state.isDirty ? `<span class="states-dirty-dot ${state.isNew ? 'is-new' : ''} ${state.isDeleted ? 'is-deleted' : ''}">●</span>` : ''}
          <input class="name-input state-name-input" value="${escapeHtml(state.name)}" placeholder="State name" ${state.isDeleted ? 'disabled' : ''} />
          <label class="state-initial-label">
            <md-checkbox class="state-initial-checkbox" ${state.isInitial ? 'checked' : ''} ${state.isDeleted ? 'disabled' : ''}></md-checkbox>
            Initial
          </label>
          <div class="actions-row">
            ${!state.isDeleted ? (
              state.isNew
                ? '<md-text-button class="remove-new-state-button">Remove</md-text-button>'
                : `${state.isDirty ? '<md-text-button class="revert-state-button">Revert</md-text-button>' : ''}<md-text-button class="delete-state-button">Delete</md-text-button>`
            ) : '<md-text-button class="restore-state-button">Restore</md-text-button>'}
          </div>
        </div>
        ${!state.isNew && state.name !== state.originalName && state.originalName ? `<div class="original-value">${escapeHtml(state.originalName)}</div>` : ''}

        <input class="state-description-input" value="${escapeHtml(state.description ?? '')}" placeholder="Description (optional)" ${state.isDeleted ? 'disabled' : ''} />

        <div class="state-process-row">
          <div class="state-process-field">
            <label>Entry process</label>
            ${this.processSelect('state-entry-process-select', state.entryProcessId, state.isDeleted)}
          </div>
          <div class="state-process-field">
            <label>Exit process</label>
            ${this.processSelect('state-exit-process-select', state.exitProcessId, state.isDeleted)}
          </div>
        </div>

        ${!state.isDeleted ? `
          <div class="transitions-section">
            <div class="transitions-label">Transitions</div>
            ${state.transitions.length === 0 ? '<p class="status">No transitions.</p>' : ''}
            ${state.transitions.map((t, ti) => this.transitionRow(state, t, ti)).join('')}
            ${state.isNew
              ? '<p class="add-transition-hint">Save this state before adding transitions.</p>'
              : '<md-outlined-button class="add-transition-button">+ Add Transition</md-outlined-button>'}
          </div>
        ` : ''}
      </div>
    `;
  }

  render() {
    const item = this._item;
    if (!item) {
      this.innerHTML = '';
      return;
    }
    const states = item.states || [];
    const showInitProcess = this.initialStateCount > 1;

    this.innerHTML = `
      <div class="states-list">
        ${states.length === 0 ? '<p class="status">No states defined.</p>' : ''}
        ${states.map((state, index) => this.stateCard(state, index)).join('')}
      </div>
      ${item.isNew
        ? '<p class="add-state-hint">Save this item type before defining states.</p>'
        : '<md-outlined-button class="add-state-button">+ Add State</md-outlined-button>'}

      ${states.length > 0 ? `
        <div class="init-process-section">
          <label>Initialization process</label>
          ${showInitProcess
            ? this.processSelect('init-process-select', item.initProcessId, false)
            : `<p class="init-process-hint">Only needed when an item type has more than one initial state (currently ${this.initialStateCount}). The single initial state is entered automatically otherwise.</p>`}
        </div>
      ` : ''}
    `;

    this.wireUp();
  }

  wireUp() {
    const item = this._item;
    const states = item.states || [];

    const addStateButton = this.querySelector('.add-state-button');
    if (addStateButton) addStateButton.addEventListener('click', () => {
      item.states = [...states, StateViewModel.create()];
      this.render();
      notifySchemaViewModelChange();
    });

    const initProcessSelect = this.querySelector('.init-process-select');
    if (initProcessSelect) initProcessSelect.addEventListener('change', (event) => {
      item.initProcessId = event.target.value || null;
      notifySchemaViewModelChange();
    });

    this.querySelectorAll('.state-card').forEach((card) => {
      const state = states[Number(card.dataset.index)];

      card.querySelector('.state-name-input')?.addEventListener('change', (event) => {
        state.name = event.target.value;
        this.render();
        notifySchemaViewModelChange();
      });

      card.querySelector('.state-description-input')?.addEventListener('change', (event) => {
        state.description = event.target.value || null;
        notifySchemaViewModelChange();
      });

      card.querySelector('.state-initial-checkbox')?.addEventListener('change', (event) => {
        state.isInitial = event.target.checked;
        this.render();
        notifySchemaViewModelChange();
      });

      card.querySelector('.state-entry-process-select')?.addEventListener('change', (event) => {
        state.entryProcessId = event.target.value || null;
        notifySchemaViewModelChange();
      });

      card.querySelector('.state-exit-process-select')?.addEventListener('change', (event) => {
        state.exitProcessId = event.target.value || null;
        notifySchemaViewModelChange();
      });

      card.querySelector('.revert-state-button')?.addEventListener('click', () => {
        state.revert();
        this.render();
        notifySchemaViewModelChange();
      });

      card.querySelector('.delete-state-button')?.addEventListener('click', () => {
        state.isDeleted = true;
        this.render();
        notifySchemaViewModelChange();
      });

      card.querySelector('.restore-state-button')?.addEventListener('click', () => {
        state.isDeleted = false;
        this.render();
        notifySchemaViewModelChange();
      });

      card.querySelector('.remove-new-state-button')?.addEventListener('click', () => {
        item.states = states.filter((s) => s !== state);
        this.render();
        notifySchemaViewModelChange();
      });

      const addTransitionButton = card.querySelector('.add-transition-button');
      if (addTransitionButton) addTransitionButton.addEventListener('click', () => {
        state.transitions = [...state.transitions, TransitionViewModel.create()];
        this.render();
        notifySchemaViewModelChange();
      });

      card.querySelectorAll('.transition-row').forEach((row) => {
        const transition = state.transitions[Number(row.dataset.index)];

        row.querySelector('.transition-name-input')?.addEventListener('change', (event) => {
          transition.name = event.target.value;
          this.render();
          notifySchemaViewModelChange();
        });

        row.querySelector('.transition-to-state-select')?.addEventListener('change', (event) => {
          transition.toStateId = event.target.value || null;
          notifySchemaViewModelChange();
        });

        row.querySelector('.transition-description-input')?.addEventListener('change', (event) => {
          transition.description = event.target.value || null;
          notifySchemaViewModelChange();
        });

        row.querySelector('.transition-process-select')?.addEventListener('change', (event) => {
          transition.processId = event.target.value || null;
          notifySchemaViewModelChange();
        });

        const guardBuilder = row.querySelector('.transition-guard-builder');
        if (guardBuilder) {
          guardBuilder.data = {
            predicate: transition.guardCondition,
            properties: item.properties.filter((p) => !p.isDeleted).map((p) => ({ name: p.name, type: p.type })),
            onChange: (newPredicate) => {
              transition.guardCondition = newPredicate;
              notifySchemaViewModelChange();
            },
          };
        }

        row.querySelector('.revert-transition-button')?.addEventListener('click', () => {
          transition.revert();
          this.render();
          notifySchemaViewModelChange();
        });

        row.querySelector('.delete-transition-button')?.addEventListener('click', () => {
          transition.isDeleted = true;
          this.render();
          notifySchemaViewModelChange();
        });

        row.querySelector('.restore-transition-button')?.addEventListener('click', () => {
          transition.isDeleted = false;
          this.render();
          notifySchemaViewModelChange();
        });

        row.querySelector('.remove-new-transition-button')?.addEventListener('click', () => {
          state.transitions = state.transitions.filter((t) => t !== transition);
          this.render();
          notifySchemaViewModelChange();
        });
      });
    });
  }
}

function escapeHtml(value) {
  const div = document.createElement('div');
  div.textContent = String(value ?? '');
  return div.innerHTML;
}

customElements.define('ntrloc-states-editor', NtrlocStatesEditor);
