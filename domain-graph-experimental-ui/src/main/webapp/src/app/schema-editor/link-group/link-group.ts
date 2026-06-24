import { Component, Input } from '@angular/core';
import { NgFor } from '@angular/common';
import { LinkPerspective } from '../link-perspective/link-perspective';
import { ItemLinkPerspective, PropertyTypeInfo } from '../model/schema.model';

@Component({
  selector: 'app-link-group',
  imports: [NgFor, LinkPerspective],
  templateUrl: './link-group.html',
  styleUrl: './link-group.scss',
})
export class LinkGroup {
  @Input() linkName!: string;
  @Input() perspectives: ItemLinkPerspective[] = [];
  @Input() propertyTypes: PropertyTypeInfo[] = [];
}
