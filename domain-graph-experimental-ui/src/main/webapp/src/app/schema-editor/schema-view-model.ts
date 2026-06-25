import { Injectable } from '@angular/core';
import { PropertyTypeInfo } from './model/schema.model';
import { ItemDefinitionViewModel } from './model/schema.viewmodel';
import { SchemaModel } from './schema-model';

@Injectable({ providedIn: 'root' })
export class SchemaViewModel {
  items: ItemDefinitionViewModel[] = [];
  propertyTypes: PropertyTypeInfo[] = [];
  selectedItem: ItemDefinitionViewModel | null = null;

  private _loaded = false;

  constructor(private schemaModel: SchemaModel) {}

  get isDirty(): boolean {
    return this.items.some(i => i.isDirty);
  }

  load(): void {
    if (this._loaded) return;
    this.schemaModel.load().subscribe(schema => {
      this._loaded = true;
      this.propertyTypes = schema.propertyTypes;
      this.items = [...schema.items]
        .sort((a, b) => a.name.localeCompare(b.name))
        .map(item => ItemDefinitionViewModel.fromAdmin(item, schema.propertyTypes));
    });
  }
}
