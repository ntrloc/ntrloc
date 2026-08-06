injectStyles('ntrloc-item-detail-styles', `
  ntrloc-item-detail {
    display: contents;
  }
  .item-header {
    margin-bottom: 20px;
  }
  .eyebrow {
    font-size: 11px;
    font-weight: bold;
    letter-spacing: 0.05em;
    color: var(--muted);
  }
  .states-edit-row {
    margin-top: 12px;
  }
  /* Without this, the diagram's own box wraps tightly to whatever height the states/transitions
     happen to need -- which, for anything short of a wide multi-branch machine, leaves the box
     barely taller than the diagram itself, with no slack for ntrloc-state-machine-diagram.js's own
     vertical centering (added per schema-editor.png) to actually be visible. A fixed floor gives
     it real room to center within, matching that mockup's own proportions -- content that's taller
     than this still just grows the box as before (min-height, not height).
     display:flex (overriding the shared component's own tag-level "display:block", via this more
     specific class selector) is what actually makes that floor usable, not just present: a plain
     block parent's min-height doesn't count as an "explicit height" for a percentage-height child
     to resolve against (confirmed live -- height:100% on .state-machine-diagram-scroll silently
     did nothing while the parent only had min-height), but a flex child's flex-basis is computed
     from the parent's actual used height regardless of *how* that height came about, min-height
     included -- the same reason .editor-diagram-pane/.editor-diagram-el are flex further down in
     ntrloc-state-machine-editor.js. */
  .states-diagram-el {
    display: flex;
    flex-direction: column;
    min-height: 320px;
  }
  .states-diagram-el .state-machine-diagram-scroll {
    flex: 1;
  }
  .state-machines-list {
    list-style: none;
    margin: 0;
    padding: 0;
  }
  .state-machines-list li {
    padding: 4px 0;
    font-size: 13px;
    border-bottom: 1px solid var(--border);
  }
  .field-row {
    display: flex;
    align-items: center;
    gap: 8px;
    margin-top: 4px;
  }
  .field-row .dirty-dot {
    color: var(--dirty-color, #e3b341);
  }
  .field-row .dirty-dot.is-new {
    color: var(--new-color, #3fb950);
  }
  /* Parent type / Abstract / Delete in one row, left/center/right. A 3-column grid (not flex with
     space-between) is what actually keeps the center column truly centered on the row as a whole
     regardless of how wide the left (variable-width select) and right (button vs. status text)
     columns end up being -- space-between only centers a middle item when its neighbors are equal
     width, which these aren't. */
  .three-col-row {
    display: grid;
    grid-template-columns: 1fr auto 1fr;
    align-items: center;
    margin-top: 4px;
  }
  .three-col-row .row-start,
  .three-col-row .row-center,
  .three-col-row .row-end {
    display: flex;
    align-items: center;
    gap: 8px;
  }
  .three-col-row .row-start {
    justify-self: start;
  }
  .three-col-row .row-center {
    justify-self: center;
  }
  .three-col-row .row-end {
    justify-self: end;
  }
  /* Matches the Angular reference's item-detail.scss exactly (plain input + placeholder, not a
     persistent Material label -- md-filled-text-field's floating label doesn't have a mode that
     disappears once you start typing the way a placeholder does, so these two header fields stay
     plain inputs while the rest of the editor uses Material). */
  input.item-name-input {
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
  input.item-name-input:focus {
    border-bottom-color: rgba(255, 255, 255, 0.4);
  }
  input.item-description-input {
    background: transparent;
    border: none;
    border-bottom: 1px solid transparent;
    color: inherit;
    font: inherit;
    opacity: 0.7;
    flex: 1;
    outline: none;
    padding: 2px 0;
  }
  input.item-description-input:focus {
    border-bottom-color: rgba(255, 255, 255, 0.4);
    opacity: 1;
  }
  input.item-description-input::placeholder {
    opacity: 0.4;
  }
  .original-value {
    font-size: 11px;
    color: var(--muted);
    text-decoration: line-through;
    margin: 2px 0 0 0;
  }
  .panel {
    background: var(--panel-bg);
    border-radius: 8px;
    margin-bottom: 16px;
    overflow: hidden;
  }
  .panel-header {
    display: flex;
    align-items: center;
    gap: 8px;
    font-weight: bold;
    padding: 16px 20px;
    cursor: pointer;
    user-select: none;
  }
  .panel-header .chevron {
    color: var(--muted);
    flex-shrink: 0;
    transition: transform 0.15s ease;
  }
  .panel-header .chevron.collapsed {
    transform: rotate(-90deg);
  }
  .panel-body {
    padding: 0 20px 20px 20px;
  }
  .trait-assignments {
    display: flex;
    flex-wrap: wrap;
    gap: 8px;
    align-items: center;
  }
  .trait-chip {
    display: flex;
    align-items: center;
    gap: 6px;
    background: var(--bg);
    border: 1px solid var(--border);
    border-radius: 16px;
    padding: 4px 6px 4px 12px;
    font-size: 13px;
  }
  .trait-chip.trait-removed {
    opacity: 0.5;
    text-decoration: line-through;
  }
  .trait-chip.trait-new {
    border-color: var(--new-color, #3fb950);
  }
  .trait-chip button {
    background: none;
    border: none;
    color: var(--muted);
    cursor: pointer;
    font-size: 14px;
    padding: 0 4px;
  }
  .add-trait-select {
    --md-filled-select-text-field-container-shape: 16px;
  }
  .pending-link-card {
    background: var(--bg);
    border: 1px solid var(--new-color, #3fb950);
    border-radius: 8px;
    padding: 12px 16px;
    margin-bottom: 12px;
  }
  .pending-link-row {
    display: flex;
    align-items: center;
    gap: 12px;
    margin-bottom: 8px;
  }
  .pending-link-row > input {
    background: transparent;
    border: none;
    border-bottom: 1px solid var(--border);
    color: var(--text);
    font: inherit;
    padding: 4px 0;
    flex: 1;
    outline: none;
  }
  .pending-link-row > input:focus {
    border-bottom-color: var(--accent);
  }
  .pending-link-cardinality {
    display: flex;
    align-items: center;
    gap: 4px;
    flex-shrink: 0;
  }
  /* Same fix as ntrloc-links-table.js's .cardinality-input: a Material field here was nearly
     unreadable (native number spinner arrows eating most of a ~56px-wide, label-less field) --
     matching the Angular reference's own plain, monospace, centered, spinner-suppressed input
     instead. */
  .pending-link-cardinality input {
    width: 3ch;
    text-align: center;
    font-family: monospace;
    font-size: 13px;
    background: transparent;
    border: none;
    border-bottom: 1px solid transparent;
    color: var(--text);
    padding: 2px 0;
    outline: none;
  }
  .pending-link-cardinality input:focus {
    border-bottom-color: var(--accent);
  }
  .pending-link-cardinality input::-webkit-inner-spin-button,
  .pending-link-cardinality input::-webkit-outer-spin-button {
    appearance: none;
    margin: 0;
  }
  .pending-link-cardinality input[type=number] {
    -moz-appearance: textfield;
  }
  .pending-link-target-row {
    display: flex;
    align-items: center;
    gap: 8px;
    margin-bottom: 8px;
    color: var(--muted);
  }
  .pending-link-target-row md-filled-select {
    flex: 1;
  }
  .pending-link-hint {
    color: var(--dirty-color, #e3b341);
    font-size: 12px;
    font-style: italic;
    margin: 4px 0 8px 0;
  }
  .pending-link-properties-label {
    font-size: 11px;
    font-weight: bold;
    letter-spacing: 0.05em;
    color: var(--muted);
    margin-bottom: 8px;
  }
`);

// Item/trait detail editor -- port of the Angular reference's ItemDetail component. Mutates the
// passed-in ItemDefinitionViewModel/TraitDefinitionViewModel directly by reference (same
// convention as ntrloc-property-table.js), notifying schemaViewModel listeners after every edit.
// Panel collapse state lives on schemaViewModel.sectionsExpanded (see schema-view-model.js),
// not on this instance, since this element is destroyed and recreated on every edit anywhere in
// the tree.
class NtrlocItemDetail extends HTMLElement {
  configure({ item, entityKind, propertyTypes, availableTraits, allItems }) {
    this._item = item;
    this._entityKind = entityKind;
    this._propertyTypes = propertyTypes || [];
    this._availableTraits = availableTraits || [];
    this._allItems = allItems || [];
    this.render();
  }

  connectedCallback() {
    this.render();
  }

  get isItem() {
    return this._entityKind === 'item';
  }

  get unassignedTraits() {
    const assignedIds = new Set(this._item.traitAssignments.filter((t) => !t.isRemoved).map((t) => t.id));
    return this._availableTraits.filter((t) => t.id && !assignedIds.has(t.id));
  }

  get implementingItems() {
    if (!this._item.id) return [];
    const traitId = this._item.id;
    return this._allItems
      .map((item) => ({ item, assignment: item.traitAssignments.find((t) => t.id === traitId) }))
      .filter((x) => x.assignment !== undefined);
  }

  get unimplementingItems() {
    if (!this._item.id) return [];
    const traitId = this._item.id;
    return this._allItems.filter((item) => !item.traitAssignments.some((t) => t.id === traitId));
  }

  // Only links "created from" this item's own Links panel -- a pending link is rendered once,
  // on the item whose "+ Add Link" button spawned it, not on both sides (the other item doesn't
  // have anything to show yet since the target perspective doesn't exist until Save).
  get itemPendingLinks() {
    if (!this.isItem || !this._item.id) return [];
    return schemaViewModel.pendingNewLinks.filter((link) => link.firstItemId === this._item.id);
  }

  // Excludes the current item (the backend has no friendly error for a self-referential link --
  // see PendingLinkViewModel's isValid comment) and excludes any other not-yet-saved item type
  // (id === null can't be used as a CREATE_LINK perspective's itemId).
  get linkTargetCandidates() {
    return this._allItems.filter((i) => i.id && i.id !== this._item.id);
  }

  // Excludes the current item (an item can't be its own parent type) and every one of its own
  // current descendants (picking one would close a cycle) -- mirrors SchemaManager.
  // resolveSupertypeInclusiveItemTypeIds' server-side BFS client-side, so the dropdown itself
  // never offers a choice the backend would reject. The backend's cycle guard remains the actual
  // enforcement point regardless; this is a UX nicety, not a correctness requirement.
  get supertypeCandidates() {
    if (!this._item.id) return this._allItems.filter((i) => i.id);

    const childrenByParent = new Map();
    for (const i of this._allItems) {
      if (!i.supertypeId) continue;
      if (!childrenByParent.has(i.supertypeId)) childrenByParent.set(i.supertypeId, []);
      childrenByParent.get(i.supertypeId).push(i.id);
    }

    const excluded = new Set([this._item.id]);
    const queue = [this._item.id];
    while (queue.length > 0) {
      const current = queue.shift();
      for (const childId of childrenByParent.get(current) ?? []) {
        if (!excluded.has(childId)) {
          excluded.add(childId);
          queue.push(childId);
        }
      }
    }

    return this._allItems.filter((i) => i.id && !excluded.has(i.id));
  }

  pendingLinkCard(link, index) {
    const targetItem = this._allItems.find((i) => i.id === link.secondItemId);
    return `
      <div class="pending-link-card" data-index="${index}">
        <div class="pending-link-row">
          <input class="pending-link-first-name" value="${escapeHtml(link.firstPerspectiveName)}" placeholder="This side's name (e.g. worksAt)" />
          <div class="pending-link-cardinality">
            <input type="number" min="0" class="pending-link-first-min" value="${link.firstMinCardinality}" />
            <span>..</span>
            <input type="number" min="0" class="pending-link-first-max" value="${link.firstMaxCardinality ?? ''}" placeholder="∞" />
          </div>
        </div>
        <div class="pending-link-target-row">
          <span>↔</span>
          <md-filled-select class="pending-link-target-select">
            <md-select-option value="" ${!link.secondItemId ? 'selected' : ''}><div slot="headline">Target item type…</div></md-select-option>
            ${this.linkTargetCandidates.map((i) => `<md-select-option value="${escapeHtml(i.id)}" ${i.id === link.secondItemId ? 'selected' : ''}><div slot="headline">${escapeHtml(i.name)}</div></md-select-option>`).join('')}
          </md-filled-select>
        </div>
        <div class="pending-link-row">
          <input class="pending-link-second-name" value="${escapeHtml(link.secondPerspectiveName)}" placeholder="${targetItem ? `${escapeHtml(targetItem.name)}'s name for this (e.g. employs)` : "Other side's name"}" />
          <div class="pending-link-cardinality">
            <input type="number" min="0" class="pending-link-second-min" value="${link.secondMinCardinality}" />
            <span>..</span>
            <input type="number" min="0" class="pending-link-second-max" value="${link.secondMaxCardinality ?? ''}" placeholder="∞" />
          </div>
        </div>
        ${!link.isValid ? '<p class="pending-link-hint">Pick a target item type and fill in both names to save this link.</p>' : ''}
        <div class="pending-link-properties-label">Properties</div>
        <ntrloc-property-table class="pending-link-properties-table"></ntrloc-property-table>
        <md-text-button class="pending-link-remove-button">Remove</md-text-button>
      </div>
    `;
  }

  linksBody() {
    const item = this._item;
    const hasExistingLinks = Object.keys(item.links || {}).length > 0;
    const pendingLinks = this.itemPendingLinks;

    const existingLinksHtml = hasExistingLinks
      ? '<ntrloc-links-table class="links-table-el"></ntrloc-links-table>'
      : (pendingLinks.length === 0 ? '<p class="status">No links defined.</p>' : '');

    const pendingLinksHtml = pendingLinks.map((link, index) => this.pendingLinkCard(link, index)).join('');

    // Mirrors the trait-assignment panel's own "save this first" guard (unimplementingItems/
    // item.id checks above) -- a pending link needs a real itemId on both sides, which a
    // not-yet-saved item type doesn't have yet.
    const addLinkAffordance = item.isNew
      ? '<p class="status">Save this item type before adding links.</p>'
      : '<md-outlined-button class="add-link-button">+ Add Link</md-outlined-button>';

    return `${existingLinksHtml}${pendingLinksHtml}${addLinkAffordance}`;
  }

  statesBody() {
    const item = this._item;
    const machines = item.stateMachines.filter((m) => !m.isDeleted);

    // A single machine keeps the existing full diagram preview -- still the common case. Multiple
    // machines fall back to a compact list (a compact multi-diagram preview isn't warranted here;
    // the full diagram for any one machine is one click away via the editor's own tab selector).
    let previewHtml;
    if (machines.length === 0) {
      previewHtml = '<p class="status">No state machines defined.</p>';
    } else if (machines.length === 1) {
      previewHtml = machines[0].states.filter((s) => !s.isDeleted).length > 0
        ? '<ntrloc-state-machine-diagram class="states-diagram-el"></ntrloc-state-machine-diagram>'
        : '<p class="status">No states defined.</p>';
    } else {
      previewHtml = `
        <ul class="state-machines-list">
          ${machines.map((m) => `<li>${escapeHtml(m.name || '(unnamed)')} <span class="status">(${m.states.filter((s) => !s.isDeleted).length} states)</span></li>`).join('')}
        </ul>
      `;
    }

    // Mirrors the Links panel's own "save this first" guard -- CreateStateMachineMutation needs a
    // real itemDefinitionId, which a not-yet-saved item type doesn't have yet.
    const editAffordance = item.isNew
      ? '<p class="status">Save this item type before defining state machines.</p>'
      : '<md-outlined-button class="edit-states-button">Edit State Machines</md-outlined-button>';

    return `${previewHtml}<div class="states-edit-row">${editAffordance}</div>`;
  }

  panel(key, title, bodyHtml) {
    const expanded = schemaViewModel.sectionsExpanded[key];
    // An inline SVG rather than mat-icon's "expand_more" glyph -- this app deliberately doesn't
    // vendor an icon font (every other action here is a text button, e.g. Delete/Revert/Restore),
    // so a small stroked chevron is the dependency-free way to get the same bold, crisp look
    // Angular Material's expansion panel indicator has, rather than relying on a Unicode triangle
    // character's thin, font-dependent rendering.
    const chevronSvg = `
      <svg class="chevron ${expanded ? '' : 'collapsed'}" viewBox="0 0 24 24" width="20" height="20"
           fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round">
        <polyline points="6 9 12 15 18 9"></polyline>
      </svg>
    `;
    return `
      <div class="panel">
        <div class="panel-header" data-section="${key}">
          ${chevronSvg}
          <span>${escapeHtml(title)}</span>
        </div>
        ${expanded ? `<div class="panel-body">${bodyHtml}</div>` : ''}
      </div>
    `;
  }

  render() {
    if (!this._item) {
      this.innerHTML = '<p class="status">Select an item or trait to view its details.</p>';
      return;
    }
    const item = this._item;

    const traitsBody = this.isItem ? `
      <div class="trait-assignments">
        ${item.traitAssignments.map((t) => `
          <div class="trait-chip ${t.isRemoved ? 'trait-removed' : ''} ${t.isNew && !t.isRemoved ? 'trait-new' : ''}" data-trait-id="${escapeHtml(t.id)}">
            <span>${escapeHtml(t.name)}</span>
            ${!t.isRemoved ? '<button class="trait-remove-button" title="Remove trait">✕</button>' : '<button class="trait-restore-button" title="Restore trait">↺</button>'}
          </div>
        `).join('')}
        ${item.traitAssignments.length === 0 ? '<p class="status">No traits assigned.</p>' : ''}
        ${this.unassignedTraits.length > 0 ? `
          <md-filled-select class="add-trait-select">
            <md-select-option value="" selected><div slot="headline">Add trait…</div></md-select-option>
            ${this.unassignedTraits.map((t) => `<md-select-option value="${escapeHtml(t.id)}"><div slot="headline">${escapeHtml(t.name)}</div></md-select-option>`).join('')}
          </md-filled-select>
        ` : ''}
      </div>
    ` : `
      <div class="trait-assignments">
        ${item.id ? `
          ${this.implementingItems.map(({ item: implementingItem, assignment }) => `
            <div class="trait-chip ${assignment.isRemoved ? 'trait-removed' : ''} ${assignment.isNew && !assignment.isRemoved ? 'trait-new' : ''}" data-item-id="${escapeHtml(implementingItem.id)}">
              <span>${escapeHtml(implementingItem.name)}</span>
              ${!assignment.isRemoved ? '<button class="item-remove-button" title="Remove">✕</button>' : '<button class="item-restore-button" title="Restore">↺</button>'}
            </div>
          `).join('')}
          ${this.implementingItems.length === 0 ? '<p class="status">No item types use this trait.</p>' : ''}
          ${this.unimplementingItems.length > 0 ? `
            <md-filled-select class="add-item-type-select">
              <md-select-option value="" selected><div slot="headline">Add to item type…</div></md-select-option>
              ${this.unimplementingItems.map((i) => `<md-select-option value="${escapeHtml(i.id)}"><div slot="headline">${escapeHtml(i.name)}</div></md-select-option>`).join('')}
            </md-filled-select>
          ` : ''}
        ` : '<p class="status">Save this trait before assigning it to item types.</p>'}
      </div>
    `;

    this.innerHTML = `
      <div class="item-header">
        <div class="eyebrow">${this._entityKind === 'item' ? 'ITEM' : 'TRAIT'}</div>

        ${this.isItem ? `
          <div class="three-col-row">
            <div class="row-start">
              ${item.supertypeId !== item.originalSupertypeId ? '<span class="dirty-dot">●</span>' : ''}
              <md-filled-select class="item-supertype-select">
                <md-select-option value="" ${!item.supertypeId ? 'selected' : ''}><div slot="headline">No parent type</div></md-select-option>
                ${this.supertypeCandidates.map((i) => `<md-select-option value="${escapeHtml(i.id)}" ${i.id === item.supertypeId ? 'selected' : ''}><div slot="headline">${escapeHtml(i.name)}</div></md-select-option>`).join('')}
              </md-filled-select>
            </div>
            <div class="row-center">
              ${item.abstractType !== item.originalAbstractType ? '<span class="dirty-dot">●</span>' : ''}
              <md-checkbox class="item-abstract-checkbox" ${item.abstractType ? 'checked' : ''}></md-checkbox>
              <label for="item-abstract-checkbox">Abstract</label>
            </div>
            <div class="row-end">
              ${item.isDeleted
                ? '<span class="status">Marked for deletion -- Save to confirm.</span>'
                : '<md-text-button class="delete-entity-button">Delete Item Type</md-text-button>'}
            </div>
          </div>
        ` : ''}

        <div class="field-row">
          ${item.isNew || item.name !== item.originalName ? `<span class="dirty-dot ${item.isNew ? 'is-new' : ''}">●</span>` : ''}
          <input class="item-name-input" value="${escapeHtml(item.name)}" placeholder="Name" />
          ${!item.isNew && item.name !== item.originalName ? '<md-text-button class="revert-name-button">Revert</md-text-button>' : ''}
        </div>
        ${!item.isNew && item.name !== item.originalName && item.originalName ? `<div class="original-value">${escapeHtml(item.originalName)}</div>` : ''}

        <div class="field-row">
          ${!item.isNew && (item.description ?? '') !== (item.originalDescription ?? '') ? '<span class="dirty-dot">●</span>' : ''}
          <input class="item-description-input" value="${escapeHtml(item.description ?? '')}" placeholder="Description (optional)" />
          ${!item.isNew && (item.description ?? '') !== (item.originalDescription ?? '') ? '<md-text-button class="revert-description-button">Revert</md-text-button>' : ''}
        </div>
        ${!item.isNew && (item.description ?? '') !== (item.originalDescription ?? '') && item.originalDescription ? `<div class="original-value">${escapeHtml(item.originalDescription)}</div>` : ''}

        ${!this.isItem ? (item.isDeleted ? `<p class="status">Marked for deletion -- Save to confirm.</p>` : `
          <div class="field-row">
            <md-text-button class="delete-entity-button">Delete Trait</md-text-button>
          </div>
        `) : ''}
      </div>

      ${this.panel('traits', this.isItem ? 'Traits' : 'Implemented By', traitsBody)}

      ${this.panel('properties', 'Properties', '<ntrloc-property-table class="properties-table"></ntrloc-property-table>')}

      ${this.panel('links', 'Links', this.linksBody())}

      ${this.isItem ? this.panel('states', 'States', this.statesBody()) : ''}
    `;

    this.wireUp();
  }

  wireUp() {
    const item = this._item;

    this.querySelectorAll('.panel-header').forEach((header) => {
      header.addEventListener('click', () => {
        const key = header.dataset.section;
        schemaViewModel.sectionsExpanded[key] = !schemaViewModel.sectionsExpanded[key];
        this.render();
      });
    });

    const nameInput = this.querySelector('.item-name-input');
    nameInput.addEventListener('change', (event) => {
      item.name = event.target.value;
      this.render();
      notifySchemaViewModelChange();
    });

    const descriptionInput = this.querySelector('.item-description-input');
    descriptionInput.addEventListener('change', (event) => {
      item.description = event.target.value || null;
      this.render();
      notifySchemaViewModelChange();
    });

    const supertypeSelect = this.querySelector('.item-supertype-select');
    if (supertypeSelect) supertypeSelect.addEventListener('change', (event) => {
      item.supertypeId = event.target.value || null;
      this.render();
      notifySchemaViewModelChange();
    });

    const abstractCheckbox = this.querySelector('.item-abstract-checkbox');
    if (abstractCheckbox) abstractCheckbox.addEventListener('change', (event) => {
      item.abstractType = event.target.checked;
      this.render();
      notifySchemaViewModelChange();
    });

    const revertNameButton = this.querySelector('.revert-name-button');
    if (revertNameButton) revertNameButton.addEventListener('click', () => {
      item.name = item.originalName;
      this.render();
      notifySchemaViewModelChange();
    });

    // Immediate, dedicated confirm here -- deliberately not relying solely on the later batch
    // Save-confirm dialog, unlike every other soft-delete in this editor (states, properties):
    // deleting a whole item type/trait is categorically more consequential, so it gets its own
    // explicit prompt naming exactly what's being deleted. Skipped for a still-new, unsaved
    // entity -- nothing has been persisted yet, so there's nothing to lose by discarding it.
    const deleteButton = this.querySelector('.delete-entity-button');
    if (deleteButton) deleteButton.addEventListener('click', () => {
      if (!item.isNew) {
        const label = this.isItem ? 'item type' : 'trait';
        if (!confirm(`Delete ${label} "${item.name}"? This cannot be undone.`)) return;
      }
      if (this.isItem) schemaViewModel.deleteItem(item);
      else schemaViewModel.deleteTrait(item);
    });

    const revertDescriptionButton = this.querySelector('.revert-description-button');
    if (revertDescriptionButton) revertDescriptionButton.addEventListener('click', () => {
      item.description = item.originalDescription;
      this.render();
      notifySchemaViewModelChange();
    });

    if (this.isItem) {
      this.querySelectorAll('.trait-remove-button').forEach((button) => {
        button.addEventListener('click', () => {
          const traitId = button.closest('.trait-chip').dataset.traitId;
          const assignment = item.traitAssignments.find((t) => t.id === traitId);
          if (assignment) item.removeTrait(assignment);
          this.render();
          notifySchemaViewModelChange();
        });
      });
      this.querySelectorAll('.trait-restore-button').forEach((button) => {
        button.addEventListener('click', () => {
          const traitId = button.closest('.trait-chip').dataset.traitId;
          const assignment = item.traitAssignments.find((t) => t.id === traitId);
          if (assignment) assignment.isRemoved = false;
          this.render();
          notifySchemaViewModelChange();
        });
      });
      const addTraitSelect = this.querySelector('.add-trait-select');
      if (addTraitSelect) addTraitSelect.addEventListener('change', (event) => {
        const traitId = event.target.value;
        if (!traitId) return;
        const trait = this._availableTraits.find((t) => t.id === traitId);
        if (trait?.id) item.addTrait({ id: trait.id, name: trait.name });
        this.render();
        notifySchemaViewModelChange();
      });
    } else {
      this.querySelectorAll('.item-remove-button').forEach((button) => {
        button.addEventListener('click', () => {
          const itemId = button.closest('.trait-chip').dataset.itemId;
          const targetItem = this._allItems.find((i) => i.id === itemId);
          const assignment = targetItem?.traitAssignments.find((t) => t.id === item.id);
          if (assignment) targetItem.removeTrait(assignment);
          this.render();
          notifySchemaViewModelChange();
        });
      });
      this.querySelectorAll('.item-restore-button').forEach((button) => {
        button.addEventListener('click', () => {
          const itemId = button.closest('.trait-chip').dataset.itemId;
          const targetItem = this._allItems.find((i) => i.id === itemId);
          const assignment = targetItem?.traitAssignments.find((t) => t.id === item.id);
          if (assignment) assignment.isRemoved = false;
          this.render();
          notifySchemaViewModelChange();
        });
      });
      const addItemTypeSelect = this.querySelector('.add-item-type-select');
      if (addItemTypeSelect) addItemTypeSelect.addEventListener('change', (event) => {
        const itemId = event.target.value;
        if (!itemId || !item.id) return;
        const targetItem = this._allItems.find((i) => i.id === itemId);
        if (targetItem) targetItem.addTrait({ id: item.id, name: item.name });
        this.render();
        notifySchemaViewModelChange();
      });
    }

    const propertiesTable = this.querySelector('.properties-table');
    if (propertiesTable) {
      propertiesTable.data = { properties: item.properties, propertyTypes: this._propertyTypes, allowAdd: true };
    }

    const linksTable = this.querySelector('.links-table-el');
    if (linksTable) {
      linksTable.data = { links: item.links, propertyTypes: this._propertyTypes };
    }

    const statesDiagram = this.querySelector('.states-diagram-el');
    if (statesDiagram) {
      // Only rendered when exactly one (non-deleted) state machine exists -- see statesBody().
      const machine = item.stateMachines.filter((m) => !m.isDeleted)[0];
      statesDiagram.data = {
        states: machine.states.filter((s) => !s.isDeleted).map((s) => ({
          ...s,
          transitions: s.transitions.filter((t) => !t.isDeleted),
        })),
      };
    }

    const editStatesButton = this.querySelector('.edit-states-button');
    if (editStatesButton) editStatesButton.addEventListener('click', async () => {
      await openStateMachineEditorDialog(item);
      this.render();
    });

    const addLinkButton = this.querySelector('.add-link-button');
    if (addLinkButton) addLinkButton.addEventListener('click', () => schemaViewModel.newLink(item));

    const pendingLinks = this.itemPendingLinks;
    this.querySelectorAll('.pending-link-card').forEach((card) => {
      const link = pendingLinks[Number(card.dataset.index)];

      card.querySelector('.pending-link-first-name').addEventListener('change', (event) => {
        link.firstPerspectiveName = event.target.value;
        this.render();
        notifySchemaViewModelChange();
      });
      card.querySelector('.pending-link-first-min').addEventListener('change', (event) => {
        link.firstMinCardinality = event.target.value === '' ? 0 : Number(event.target.value);
        notifySchemaViewModelChange();
      });
      card.querySelector('.pending-link-first-max').addEventListener('change', (event) => {
        link.firstMaxCardinality = event.target.value === '' ? null : Number(event.target.value);
        notifySchemaViewModelChange();
      });

      card.querySelector('.pending-link-target-select').addEventListener('change', (event) => {
        link.secondItemId = event.target.value || null;
        this.render();
        notifySchemaViewModelChange();
      });

      card.querySelector('.pending-link-second-name').addEventListener('change', (event) => {
        link.secondPerspectiveName = event.target.value;
        this.render();
        notifySchemaViewModelChange();
      });
      card.querySelector('.pending-link-second-min').addEventListener('change', (event) => {
        link.secondMinCardinality = event.target.value === '' ? 0 : Number(event.target.value);
        notifySchemaViewModelChange();
      });
      card.querySelector('.pending-link-second-max').addEventListener('change', (event) => {
        link.secondMaxCardinality = event.target.value === '' ? null : Number(event.target.value);
        notifySchemaViewModelChange();
      });

      card.querySelector('.pending-link-remove-button').addEventListener('click', () => {
        schemaViewModel.removePendingLink(link);
      });

      const propertiesTable = card.querySelector('.pending-link-properties-table');
      if (propertiesTable) {
        propertiesTable.data = { properties: link.properties, propertyTypes: this._propertyTypes, allowAdd: true };
      }
    });
  }
}

function escapeHtml(value) {
  const div = document.createElement('div');
  div.textContent = String(value ?? '');
  return div.innerHTML;
}

customElements.define('ntrloc-item-detail', NtrlocItemDetail);
