import { Component, Input } from '@angular/core';
import { NgIf } from '@angular/common';
import { MatExpansionModule } from '@angular/material/expansion';
import { ItemDefinition, PropertyTypeInfo } from '../model/schema.model';
import { PropertyGrid } from '../property-grid/property-grid';
import { LinksTable } from '../links-table/links-table';

@Component({
  selector: 'app-item-detail',
  imports: [NgIf, MatExpansionModule, PropertyGrid, LinksTable],
  templateUrl: './item-detail.html',
  styleUrl: './item-detail.scss',
})
export class ItemDetail {
  @Input() item: ItemDefinition | null = null;
  @Input() propertyTypes: PropertyTypeInfo[] = [];
}
