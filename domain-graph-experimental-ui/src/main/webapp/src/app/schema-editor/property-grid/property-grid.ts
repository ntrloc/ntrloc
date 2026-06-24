import { Component, Input, OnChanges } from '@angular/core';
import { NgFor, NgIf } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { MatButton, MatIconButton } from '@angular/material/button';
import { MatIcon } from '@angular/material/icon';
import { PropertyDefinition, PropertyTypeInfo } from '../model/schema.model';

interface EditableProperty {
  id: string | null;
  name: string;
  originalName: string;
  description: string | null;
  originalDescription: string | null;
  type: string;
  cardinality: string;
  originalCardinality: string;
  validCardinalities: string[];
  isNew: boolean;
  isDeleted: boolean;
  isDirty: boolean;
}

@Component({
  selector: 'app-property-grid',
  imports: [NgIf, NgFor, FormsModule, MatButton, MatIconButton, MatIcon],
  templateUrl: './property-grid.html',
  styleUrl: './property-grid.scss',
})
export class PropertyGrid implements OnChanges {
  @Input() properties: PropertyDefinition[] = [];
  @Input() propertyTypes: PropertyTypeInfo[] = [];
  @Input() allowAdd = false;

  editableProperties: EditableProperty[] = [];

  ngOnChanges(): void {
    this.editableProperties = this.properties.map(p => this.toEditable(p));
  }

  addProperty(): void {
    const defaultTypeInfo = this.propertyTypes[0];
    const defaultType = defaultTypeInfo?.type ?? 'STRING';
    const validCardinalities = defaultTypeInfo?.validCardinalities.map(c => String(c)) ?? ['SINGLE'];
    this.editableProperties.push({
      id: null,
      name: '',
      originalName: '',
      description: null,
      originalDescription: null,
      type: defaultType,
      cardinality: validCardinalities[0],
      originalCardinality: '',
      validCardinalities,
      isNew: true,
      isDeleted: false,
      isDirty: true,
    });
  }

  deleteProperty(prop: EditableProperty): void {
    if (prop.isNew) {
      this.editableProperties = this.editableProperties.filter(p => p !== prop);
    } else {
      prop.isDeleted = true;
      prop.isDirty = true;
    }
  }

  revertProperty(prop: EditableProperty): void {
    prop.name = prop.originalName;
    prop.description = prop.originalDescription;
    prop.cardinality = prop.originalCardinality;
    const typeInfo = this.propertyTypes.find(t => t.type === prop.type);
    prop.validCardinalities = typeInfo?.validCardinalities.map(c => String(c)) ?? [prop.cardinality];
    prop.isDeleted = false;
    prop.isDirty = false;
  }

  restoreProperty(prop: EditableProperty): void {
    prop.isDeleted = false;
    prop.isDirty = prop.name !== prop.originalName
      || this.coerce(prop.description) !== this.coerce(prop.originalDescription)
      || prop.cardinality !== prop.originalCardinality;
  }

  onTypeChange(prop: EditableProperty): void {
    const typeInfo = this.propertyTypes.find(t => t.type === prop.type);
    prop.validCardinalities = typeInfo?.validCardinalities.map(c => String(c)) ?? [prop.cardinality];
    if (!prop.validCardinalities.includes(prop.cardinality)) {
      prop.cardinality = prop.validCardinalities[0];
    }
  }

  onPropertyChange(prop: EditableProperty): void {
    if (prop.isNew) return;
    prop.isDirty = prop.name !== prop.originalName
      || this.coerce(prop.description) !== this.coerce(prop.originalDescription)
      || prop.cardinality !== prop.originalCardinality;
  }

  private toEditable(p: PropertyDefinition): EditableProperty {
    const typeInfo = this.propertyTypes.find(t => t.type === p.type);
    return {
      id: p.id,
      name: p.name,
      originalName: p.name,
      description: p.description,
      originalDescription: p.description,
      type: p.type,
      cardinality: p.cardinality,
      originalCardinality: p.cardinality,
      validCardinalities: typeInfo?.validCardinalities.map(c => String(c)) ?? [p.cardinality],
      isNew: false,
      isDeleted: false,
      isDirty: false,
    };
  }

  private coerce(value: string | null | undefined): string {
    return value ?? '';
  }
}
