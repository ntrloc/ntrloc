import { Injectable, signal } from '@angular/core';
import { AdminSchema, ControlledListEntry, DefinitionMutation, PropertyTypeInfo } from './model/schema.model';
import { ItemDefinitionViewModel, LinkViewModel, TraitDefinitionViewModel } from './model/schema.viewmodel';
import { SchemaModel } from './schema-model';

export interface PendingChangeSummary {
  label: string;
  changes: string[];
}

@Injectable({ providedIn: 'root' })
export class SchemaViewModel {
  readonly items = signal<ItemDefinitionViewModel[]>([]);
  readonly traits = signal<TraitDefinitionViewModel[]>([]);
  readonly propertyTypes = signal<PropertyTypeInfo[]>([]);
  readonly selectedItem = signal<ItemDefinitionViewModel | null>(null);
  readonly selectedTrait = signal<TraitDefinitionViewModel | null>(null);

  readonly pendingControlledListReplacements = new Map<string, ControlledListEntry[]>();

  private _loaded = false;

  constructor(private schemaModel: SchemaModel) {}

  get isDirty(): boolean {
    return this.items().some(i => i.isDirty)
      || this.traits().some(t => t.isDirty)
      || this.pendingControlledListReplacements.size > 0;
  }

  setPendingControlledList(propertyId: string, values: ControlledListEntry[]): void {
    this.pendingControlledListReplacements.set(propertyId, values);
  }

  load(): void {
    if (this._loaded) return;
    this.schemaModel.load().subscribe(schema => this._applySchema(schema, null, null, null, null));
  }

  reload(): void {
    const selectedItemId   = this.selectedItem()?.id   ?? null;
    const selectedItemName = this.selectedItem()?.name ?? null;
    const selectedTraitId   = this.selectedTrait()?.id   ?? null;
    const selectedTraitName = this.selectedTrait()?.name ?? null;
    this._loaded = false;
    this.selectedItem.set(null);
    this.selectedTrait.set(null);
    this.pendingControlledListReplacements.clear();
    this.schemaModel.reload().subscribe(schema =>
      this._applySchema(schema, selectedItemId, selectedItemName, selectedTraitId, selectedTraitName));
  }

  selectItem(item: ItemDefinitionViewModel): void {
    this.selectedTrait.set(null);
    this.selectedItem.set(item);
  }

  selectTrait(trait: TraitDefinitionViewModel): void {
    this.selectedItem.set(null);
    this.selectedTrait.set(trait);
  }

  newItem(): void {
    const vm = ItemDefinitionViewModel.create();
    this.items.update(items => [...items, vm]);
    this.selectItem(vm);
  }

  newTrait(): void {
    const vm = TraitDefinitionViewModel.create();
    this.traits.update(traits => [...traits, vm]);
    this.selectTrait(vm);
  }

  collectMutations(): DefinitionMutation[] {
    const ops: DefinitionMutation[] = [];
    const processedLinkIds = new Set<string>();

    for (const item of this.items()) {
      if (!item.isDirty) continue;

      if (item.isNew) {
        ops.push({
          type: 'CREATE_ITEM',
          name: item.name,
          description: item.description,
          properties: item.properties.map(p => ({
            name: p.name, description: p.description,
            propertyType: p.type, cardinality: p.cardinality, usage: p.usage,
          })),
        });
        continue;
      }

      if (item.name !== item.originalName || (item.description ?? '') !== (item.originalDescription ?? '')) {
        ops.push({ type: 'UPDATE_ITEM', id: item.id!, name: item.name, description: item.description });
      }

      for (const t of item.traitAssignments) {
        if (t.isNew && !t.isRemoved) ops.push({ type: 'IMPLEMENT_TRAIT', itemId: item.id!, traitId: t.id });
        else if (t.isRemoved && !t.isNew) ops.push({ type: 'REMOVE_TRAIT', itemId: item.id!, traitId: t.id });
      }

      for (const prop of item.properties) {
        if (!prop.isReadonly) {
          if (prop.isNew) {
            ops.push({ type: 'CREATE_ITEM_PROPERTY', itemId: item.id!, name: prop.name, description: prop.description, propertyType: prop.type, cardinality: prop.cardinality, usage: prop.usage });
            continue;
          } else if (prop.isDeleted) {
            ops.push({ type: 'DELETE_PROPERTY', id: prop.id! });
            continue;
          } else if (prop.isDirty) {
            ops.push({ type: 'UPDATE_PROPERTY', id: prop.id!, name: prop.name, description: prop.description, propertyType: prop.type, cardinality: prop.cardinality, usage: prop.usage });
          }
        }
        // Group assignment applies to all existing properties (own and trait-inherited)
        if (prop.id && prop.groupId !== prop.originalGroupId) {
          ops.push({ type: 'ASSIGN_ITEM_PROPERTY_GROUP', itemId: item.id!, propertyId: prop.id!, groupId: prop.groupId });
        }
      }

      for (const group of item.groups) {
        if (group.isNew && !group.isDeleted) {
          ops.push({ type: 'CREATE_PROPERTY_GROUP', entityId: item.entityId!, name: group.name });
        } else if (group.isDeleted && !group.isNew) {
          ops.push({ type: 'DELETE_PROPERTY_GROUP', id: group.id! });
        } else if (group.isDirty) {
          ops.push({ type: 'UPDATE_PROPERTY_GROUP', id: group.id!, name: group.name });
        }
      }

      for (const perspectives of Object.values(item.links)) {
        for (const p of perspectives) {
          if (p.isReadonly) continue;
          if (p.minCardinality !== p.originalMinCardinality || p.maxCardinality !== p.originalMaxCardinality) {
            ops.push({ type: 'UPDATE_PERSPECTIVE', id: p.id, name: p.name, description: p.description, minCardinality: p.minCardinality, maxCardinality: p.maxCardinality });
          }
          if (!processedLinkIds.has(p.linkId) && p.link.isDirty) {
            processedLinkIds.add(p.linkId);
            for (const prop of p.link.properties) {
              if (prop.isNew) {
                ops.push({ type: 'CREATE_LINK_PROPERTY', linkId: p.linkId, name: prop.name, description: prop.description, propertyType: prop.type, cardinality: prop.cardinality, usage: prop.usage });
              } else if (prop.isDeleted) {
                ops.push({ type: 'DELETE_PROPERTY', id: prop.id! });
              } else if (prop.isDirty) {
                ops.push({ type: 'UPDATE_PROPERTY', id: prop.id!, name: prop.name, description: prop.description, propertyType: prop.type, cardinality: prop.cardinality, usage: prop.usage });
              }
            }
          }
        }
      }
    }

    for (const trait of this.traits()) {
      if (!trait.isDirty) continue;

      if (trait.isNew) {
        ops.push({
          type: 'CREATE_TRAIT',
          name: trait.name,
          description: trait.description,
          properties: trait.properties.map(p => ({
            name: p.name, description: p.description,
            propertyType: p.type, cardinality: p.cardinality, usage: p.usage,
          })),
        });
        continue;
      }

      // TODO: UPDATE_TRAIT when backend supports it
    }

    for (const [propertyId, values] of this.pendingControlledListReplacements) {
      ops.push({ type: 'REPLACE_CONTROLLED_LIST', propertyId, values });
    }

    return ops;
  }

  describePendingChanges(): PendingChangeSummary[] {
    const summaries: PendingChangeSummary[] = [];
    const processedLinkIds = new Set<string>();

    for (const item of this.items()) {
      if (!item.isDirty) continue;

      if (item.isNew) {
        const propSummary = item.properties.length > 0
          ? [`${item.properties.length} propert${item.properties.length === 1 ? 'y' : 'ies'}`]
          : [];
        summaries.push({ label: `+ Item Type "${item.name || '(unnamed)'}"`, changes: propSummary });
        continue;
      }

      const changes: string[] = [];
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

      for (const group of item.groups) {
        if (group.isNew && !group.isDeleted) changes.push(`+ Group "${group.name}"`);
        else if (group.isDeleted) changes.push(`- Group "${group.originalName}"`);
        else if (group.isDirty) changes.push(`Group "${group.originalName}" → "${group.name}"`);
      }

      for (const [perspName, perspectives] of Object.entries(item.links)) {
        for (const p of perspectives) {
          if (p.isReadonly) continue;
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
          const linkChanges: string[] = [];
          for (const prop of p.link.properties) {
            if (prop.isNew) linkChanges.push(`+ Property "${prop.name}"`);
            else if (prop.isDeleted) linkChanges.push(`- Property "${prop.originalName}"`);
            else if (prop.isDirty) linkChanges.push(`Property "${prop.originalName}": updated`);
          }
          if (linkChanges.length > 0) summaries.push({ label: this._linkLabel(p.linkId), changes: linkChanges });
        }
      }
    }

    for (const trait of this.traits()) {
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

    return summaries;
  }

  private _findPropertyName(propertyId: string): string {
    for (const item of this.items()) {
      const prop = item.properties.find(p => p.id === propertyId);
      if (prop) return prop.name;
    }
    for (const trait of this.traits()) {
      const prop = trait.properties.find(p => p.id === propertyId);
      if (prop) return prop.name;
    }
    return propertyId;
  }

  private _linkLabel(linkId: string): string {
    const names: string[] = [];
    for (const item of this.items()) {
      if (Object.values(item.links).some(ps => ps.some(p => p.linkId === linkId))) names.push(item.name);
    }
    return `Link (${names.join(' ↔ ')})`;
  }

  private _applySchema(
    schema: AdminSchema,
    restoreItemId: string | null,
    restoreItemName: string | null,
    restoreTraitId: string | null,
    restoreTraitName: string | null,
  ): void {
    this._loaded = true;
    this.propertyTypes.set(schema.propertyTypes);
    const linkMap = new Map(
      schema.links.map(link => [link.id, LinkViewModel.fromAdmin(link, schema.propertyTypes)])
    );
    this.items.set(
      [...schema.items]
        .sort((a, b) => a.name.localeCompare(b.name))
        .map(item => ItemDefinitionViewModel.fromAdmin(item, schema.propertyTypes, linkMap))
    );
    this.traits.set(
      [...(schema.traits ?? [])]
        .sort((a, b) => a.name.localeCompare(b.name))
        .map(trait => TraitDefinitionViewModel.fromAdmin(trait, schema.propertyTypes, linkMap))
    );

    const restoredItem = restoreItemId
      ? this.items().find(i => i.id === restoreItemId)
      : restoreItemName
        ? this.items().find(i => i.name === restoreItemName)
        : null;
    if (restoredItem) this.selectedItem.set(restoredItem);

    const restoredTrait = restoreTraitId
      ? this.traits().find(t => t.id === restoreTraitId)
      : restoreTraitName
        ? this.traits().find(t => t.name === restoreTraitName)
        : null;
    if (restoredTrait) this.selectedTrait.set(restoredTrait);
  }
}
