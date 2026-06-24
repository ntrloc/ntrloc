import { Component, Input } from '@angular/core';
import { NgIf, NgFor, KeyValuePipe } from '@angular/common';
import { MatExpansionModule } from '@angular/material/expansion';
import { ItemDefinition, PropertyTypeInfo } from '../model/schema.model';
import { PropertyGrid } from '../property-grid/property-grid';
import { LinkGroup } from '../link-group/link-group';

@Component({
  selector: 'app-item-detail',
  imports: [NgIf, NgFor, KeyValuePipe, MatExpansionModule, PropertyGrid, LinkGroup],
  templateUrl: './item-detail.html',
  styleUrl: './item-detail.scss',
})
export class ItemDetail {
  @Input() item: ItemDefinition | null = null;
  @Input() propertyTypes: PropertyTypeInfo[] = [];
}
