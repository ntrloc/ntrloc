import { Component, ElementRef, Input, OnChanges, QueryList, ViewChildren } from '@angular/core';
import { NgFor, NgIf } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { MatButton, MatIconButton } from '@angular/material/button';
import { MatIcon } from '@angular/material/icon';
import { AdminPropertyDefinition, PropertyRequirement, PropertyTypeInfo } from '../model/schema.model';

interface EditableProperty {
  id: string | null;
  name: string;
  originalName: string;
  description: string | null;
  originalDescription: string | null;
  type: string;
  cardinality: string;
  originalCardinality: string;
  requirement: PropertyRequirement;
  originalRequirement: PropertyRequirement;
  validCardinalities: string[];
  isNew: boolean;
  isDeleted: boolean;
  isDirty: boolean;
}

const REQUIREMENTS: PropertyRequirement[] = ['OPTIONAL', 'REQUIRED', 'DEPRECATED'];

@Component({
  selector: 'app-property-grid',
  imports: [NgIf, NgFor, FormsModule, MatButton, MatIconButton, MatIcon],
  templateUrl: './property-grid.html',
  styleUrl: './property-grid.scss',
})
export class PropertyGrid implements OnChanges {
  @Input() properties: AdminPropertyDefinition[] = [];
  @Input() propertyTypes: PropertyTypeInfo[] = [];
  @Input() allowAdd = false;

  @ViewChildren('nameInput') nameInputs!: QueryList<ElementRef<HTMLInputElement>>;

  editableProperties: EditableProperty[] = [];
  readonly requirements = REQUIREMENTS;

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
      requirement: 'OPTIONAL',
      originalRequirement: 'OPTIONAL',
      validCardinalities,
      isNew: true,
      isDeleted: false,
      isDirty: true,
    });
    setTimeout(() => {
      const inputs = this.nameInputs.toArray();
      if (inputs.length > 0) inputs[inputs.length - 1].nativeElement.focus();
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
    prop.requirement = prop.originalRequirement;
    const typeInfo = this.propertyTypes.find(t => t.type === prop.type);
    prop.validCardinalities = typeInfo?.validCardinalities.map(c => String(c)) ?? [prop.cardinality];
    prop.isDeleted = false;
    prop.isDirty = false;
  }

  restoreProperty(prop: EditableProperty): void {
    prop.isDeleted = false;
    prop.isDirty = prop.name !== prop.originalName
      || this.coerce(prop.description) !== this.coerce(prop.originalDescription)
      || prop.cardinality !== prop.originalCardinality
      || prop.requirement !== prop.originalRequirement;
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
      || prop.cardinality !== prop.originalCardinality
      || prop.requirement !== prop.originalRequirement;
  }

  private toEditable(p: AdminPropertyDefinition): EditableProperty {
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
      requirement: p.requirement,
      originalRequirement: p.requirement,
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
