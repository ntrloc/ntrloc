import { Component, ElementRef, Input, QueryList, ViewChildren } from '@angular/core';
import { NgFor, NgIf } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { MatButton, MatIconButton } from '@angular/material/button';
import { MatIcon } from '@angular/material/icon';
import { PropertyTypeInfo } from '../model/schema.model';
import { PropertyDefinitionViewModel } from '../model/schema.viewmodel';

@Component({
  selector: 'app-property-grid',
  imports: [NgIf, NgFor, FormsModule, MatButton, MatIconButton, MatIcon],
  templateUrl: './property-grid.html',
  styleUrl: './property-grid.scss',
})
export class PropertyGrid {
  @Input() properties: PropertyDefinitionViewModel[] = [];
  @Input() propertyTypes: PropertyTypeInfo[] = [];
  @Input() allowAdd = false;

  @ViewChildren('nameInput') nameInputs!: QueryList<ElementRef<HTMLInputElement>>;

  readonly requirements = ['OPTIONAL', 'REQUIRED', 'DEPRECATED'] as const;

  addProperty(): void {
    this.properties.push(PropertyDefinitionViewModel.create(this.propertyTypes));
    setTimeout(() => {
      const inputs = this.nameInputs.toArray();
      if (inputs.length > 0) inputs[inputs.length - 1].nativeElement.focus();
    });
  }

  deleteProperty(prop: PropertyDefinitionViewModel): void {
    if (prop.isNew) {
      const idx = this.properties.indexOf(prop);
      if (idx !== -1) this.properties.splice(idx, 1);
    } else {
      prop.isDeleted = true;
    }
  }

  revertProperty(prop: PropertyDefinitionViewModel): void {
    prop.revert();
  }

  restoreProperty(prop: PropertyDefinitionViewModel): void {
    prop.isDeleted = false;
  }

  onTypeChange(prop: PropertyDefinitionViewModel): void {
    prop.updateType(prop.type, this.propertyTypes);
  }

  trackProp(_: number, prop: PropertyDefinitionViewModel): string | null {
    return prop.id;
  }
}
