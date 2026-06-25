import { Component, OnInit } from '@angular/core';
import { ItemDefinitionViewModel } from './model/schema.viewmodel';
import { SchemaViewModel } from './schema-view-model';
import { ItemList } from './item-list/item-list';
import { ItemDetail } from './item-detail/item-detail';

@Component({
  selector: 'app-schema-editor',
  imports: [ItemList, ItemDetail],
  templateUrl: './schema-editor.html',
  styleUrl: './schema-editor.scss',
})
export class SchemaEditor implements OnInit {
  constructor(readonly schemaViewModel: SchemaViewModel) {}

  ngOnInit(): void {
    this.schemaViewModel.load();
  }

  onItemSelected(item: ItemDefinitionViewModel): void {
    this.schemaViewModel.selectedItem = item;
  }

  onNewItem(): void {
    // TODO: implement new item creation
  }
}
