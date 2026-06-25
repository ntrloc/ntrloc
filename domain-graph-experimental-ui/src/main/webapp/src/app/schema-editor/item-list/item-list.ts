import { Component, EventEmitter, Input, Output } from '@angular/core';
import { NgFor } from '@angular/common';
import { MatButton } from '@angular/material/button';
import { MatIcon } from '@angular/material/icon';
import { ItemDefinitionViewModel } from '../model/schema.viewmodel';

@Component({
  selector: 'app-item-list',
  imports: [NgFor, MatButton, MatIcon],
  templateUrl: './item-list.html',
  styleUrl: './item-list.scss',
})
export class ItemList {
  @Input() items: ItemDefinitionViewModel[] = [];
  @Input() selectedItem: ItemDefinitionViewModel | null = null;
  @Output() itemSelected = new EventEmitter<ItemDefinitionViewModel>();
  @Output() newItemRequested = new EventEmitter<void>();

  select(item: ItemDefinitionViewModel): void {
    this.itemSelected.emit(item);
  }
}
