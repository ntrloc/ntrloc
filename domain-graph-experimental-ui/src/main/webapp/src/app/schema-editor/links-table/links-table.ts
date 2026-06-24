import { Component, Input, OnChanges } from '@angular/core';
import { NgIf, NgFor } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { MatIconButton } from '@angular/material/button';
import { MatIcon } from '@angular/material/icon';
import { PropertyGrid } from '../property-grid/property-grid';
import { ItemLinkPerspective, PropertyTypeInfo } from '../model/schema.model';

interface PerspectiveRow {
  key: string;
  groupName: string;
  showGroupName: boolean;
  isCollapsible: boolean;
  perspective: ItemLinkPerspective;
  editableMin: number;
  editableMax: number | null;
  isDeleted: boolean;
  isDirty: boolean;
}

@Component({
  selector: 'app-links-table',
  imports: [NgIf, NgFor, FormsModule, MatIconButton, MatIcon, PropertyGrid],
  templateUrl: './links-table.html',
  styleUrl: './links-table.scss',
})
export class LinksTable implements OnChanges {
  @Input() links: Record<string, ItemLinkPerspective[]> | null = null;
  @Input() propertyTypes: PropertyTypeInfo[] = [];

  rows: PerspectiveRow[] = [];
  visibleRows: PerspectiveRow[] = [];
  private collapsedGroups = new Set<string>();

  ngOnChanges(): void {
    this.buildRows();
  }

  private buildRows(): void {
    const links = this.links ?? {};
    this.rows = [];
    for (const [groupName, perspectives] of Object.entries(links)) {
      const isCollapsible = perspectives.length > 1;
      perspectives.forEach((p, i) => {
        this.rows.push({
          key: `${groupName}::${i}`,
          groupName,
          showGroupName: i === 0,
          isCollapsible,
          perspective: p,
          editableMin: p.minCardinality,
          editableMax: p.maxCardinality,
          isDeleted: false,
          isDirty: false,
        });
      });
    }
    this.recomputeVisibleRows();
  }

  private recomputeVisibleRows(): void {
    const seen = new Set<string>();
    this.visibleRows = this.rows.filter(row => {
      if (!seen.has(row.groupName)) {
        seen.add(row.groupName);
        return true;
      }
      return !this.collapsedGroups.has(row.groupName);
    });
  }

  trackRow(_: number, row: PerspectiveRow): string {
    return row.key;
  }

  toggleGroup(groupName: string): void {
    if (this.collapsedGroups.has(groupName)) {
      this.collapsedGroups.delete(groupName);
    } else {
      this.collapsedGroups.add(groupName);
    }
    this.recomputeVisibleRows();
  }

  isGroupCollapsed(groupName: string): boolean {
    return this.collapsedGroups.has(groupName);
  }

  formatMax(max: number | null): string {
    return max === null ? '∞' : String(max);
  }

  onCardinalityChange(row: PerspectiveRow): void {
    row.isDirty = row.editableMin !== row.perspective.minCardinality
      || row.editableMax !== row.perspective.maxCardinality;
  }

  deleteRow(row: PerspectiveRow): void {
    row.isDeleted = true;
    row.isDirty = true;
  }

  restoreRow(row: PerspectiveRow): void {
    row.isDeleted = false;
    row.isDirty = row.editableMin !== row.perspective.minCardinality
      || row.editableMax !== row.perspective.maxCardinality;
  }
}
