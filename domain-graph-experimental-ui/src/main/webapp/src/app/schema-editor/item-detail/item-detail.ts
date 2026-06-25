import { Component, Input } from '@angular/core';
import { NgIf } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { MatExpansionModule } from '@angular/material/expansion';
import { MatIconButton } from '@angular/material/button';
import { MatIcon } from '@angular/material/icon';
import { PropertyTypeInfo } from '../model/schema.model';
import { ItemDefinitionViewModel } from '../model/schema.viewmodel';
import { PropertyGrid } from '../property-grid/property-grid';
import { LinksTable } from '../links-table/links-table';

@Component({
  selector: 'app-item-detail',
  imports: [NgIf, FormsModule, MatExpansionModule, MatIconButton, MatIcon, PropertyGrid, LinksTable],
  templateUrl: './item-detail.html',
  styleUrl: './item-detail.scss',
})
export class ItemDetail {
  @Input() item: ItemDefinitionViewModel | null = null;
  @Input() propertyTypes: PropertyTypeInfo[] = [];

  readonly objectKeys = Object.keys;
}
