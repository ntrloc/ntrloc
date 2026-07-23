// The "ViewModel" -- UI-facing state (selection, entity lists, dirty tracking, mutation
// building), a direct port of the Angular reference's SchemaViewModel. Angular uses signals for
// reactivity; this app has no reactive framework, so instead this is a plain singleton object
// plus a subscriber list, mirroring the existing onTaskEvent/task-events.js pattern used for a
// different concern. ntrloc-schema-editor.js subscribes once in connectedCallback() and calls
// this.render() on every notification -- the only full re-render trigger, same as how
// ntrloc-tasks.js already re-renders itself on task events.
const schemaViewModelListeners = new Set();

function onSchemaViewModelChange(listener) {
  schemaViewModelListeners.add(listener);
  return () => schemaViewModelListeners.delete(listener);
}

function notifySchemaViewModelChange() {
  schemaViewModelListeners.forEach((listener) => listener());
}

const schemaViewModel = {
  items: [],
  traits: [],
  propertyTypes: [],
  selectedItem: null,
  selectedTrait: null,
  pendingControlledListReplacements: new Map(),
  // Not-yet-saved links (see schema-viewmodels.js's PendingLinkViewModel) -- a plain array, not
  // nested inside items/traits, since a link spans two item types and doesn't belong to either
  // one alone.
  pendingNewLinks: [],
  _loaded: false,

  // Collapse/expand state for the Traits/Properties/Links panels in ntrloc-item-detail.js. Lives
  // here (not as an instance field on ntrloc-item-detail) because that element is destroyed and
  // recreated on every schemaViewModel change -- every field edit calls
  // notifySchemaViewModelChange(), which ntrloc-schema-editor.js reacts to by rebuilding its
  // entire innerHTML, including a fresh <ntrloc-item-detail>. Storing the toggle state on this
  // persistent singleton instead is what makes a panel stay collapsed while the user keeps
  // editing, matching the Angular reference's mat-expansion-panel (whose own open/closed state
  // is intrinsic to the long-lived component instance, not reset by unrelated input changes).
  sectionsExpanded: { traits: true, properties: true, links: true },

  get isDirty() {
    return this.items.some((i) => i.isDirty)
      || this.traits.some((t) => t.isDirty)
      || this.pendingControlledListReplacements.size > 0
      || this.pendingNewLinks.length > 0;
  },

  // The backend has no friendly error for a malformed CREATE_LINK (self-referential links hit an
  // unhandled UNIQUE constraint violation; missing names hit a raw NOT NULL violation) -- see
  // PendingLinkViewModel.isValid. Save is gated on this so an incomplete pending link can never
  // actually be submitted; the UI surfaces why instead of letting the request 500.
  get hasInvalidPendingLinks() {
    return this.pendingNewLinks.some((link) => !link.isValid);
  },

  setPendingControlledList(propertyId, values) {
    this.pendingControlledListReplacements.set(propertyId, values);
    notifySchemaViewModelChange();
  },

  newLink(item) {
    this.pendingNewLinks = [...this.pendingNewLinks, PendingLinkViewModel.create(item.id)];
    notifySchemaViewModelChange();
  },

  removePendingLink(pendingLink) {
    this.pendingNewLinks = this.pendingNewLinks.filter((link) => link !== pendingLink);
    notifySchemaViewModelChange();
  },

  load() {
    if (this._loaded) return Promise.resolve();
    return schemaModel.load().then((schema) => this._applySchema(schema, null, null, null, null));
  },

  reload() {
    const selectedItemId = this.selectedItem?.id ?? null;
    const selectedItemName = this.selectedItem?.name ?? null;
    const selectedTraitId = this.selectedTrait?.id ?? null;
    const selectedTraitName = this.selectedTrait?.name ?? null;
    this._loaded = false;
    this.selectedItem = null;
    this.selectedTrait = null;
    this.pendingControlledListReplacements.clear();
    this.pendingNewLinks = [];
    return schemaModel.reload().then((schema) =>
      this._applySchema(schema, selectedItemId, selectedItemName, selectedTraitId, selectedTraitName));
  },

  selectItem(item) {
    this.selectedTrait = null;
    this.selectedItem = item;
    notifySchemaViewModelChange();
  },

  selectTrait(trait) {
    this.selectedItem = null;
    this.selectedTrait = trait;
    notifySchemaViewModelChange();
  },

  newItem() {
    const vm = ItemDefinitionViewModel.create();
    this.items = [...this.items, vm];
    this.selectItem(vm);
  },

  newTrait() {
    const vm = TraitDefinitionViewModel.create();
    this.traits = [...this.traits, vm];
    this.selectTrait(vm);
  },

  // Direct port of Angular's SchemaViewModel.collectMutations() (schema-view-model.ts lines
  // 75-161) -- same iteration order, same processedLinkIds dedup-per-link-not-per-perspective
  // logic, same "new item/trait short-circuits to a single inline CREATE_* and skips further
  // diffing" behavior. Existing-trait name/description edits are intentionally NOT collected: the
  // backend has no UPDATE_TRAIT mutation yet, matching the Angular reference's own
  // "TODO: UPDATE_TRAIT when backend supports it" gap rather than inventing a mutation type the
  // backend doesn't support.
  collectMutations() {
    const ops = [];
    const processedLinkIds = new Set();

    for (const item of this.items) {
      if (!item.isDirty) continue;

      if (item.isNew) {
        ops.push({
          type: 'CREATE_ITEM',
          name: item.name,
          description: item.description,
          properties: item.properties.map((p) => ({
            name: p.name, description: p.description,
            propertyType: p.type, cardinality: p.cardinality, usage: p.usage,
          })),
        });
        continue;
      }

      if (item.name !== item.originalName || (item.description ?? '') !== (item.originalDescription ?? '')) {
        ops.push({ type: 'UPDATE_ITEM', id: item.id, name: item.name, description: item.description });
      }

      for (const t of item.traitAssignments) {
        if (t.isNew && !t.isRemoved) ops.push({ type: 'IMPLEMENT_TRAIT', itemId: item.id, traitId: t.id });
        else if (t.isRemoved && !t.isNew) ops.push({ type: 'REMOVE_TRAIT', itemId: item.id, traitId: t.id });
      }

      for (const prop of item.properties) {
        if (prop.isReadonly) continue;
        if (prop.isNew) {
          ops.push({ type: 'CREATE_ITEM_PROPERTY', itemId: item.id, name: prop.name, description: prop.description, propertyType: prop.type, cardinality: prop.cardinality, usage: prop.usage });
        } else if (prop.isDeleted) {
          ops.push({ type: 'DELETE_PROPERTY', id: prop.id });
        } else if (prop.isDirty) {
          ops.push({ type: 'UPDATE_PROPERTY', id: prop.id, name: prop.name, description: prop.description, propertyType: prop.type, cardinality: prop.cardinality, usage: prop.usage });
        }
      }

      for (const perspectives of Object.values(item.links)) {
        for (const p of perspectives) {
          if (p.isReadonly) continue;
          if (p.name !== p.originalName || p.minCardinality !== p.originalMinCardinality || p.maxCardinality !== p.originalMaxCardinality) {
            ops.push({ type: 'UPDATE_PERSPECTIVE', id: p.id, name: p.name, description: p.description, minCardinality: p.minCardinality, maxCardinality: p.maxCardinality });
          }
          if (!processedLinkIds.has(p.linkId) && p.link.isDirty) {
            processedLinkIds.add(p.linkId);
            for (const prop of p.link.properties) {
              if (prop.isNew) {
                ops.push({ type: 'CREATE_LINK_PROPERTY', linkId: p.linkId, name: prop.name, description: prop.description, propertyType: prop.type, cardinality: prop.cardinality, usage: prop.usage });
              } else if (prop.isDeleted) {
                ops.push({ type: 'DELETE_PROPERTY', id: prop.id });
              } else if (prop.isDirty) {
                ops.push({ type: 'UPDATE_PROPERTY', id: prop.id, name: prop.name, description: prop.description, propertyType: prop.type, cardinality: prop.cardinality, usage: prop.usage });
              }
            }
          }
        }
      }
    }

    for (const trait of this.traits) {
      if (!trait.isDirty) continue;

      if (trait.isNew) {
        ops.push({
          type: 'CREATE_TRAIT',
          name: trait.name,
          description: trait.description,
          properties: trait.properties.map((p) => ({
            name: p.name, description: p.description,
            propertyType: p.type, cardinality: p.cardinality, usage: p.usage,
          })),
        });
        continue;
      }

      // TODO: UPDATE_TRAIT when backend supports it (matches Angular reference)
    }

    for (const [propertyId, values] of this.pendingControlledListReplacements) {
      ops.push({ type: 'REPLACE_CONTROLLED_LIST', propertyId, values });
    }

    // Invalid pending links (missing target/names, or self-referential) are never emitted --
    // Save is gated on hasInvalidPendingLinks so this should never actually filter anything out
    // in practice, but it's the last line of defense against a request that would 500.
    for (const link of this.pendingNewLinks) {
      if (!link.isValid) continue;
      ops.push({
        type: 'CREATE_LINK',
        properties: link.properties.map((p) => ({
          name: p.name, description: p.description,
          propertyType: p.type, cardinality: p.cardinality, usage: p.usage,
        })),
        perspectives: [
          { itemId: link.firstItemId, name: link.firstPerspectiveName, description: null, minCardinality: link.firstMinCardinality, maxCardinality: link.firstMaxCardinality },
          { itemId: link.secondItemId, name: link.secondPerspectiveName, description: null, minCardinality: link.secondMinCardinality, maxCardinality: link.secondMaxCardinality },
        ],
      });
    }

    return ops;
  },

  // Direct port of Angular's describePendingChanges() -- a parallel, independent human-readable
  // diff used only for the Save-confirm dialog, not sent to the server.
  describePendingChanges() {
    const summaries = [];
    const processedLinkIds = new Set();

    for (const item of this.items) {
      if (!item.isDirty) continue;

      if (item.isNew) {
        const propSummary = item.properties.length > 0
          ? [`${item.properties.length} propert${item.properties.length === 1 ? 'y' : 'ies'}`]
          : [];
        summaries.push({ label: `+ Item Type "${item.name || '(unnamed)'}"`, changes: propSummary });
        continue;
      }

      const changes = [];
      if (item.name !== item.originalName) changes.push(`Name: "${item.originalName}" → "${item.name}"`);
      if ((item.description ?? '') !== (item.originalDescription ?? '')) changes.push('Description updated');

      for (const t of item.traitAssignments) {
        if (t.isNew && !t.isRemoved) changes.push(`+ Trait "${t.name}"`);
        else if (t.isRemoved) changes.push(`- Trait "${t.name}"`);
      }

      for (const prop of item.properties) {
        if (prop.isReadonly) continue;
        if (prop.isNew) changes.push(`+ Property "${prop.name}"`);
        else if (prop.isDeleted) changes.push(`- Property "${prop.originalName}"`);
        else if (prop.isDirty) changes.push(`Property "${prop.originalName}": updated`);
      }

      for (const [perspName, perspectives] of Object.entries(item.links)) {
        for (const p of perspectives) {
          if (p.isReadonly) continue;
          if (p.name !== p.originalName) {
            summaries.push({ label: `"${perspName}"`, changes: [`Name: "${p.originalName}" → "${p.name}"`] });
          }
          if (p.minCardinality !== p.originalMinCardinality || p.maxCardinality !== p.originalMaxCardinality) {
            summaries.push({ label: `"${perspName}" cardinality`, changes: [`${p.originalMinCardinality}..${p.originalMaxCardinality ?? '∞'} → ${p.minCardinality}..${p.maxCardinality ?? '∞'}`] });
          }
        }
      }

      if (changes.length > 0) summaries.push({ label: item.name, changes });

      for (const perspectives of Object.values(item.links)) {
        for (const p of perspectives) {
          if (p.isReadonly || processedLinkIds.has(p.linkId) || !p.link.isDirty) continue;
          processedLinkIds.add(p.linkId);
          const linkChanges = [];
          for (const prop of p.link.properties) {
            if (prop.isNew) linkChanges.push(`+ Property "${prop.name}"`);
            else if (prop.isDeleted) linkChanges.push(`- Property "${prop.originalName}"`);
            else if (prop.isDirty) linkChanges.push(`Property "${prop.originalName}": updated`);
          }
          if (linkChanges.length > 0) summaries.push({ label: this._linkLabel(p.linkId), changes: linkChanges });
        }
      }
    }

    for (const trait of this.traits) {
      if (!trait.isDirty) continue;
      if (trait.isNew) {
        const propSummary = trait.properties.length > 0
          ? [`${trait.properties.length} propert${trait.properties.length === 1 ? 'y' : 'ies'}`]
          : [];
        summaries.push({ label: `+ Trait "${trait.name || '(unnamed)'}"`, changes: propSummary });
      }
    }

    for (const [propertyId, values] of this.pendingControlledListReplacements) {
      const propName = this._findPropertyName(propertyId);
      summaries.push({ label: `Controlled list: "${propName}"`, changes: [`${values.length} value${values.length === 1 ? '' : 's'}`] });
    }

    for (const link of this.pendingNewLinks) {
      if (!link.isValid) continue;
      const firstItemName = this.items.find((i) => i.id === link.firstItemId)?.name ?? '?';
      const secondItemName = this.items.find((i) => i.id === link.secondItemId)?.name ?? '?';
      const propSummary = link.properties.length > 0
        ? [`${link.properties.length} propert${link.properties.length === 1 ? 'y' : 'ies'}`]
        : [];
      summaries.push({
        label: `+ Link "${link.firstPerspectiveName}" (${firstItemName} ↔ ${secondItemName}) "${link.secondPerspectiveName}"`,
        changes: propSummary,
      });
    }

    return summaries;
  },

  _findPropertyName(propertyId) {
    for (const item of this.items) {
      const prop = item.properties.find((p) => p.id === propertyId);
      if (prop) return prop.name;
    }
    for (const trait of this.traits) {
      const prop = trait.properties.find((p) => p.id === propertyId);
      if (prop) return prop.name;
    }
    return propertyId;
  },

  _linkLabel(linkId) {
    const names = [];
    for (const item of this.items) {
      if (Object.values(item.links).some((ps) => ps.some((p) => p.linkId === linkId))) names.push(item.name);
    }
    return `Link (${names.join(' ↔ ')})`;
  },

  _applySchema(schema, restoreItemId, restoreItemName, restoreTraitId, restoreTraitName) {
    this._loaded = true;
    this.propertyTypes = schema.propertyTypes;
    const linkMap = new Map(
      schema.links.map((link) => [link.id, LinkViewModel.fromAdmin(link, schema.propertyTypes)]));

    this.items = [...schema.items]
      .sort((a, b) => a.name.localeCompare(b.name))
      .map((item) => ItemDefinitionViewModel.fromAdmin(item, schema.propertyTypes, linkMap));

    this.traits = [...(schema.traits ?? [])]
      .sort((a, b) => a.name.localeCompare(b.name))
      .map((trait) => TraitDefinitionViewModel.fromAdmin(trait, schema.propertyTypes, linkMap));

    const restoredItem = restoreItemId
      ? this.items.find((i) => i.id === restoreItemId)
      : restoreItemName
        ? this.items.find((i) => i.name === restoreItemName)
        : null;
    if (restoredItem) this.selectedItem = restoredItem;

    const restoredTrait = restoreTraitId
      ? this.traits.find((t) => t.id === restoreTraitId)
      : restoreTraitName
        ? this.traits.find((t) => t.name === restoreTraitName)
        : null;
    if (restoredTrait) this.selectedTrait = restoredTrait;

    notifySchemaViewModelChange();
  },
};
