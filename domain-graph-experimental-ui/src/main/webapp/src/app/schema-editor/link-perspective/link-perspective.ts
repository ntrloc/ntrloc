import { Component, Input, OnChanges } from '@angular/core';
import { NgIf } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { MatIconButton } from '@angular/material/button';
import { MatIcon } from '@angular/material/icon';
import { PropertyGrid } from '../property-grid/property-grid';
import { ItemLinkPerspective, PropertyTypeInfo } from '../model/schema.model';

@Component({
  selector: 'app-link-perspective',
  imports: [NgIf, FormsModule, MatIconButton, MatIcon, PropertyGrid],
  templateUrl: './link-perspective.html',
  styleUrl: './link-perspective.scss',
})
export class LinkPerspective implements OnChanges {
  @Input() perspective!: ItemLinkPerspective;
  @Input() propertyTypes: PropertyTypeInfo[] = [];

  editableMin = 0;
  editableMax: number | null = null;
  isDeleted = false;
  isDirty = false;

  ngOnChanges(): void {
    this.editableMin = this.perspective.minCardinality;
    this.editableMax = this.perspective.maxCardinality;
    this.isDeleted = false;
    this.isDirty = false;
  }

  formatMax(max: number | null): string {
    return max === null ? '∞' : String(max);
  }

  onCardinalityChange(): void {
    this.isDirty = this.editableMin !== this.perspective.minCardinality
      || this.editableMax !== this.perspective.maxCardinality;
  }

  deletePerspective(): void {
    this.isDeleted = true;
    this.isDirty = true;
  }

  restorePerspective(): void {
    this.isDeleted = false;
    this.isDirty = this.editableMin !== this.perspective.minCardinality
      || this.editableMax !== this.perspective.maxCardinality;
  }
}
