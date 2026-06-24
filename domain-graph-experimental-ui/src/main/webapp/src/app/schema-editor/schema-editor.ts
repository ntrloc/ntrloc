import { Component, OnInit } from '@angular/core';
import { SchemaService } from './services/schema.service';
import { ItemDefinition, PropertyTypeInfo, Schema } from './model/schema.model';
import { ItemList } from './item-list/item-list';
import { ItemDetail } from './item-detail/item-detail';

@Component({
  selector: 'app-schema-editor',
  imports: [ItemList, ItemDetail],
  templateUrl: './schema-editor.html',
  styleUrl: './schema-editor.scss',
})
export class SchemaEditor implements OnInit {
  schema: Schema | null = null;
  selectedItem: ItemDefinition | null = null;
  propertyTypes: PropertyTypeInfo[] = [];

  constructor(private schemaService: SchemaService) {}

  ngOnInit(): void {
    this.schemaService.getSchema().subscribe(schema => this.schema = schema);
    this.schemaService.getPropertyTypes().subscribe(types => this.propertyTypes = types);
  }

  onItemSelected(item: ItemDefinition): void {
    this.selectedItem = item;
  }
}
