injectStyles('ntrloc-schema-editor-styles', `
  ntrloc-schema-editor {
    display: contents;
  }
  .body-row {
    flex: 1;
    display: flex;
    min-height: 0;
  }
  aside {
    width: 250px;
    flex-shrink: 0;
    border-right: 1px solid var(--border);
    padding: 16px 12px;
    overflow-y: auto;
    display: flex;
    flex-direction: column;
  }
  aside .section-header {
    display: flex;
    align-items: center;
    justify-content: space-between;
    font-size: 11px;
    font-weight: bold;
    letter-spacing: 0.05em;
    color: var(--muted);
    cursor: pointer;
    padding: 6px 4px;
  }
  aside .add-button {
    margin: 6px 0 10px 0;
  }
  aside .type-button {
    display: block;
    width: 100%;
    text-align: left;
    background: none;
    border: none;
    border-radius: 6px;
    color: var(--accent);
    padding: 8px 10px;
    margin-bottom: 2px;
    cursor: pointer;
    font-size: 14px;
  }
  aside .type-button:hover {
    background: var(--panel-bg);
  }
  aside .type-button.selected {
    background: var(--border);
    color: var(--text);
  }
  aside .save-button {
    margin-top: auto;
  }
  main {
    flex: 1;
    overflow: auto;
    padding: 24px 32px;
  }
`);

// Recreates the Angular schema screen's page shell: sidebar (item types + traits) and a detail
// panel hosting <ntrloc-item-detail> for whichever is selected. All state lives in the shared
// schemaViewModel singleton (schema-view-model.js) -- this component just subscribes to it via
// onSchemaViewModelChange and re-renders on every notification, the same "mutate state, call
// render()" convention as every other component in this app, just fed by a shared store instead
// of purely local fields.
class NtrlocSchemaEditor extends HTMLElement {
  connectedCallback() {
    this.render();
    this._unsubscribe = onSchemaViewModelChange(() => this.render());
    schemaViewModel.load();
  }

  disconnectedCallback() {
    if (this._unsubscribe) this._unsubscribe();
  }

  isSelected(kind, id) {
    if (kind === 'item') return schemaViewModel.selectedItem === schemaViewModel.items.find((i) => i.id === id);
    return schemaViewModel.selectedTrait === schemaViewModel.traits.find((t) => t.id === id);
  }

  render() {
    const loading = !schemaViewModel._loaded;

    this.innerHTML = `
      <div class="body-row">
        <aside>
          ${loading ? '<p class="status">Loading...</p>' : `
            <div class="section-header">ITEM TYPES</div>
            <md-outlined-button class="add-button new-item-button">+ New Item Type</md-outlined-button>
            ${schemaViewModel.items.map((item, index) => `
              <button class="type-button item-button ${schemaViewModel.selectedItem === item ? 'selected' : ''}"
                      data-index="${index}">${escapeHtml(item.name || '(unnamed)')}</button>
            `).join('')}

            <div class="section-header" style="margin-top: 16px;">TRAITS</div>
            <md-outlined-button class="add-button new-trait-button">+ New Trait</md-outlined-button>
            ${schemaViewModel.traits.map((trait, index) => `
              <button class="type-button trait-button ${schemaViewModel.selectedTrait === trait ? 'selected' : ''}"
                      data-index="${index}">${escapeHtml(trait.name || '(unnamed)')}</button>
            `).join('')}
          `}
          <md-filled-button class="save-button" ${schemaViewModel.isDirty && !schemaViewModel.hasInvalidPendingLinks && !schemaViewModel.hasInvalidPendingStates ? '' : 'disabled'}>Save</md-filled-button>
        </aside>

        <main>
          <ntrloc-item-detail></ntrloc-item-detail>
        </main>
      </div>
    `;

    this.wireUp();
  }

  wireUp() {
    this.querySelectorAll('.item-button').forEach((button) => {
      button.addEventListener('click', () => {
        schemaViewModel.selectItem(schemaViewModel.items[Number(button.dataset.index)]);
      });
    });
    this.querySelectorAll('.trait-button').forEach((button) => {
      button.addEventListener('click', () => {
        schemaViewModel.selectTrait(schemaViewModel.traits[Number(button.dataset.index)]);
      });
    });

    const newItemButton = this.querySelector('.new-item-button');
    if (newItemButton) newItemButton.addEventListener('click', () => schemaViewModel.newItem());

    const newTraitButton = this.querySelector('.new-trait-button');
    if (newTraitButton) newTraitButton.addEventListener('click', () => schemaViewModel.newTrait());

    this.querySelector('.save-button').addEventListener('click', () => this.onSave());

    const itemDetail = this.querySelector('ntrloc-item-detail');
    itemDetail.configure({
      item: schemaViewModel.selectedItem ?? schemaViewModel.selectedTrait,
      entityKind: schemaViewModel.selectedItem ? 'item' : 'trait',
      propertyTypes: schemaViewModel.propertyTypes,
      availableTraits: schemaViewModel.traits,
      allItems: schemaViewModel.items,
      processOptions: schemaViewModel.processOptions,
    });
  }

  async onSave() {
    const summary = schemaViewModel.describePendingChanges();
    const mutations = schemaViewModel.collectMutations();
    const confirmed = await openSaveConfirmDialog(summary);
    if (!confirmed) return;
    try {
      await schemaService.applyMutations(mutations);
      await schemaViewModel.reload();
    } catch (e) {
      console.error('[save] error:', e);
    }
  }
}

function escapeHtml(value) {
  const div = document.createElement('div');
  div.textContent = String(value ?? '');
  return div.innerHTML;
}

customElements.define('ntrloc-schema-editor', NtrlocSchemaEditor);
