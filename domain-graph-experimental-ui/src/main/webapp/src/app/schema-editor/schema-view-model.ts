import { Injectable, signal } from '@angular/core';
import { AdminSchema, DefinitionMutation, PropertyTypeInfo } from './model/schema.model';
import { ItemDefinitionViewModel, LinkViewModel } from './model/schema.viewmodel';
import { SchemaModel } from './schema-model';

export interface PendingChangeSummary {
  label: string;
  changes: string[];
}

@Injectable({ providedIn: 'root' })
export class SchemaViewModel {
  readonly items = signal<ItemDefinitionViewModel[]>([]);
  readonly propertyTypes = signal<PropertyTypeInfo[]>([]);
  readonly selectedItem = signal<ItemDefinitionViewModel | null>(null);

  private _loaded = false;

  constructor(private schemaModel: SchemaModel) {}

  get isDirty(): boolean {
    return this.items().some(i => i.isDirty);
  }

  load(): void {
    if (this._loaded) return;
    this.schemaModel.load().subscribe(schema => this._applySchema(schema, null));
  }

  reload(): void {
    const selectedId = this.selectedItem()?.id ?? null;
    this._loaded = false;
    this.selectedItem.set(null);
    this.schemaModel.reload().subscribe(schema => this._applySchema(schema, selectedId));
  }

  collectMutations(): DefinitionMutation[] {
    const ops: DefinitionMutation[] = [];
    const processedLinkIds = new Set<string>();

    for (const item of this.items()) {
      if (!item.isDirty) continue;

      if (item.id && (item.name !== item.originalName || (item.description ?? '') !== (item.originalDescription ?? ''))) {
        ops.push({ type: 'UPDATE_ITEM', id: item.id, name: item.name, description: item.description });
      }

      for (const prop of item.properties) {
        if (prop.isNew) {
          ops.push({ type: 'CREATE_ITEM_PROPERTY', itemId: item.id!, name: prop.name, description: prop.description, propertyType: prop.type, cardinality: prop.cardinality, usage: prop.usage });
        } else if (prop.isDeleted) {
          ops.push({ type: 'DELETE_PROPERTY', id: prop.id! });
        } else if (prop.isDirty) {
          ops.push({ type: 'UPDATE_PROPERTY', id: prop.id!, name: prop.name, description: prop.description, propertyType: prop.type, cardinality: prop.cardinality, usage: prop.usage });
        }
      }

      for (const perspectives of Object.values(item.links)) {
        for (const p of perspectives) {
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

    return ops;
  }

  describePendingChanges(): PendingChangeSummary[] {
    const summaries: PendingChangeSummary[] = [];
    const processedLinkIds = new Set<string>();

    for (const item of this.items()) {
      if (!item.isDirty) continue;

      const changes: string[] = [];

      if (item.name !== item.originalName) {
        changes.push(`Name: "${item.originalName}" → "${item.name}"`);
      }
      if ((item.description ?? '') !== (item.originalDescription ?? '')) {
        changes.push('Description updated');
      }

      for (const prop of item.properties) {
        if (prop.isNew) changes.push(`+ Property "${prop.name}"`);
        else if (prop.isDeleted) changes.push(`- Property "${prop.originalName}"`);
        else if (prop.isDirty) changes.push(`Property "${prop.originalName}": updated`);
      }

      for (const [perspName, perspectives] of Object.entries(item.links)) {
        for (const p of perspectives) {
          if (p.minCardinality !== p.originalMinCardinality || p.maxCardinality !== p.originalMaxCardinality) {
            const origMax = p.originalMaxCardinality ?? '∞';
            const newMax = p.maxCardinality ?? '∞';
            changes.push(`"${perspName}" cardinality: ${p.originalMinCardinality}..${origMax} → ${p.minCardinality}..${newMax}`);
          }
        }
      }

      if (changes.length > 0) {
        summaries.push({ label: item.name, changes });
      }

      for (const perspectives of Object.values(item.links)) {
        for (const p of perspectives) {
          if (!processedLinkIds.has(p.linkId) && p.link.isDirty) {
            processedLinkIds.add(p.linkId);
            const linkChanges: string[] = [];
            for (const prop of p.link.properties) {
              if (prop.isNew) linkChanges.push(`+ Property "${prop.name}"`);
              else if (prop.isDeleted) linkChanges.push(`- Property "${prop.originalName}"`);
              else if (prop.isDirty) linkChanges.push(`Property "${prop.originalName}": updated`);
            }
            if (linkChanges.length > 0) {
              summaries.push({ label: this._linkLabel(p.linkId), changes: linkChanges });
            }
          }
        }
      }
    }

    return summaries;
  }

  private _linkLabel(linkId: string): string {
    const names: string[] = [];
    for (const item of this.items()) {
      if (Object.values(item.links).some(ps => ps.some(p => p.linkId === linkId))) {
        names.push(item.name);
      }
    }
    return `Link (${names.join(' ↔ ')})`;
  }

  private _applySchema(schema: AdminSchema, restoreSelectedId: string | null): void {
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
    if (restoreSelectedId) {
      this.selectedItem.set(this.items().find(i => i.id === restoreSelectedId) ?? null);
    }
  }
}
