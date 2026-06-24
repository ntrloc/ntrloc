import { Component, EventEmitter, Input, Output } from '@angular/core';
import { NgFor } from '@angular/common';
import { MatButton } from '@angular/material/button';
import { MatIcon } from '@angular/material/icon';
import { AdminItemDefinition } from '../model/schema.model';

@Component({
  selector: 'app-item-list',
  imports: [NgFor, MatButton, MatIcon],
  templateUrl: './item-list.html',
  styleUrl: './item-list.scss',
})
export class ItemList {
  @Input() items: AdminItemDefinition[] = [];
  @Input() selectedItem: AdminItemDefinition | null = null;
  @Output() itemSelected = new EventEmitter<AdminItemDefinition>();
  @Output() newItemRequested = new EventEmitter<void>();

  select(item: AdminItemDefinition): void {
    this.itemSelected.emit(item);
  }
}
