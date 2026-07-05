import { Component, ElementRef, Input, QueryList, ViewChildren } from '@angular/core';
import { NgIf, NgFor } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { MatExpansionModule } from '@angular/material/expansion';
import { MatButton, MatIconButton } from '@angular/material/button';
import { MatIcon } from '@angular/material/icon';
import { PropertyTypeInfo } from '../model/schema.model';
import { ItemDefinitionViewModel, PropertyDefinitionViewModel, PropertyGroupViewModel, TraitAssignmentViewModel, TraitDefinitionViewModel } from '../model/schema.viewmodel';
import { PropertyGrid } from '../property-grid/property-grid';
import { LinksTable } from '../links-table/links-table';

@Component({
  selector: 'app-item-detail',
  imports: [NgIf, NgFor, FormsModule, MatExpansionModule, MatButton, MatIconButton, MatIcon, PropertyGrid, LinksTable],
  templateUrl: './item-detail.html',
  styleUrl: './item-detail.scss',
})
export class ItemDetail {
  @Input() item: ItemDefinitionViewModel | TraitDefinitionViewModel | null = null;
  @Input() entityKind: 'item' | 'trait' = 'item';
  @Input() propertyTypes: PropertyTypeInfo[] = [];
  @Input() availableTraits: TraitDefinitionViewModel[] = [];
  @Input() allItems: ItemDefinitionViewModel[] = [];

  @ViewChildren('groupNameInput') groupNameInputs!: QueryList<ElementRef<HTMLInputElement>>;

  renamingGroup: PropertyGroupViewModel | null = null;

  readonly objectKeys = Object.keys;

  get isItem(): boolean { return this.entityKind === 'item'; }

  get asItem(): ItemDefinitionViewModel { return this.item as ItemDefinitionViewModel; }

  // --- Property group helpers (item types only) ---

  get activeGroups(): PropertyGroupViewModel[] {
    if (!this.isItem) return [];
    return this.asItem.groups.filter(g => !g.isDeleted);
  }

  get ungroupedProperties(): PropertyDefinitionViewModel[] {
    return (this.item?.properties ?? []).filter(p => !p.groupId);
  }

  propertiesForGroup(groupId: string | null): PropertyDefinitionViewModel[] {
    return (this.item?.properties ?? []).filter(p => p.groupId === groupId);
  }

  addGroup(): void {
    if (!this.isItem) return;
    const group = PropertyGroupViewModel.create();
    this.asItem.groups.push(group);
    this.renamingGroup = group;
    setTimeout(() => {
      const inputs = this.groupNameInputs.toArray();
      if (inputs.length > 0) inputs[0].nativeElement.select();
    });
  }

  deleteGroup(group: PropertyGroupViewModel, event: Event): void {
    event.stopPropagation();
    if (!this.isItem) return;
    this.asItem.properties.forEach(p => { if (p.groupId === group.id) p.groupId = null; });
    if (group.isNew) {
      const idx = this.asItem.groups.indexOf(group);
      if (idx !== -1) this.asItem.groups.splice(idx, 1);
    } else {
      group.isDeleted = true;
    }
    if (this.renamingGroup === group) this.renamingGroup = null;
  }

  startRenameGroup(group: PropertyGroupViewModel, event: Event): void {
    event.stopPropagation();
    this.renamingGroup = group;
    setTimeout(() => {
      const inputs = this.groupNameInputs.toArray();
      if (inputs.length > 0) inputs[0].nativeElement.select();
    });
  }

  finishRenameGroup(): void {
    this.renamingGroup = null;
  }

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
