import { Component, OnInit } from '@angular/core';
import { SchemaService } from './services/schema.service';
import { AdminItemDefinition, AdminSchema, PropertyTypeInfo } from './model/schema.model';
import { ItemList } from './item-list/item-list';
import { ItemDetail } from './item-detail/item-detail';

@Component({
  selector: 'app-schema-editor',
  imports: [ItemList, ItemDetail],
  templateUrl: './schema-editor.html',
  styleUrl: './schema-editor.scss',
})
export class SchemaEditor implements OnInit {
  schema: AdminSchema | null = null;
  selectedItem: AdminItemDefinition | null = null;
  propertyTypes: PropertyTypeInfo[] = [];

  constructor(private schemaService: SchemaService) {}

  ngOnInit(): void {
    this.schemaService.getAdminSchema().subscribe(schema => {
      this.schema = schema;
      this.propertyTypes = schema.propertyTypes;
    });
  }

  onItemSelected(item: AdminItemDefinition): void {
    this.selectedItem = item;
  }

  onNewItem(): void {
    // TODO: implement new item creation
  }
}
