import { Component, EventEmitter, Input, Output } from '@angular/core';
import { NgFor } from '@angular/common';
import { MatButton } from '@angular/material/button';
import { ItemDefinition } from '../model/schema.model';

@Component({
  selector: 'app-item-list',
  imports: [NgFor, MatButton],
  templateUrl: './item-list.html',
  styleUrl: './item-list.scss',
})
export class ItemList {
  @Input() items: ItemDefinition[] = [];
  @Input() selectedItem: ItemDefinition | null = null;
  @Output() itemSelected = new EventEmitter<ItemDefinition>();

  select(item: ItemDefinition): void {
    this.itemSelected.emit(item);
  }
}
