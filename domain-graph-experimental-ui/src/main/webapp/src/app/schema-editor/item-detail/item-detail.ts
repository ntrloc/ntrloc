import { Component, Input } from '@angular/core';
import { NgIf, NgFor } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { MatExpansionModule } from '@angular/material/expansion';
import { MatIconButton } from '@angular/material/button';
import { MatIcon } from '@angular/material/icon';
import { PropertyTypeInfo } from '../model/schema.model';
import { ItemDefinitionViewModel, TraitAssignmentViewModel, TraitDefinitionViewModel } from '../model/schema.viewmodel';
import { PropertyGrid } from '../property-grid/property-grid';
import { LinksTable } from '../links-table/links-table';

@Component({
  selector: 'app-item-detail',
  imports: [NgIf, NgFor, FormsModule, MatExpansionModule, MatIconButton, MatIcon, PropertyGrid, LinksTable],
  templateUrl: './item-detail.html',
  styleUrl: './item-detail.scss',
})
export class ItemDetail {
  @Input() item: ItemDefinitionViewModel | TraitDefinitionViewModel | null = null;
  @Input() entityKind: 'item' | 'trait' = 'item';
  @Input() propertyTypes: PropertyTypeInfo[] = [];
  @Input() availableTraits: TraitDefinitionViewModel[] = [];
  @Input() allItems: ItemDefinitionViewModel[] = [];

  readonly objectKeys = Object.keys;

  get isItem(): boolean { return this.entityKind === 'item'; }

  get asItem(): ItemDefinitionViewModel { return this.item as ItemDefinitionViewModel; }

  // --- Item editor: trait assignment ---

  get unassignedTraits(): TraitDefinitionViewModel[] {
    const assignedIds = new Set(this.asItem.traitAssignments.filter(t => !t.isRemoved).map(t => t.id));
    return this.availableTraits.filter(t => t.id && !assignedIds.has(t.id!));
  }

  addTrait(event: Event): void {
    const select = event.target as HTMLSelectElement;
    const traitId = select.value;
    if (!traitId) return;
    const trait = this.availableTraits.find(t => t.id === traitId);
    if (trait?.id) this.asItem.addTrait({ id: trait.id, name: trait.name });
    select.value = '';
  }

  removeTrait(assignment: TraitAssignmentViewModel): void {
    this.asItem.removeTrait(assignment);
  }

  // --- Trait editor: implemented-by ---

  get implementingItems(): { item: ItemDefinitionViewModel; assignment: TraitAssignmentViewModel }[] {
    if (!this.item?.id) return [];
    const traitId = this.item.id;
    return this.allItems
      .map(item => ({ item, assignment: item.traitAssignments.find(t => t.id === traitId) }))
      .filter((x): x is { item: ItemDefinitionViewModel; assignment: TraitAssignmentViewModel } => x.assignment !== undefined);
  }

  get unimplementingItems(): ItemDefinitionViewModel[] {
    if (!this.item?.id) return [];
    const traitId = this.item.id;
    return this.allItems.filter(item => !item.traitAssignments.some(t => t.id === traitId));
  }

  addItemType(event: Event): void {
    const select = event.target as HTMLSelectElement;
    const itemId = select.value;
    if (!itemId || !this.item?.id) return;
    const item = this.allItems.find(i => i.id === itemId);
    if (item) item.addTrait({ id: this.item.id!, name: this.item.name });
    select.value = '';
  }

  removeItemType(item: ItemDefinitionViewModel): void {
    const traitId = this.item?.id;
    if (!traitId) return;
    const assignment = item.traitAssignments.find(t => t.id === traitId);
    if (assignment) item.removeTrait(assignment);
  }
}
