import {
  AdminItemDefinition,
  AdminItemLinkPerspective,
  AdminLink,
  AdminPropertyDefinition,
  AdminTraitDefinition,
  DefinedIn,
  PropertyCardinality,
  PropertyGroup,
  PropertyUsage,
  PropertyType,
  PropertyTypeInfo,
  TargetEntity,
  TraitRef,
} from './schema.model';

export class PropertyGroupViewModel {
  id: string | null;
  name: string;
  readonly originalName: string;
  readonly isNew: boolean;
  isDeleted: boolean;

  constructor(group: PropertyGroup | null, isNew = false) {
    this.id = group?.id ?? null;
    this.name = group?.name ?? '';
    this.originalName = this.name;
    this.isNew = isNew;
    this.isDeleted = false;
  }

  get isDirty(): boolean {
    return this.isNew || this.isDeleted || this.name !== this.originalName;
  }

  revert(): void {
    this.name = this.originalName;
    this.isDeleted = false;
  }

  static create(): PropertyGroupViewModel {
    return new PropertyGroupViewModel(null, true);
  }
}

export class PropertyDefinitionViewModel {
  id: string | null;
  name: string;
  description: string | null;
  type: PropertyType;
  cardinality: PropertyCardinality;
  usage: PropertyUsage;
  validCardinalities: PropertyCardinality[];
  readonly definedIn: DefinedIn | null;
  readonly controlledListId: string | null;
  groupId: string | null;

  readonly originalName: string;
  readonly originalDescription: string | null;
  readonly originalType: PropertyType;
  readonly originalCardinality: PropertyCardinality;
  readonly originalUsage: PropertyUsage;
  readonly originalGroupId: string | null;
  readonly isNew: boolean;
  isDeleted: boolean;

  private constructor(args: {
    id: string | null;
    name: string;
    description: string | null;
    type: PropertyType;
    cardinality: PropertyCardinality;
    usage: PropertyUsage;
    validCardinalities: PropertyCardinality[];
    definedIn: DefinedIn | null;
    controlledListId: string | null;
    groupId: string | null;
    isNew: boolean;
  }) {
    this.id = args.id;
    this.name = args.name;
    this.originalName = args.name;
    this.description = args.description;
    this.originalDescription = args.description;
    this.type = args.type;
    this.originalType = args.type;
    this.cardinality = args.cardinality;
    this.originalCardinality = args.cardinality;
    this.usage = args.usage;
    this.originalUsage = args.usage;
    this.validCardinalities = args.validCardinalities;
    this.definedIn = args.definedIn;
    this.controlledListId = args.controlledListId;
    this.groupId = args.groupId;
    this.originalGroupId = args.groupId;
    this.isNew = args.isNew;
    this.isDeleted = false;
  }

  get isReadonly(): boolean {
    return this.definedIn != null;
  }

  get isDirty(): boolean {
    if (this.isReadonly) return this.groupId !== this.originalGroupId;
    return this.isNew
      || this.isDeleted
      || this.name !== this.originalName
      || (this.description ?? '') !== (this.originalDescription ?? '')
      || this.type !== this.originalType
      || this.cardinality !== this.originalCardinality
      || this.usage !== this.originalUsage
      || this.groupId !== this.originalGroupId;
  }

  revert(): void {
    this.name = this.originalName;
    this.description = this.originalDescription;
    this.type = this.originalType;
    this.cardinality = this.originalCardinality;
    this.usage = this.originalUsage;
    this.groupId = this.originalGroupId;
    this.isDeleted = false;
  }

  updateType(newType: PropertyType, propertyTypes: PropertyTypeInfo[]): void {
    this.type = newType;
    const typeInfo = propertyTypes.find(t => t.type === newType);
    this.validCardinalities = (typeInfo?.validCardinalities ?? [this.cardinality]) as PropertyCardinality[];
    if (!this.validCardinalities.includes(this.cardinality)) {
      this.cardinality = this.validCardinalities[0];
    }
  }

  static fromAdmin(p: AdminPropertyDefinition, propertyTypes: PropertyTypeInfo[]): PropertyDefinitionViewModel {
    const typeInfo = propertyTypes.find(t => t.type === p.type);
    return new PropertyDefinitionViewModel({
      id: p.id,
      name: p.name,
      description: p.description,
      type: p.type,
      cardinality: p.cardinality,
      usage: p.usage,
      validCardinalities: (typeInfo?.validCardinalities ?? [p.cardinality]) as PropertyCardinality[],
      definedIn: p.definedIn ?? null,
      controlledListId: p.controlledListId ?? null,
      groupId: p.groupId ?? null,
      isNew: false,
    });
  }

  static create(propertyTypes: PropertyTypeInfo[]): PropertyDefinitionViewModel {
    const defaultType = propertyTypes[0];
    return new PropertyDefinitionViewModel({
      id: null,
      name: '',
      description: null,
      type: defaultType?.type ?? 'STRING',
      cardinality: (defaultType?.validCardinalities[0] ?? 'SINGLE') as PropertyCardinality,
      usage: 'OPTIONAL',
      validCardinalities: (defaultType?.validCardinalities ?? ['SINGLE']) as PropertyCardinality[],
      definedIn: null,
      controlledListId: null,
      groupId: null,
      isNew: true,
    });
  }
}

export class LinkViewModel {
  readonly id: string;
  readonly properties: PropertyDefinitionViewModel[];

  private constructor(args: { id: string; properties: PropertyDefinitionViewModel[] }) {
    this.id = args.id;
    this.properties = args.properties;
  }

  get isDirty(): boolean { return this.properties.some(p => p.isDirty); }

  static fromAdmin(link: AdminLink, propertyTypes: PropertyTypeInfo[]): LinkViewModel {
    return new LinkViewModel({
      id: link.id,
      properties: (link.properties ?? []).map(p => PropertyDefinitionViewModel.fromAdmin(p, propertyTypes)),
    });
  }
}

export class ItemLinkPerspectiveViewModel {
  readonly id: string;
  readonly linkId: string;
  readonly name: string;
  readonly targets: TargetEntity[];
  readonly description: string | null;
  readonly definedIn: DefinedIn | null;
  readonly link: LinkViewModel;

  minCardinality: number;
  maxCardinality: number | null;

  readonly originalMinCardinality: number;
  readonly originalMaxCardinality: number | null;

  isDeleted: boolean;

  private constructor(args: {
    id: string;
    linkId: string;
    name: string;
    targets: TargetEntity[];
    description: string | null;
    definedIn: DefinedIn | null;
    minCardinality: number;
    maxCardinality: number | null;
    link: LinkViewModel;
  }) {
    this.id = args.id;
    this.linkId = args.linkId;
    this.name = args.name;
    this.targets = args.targets;
    this.description = args.description;
    this.definedIn = args.definedIn;
    this.minCardinality = args.minCardinality;
    this.originalMinCardinality = args.minCardinality;
    this.maxCardinality = args.maxCardinality;
    this.originalMaxCardinality = args.maxCardinality;
    this.link = args.link;
    this.isDeleted = false;
  }

  get isReadonly(): boolean {
    return this.definedIn != null;
  }

  get isDirty(): boolean {
    if (this.isReadonly) return false;
    return this.isDeleted
      || this.minCardinality !== this.originalMinCardinality
      || this.maxCardinality !== this.originalMaxCardinality
      || this.link.isDirty;
  }

  revert(): void {
    this.minCardinality = this.originalMinCardinality;
    this.maxCardinality = this.originalMaxCardinality;
    this.isDeleted = false;
  }

  static fromAdmin(p: AdminItemLinkPerspective, link: LinkViewModel, name: string): ItemLinkPerspectiveViewModel {
    return new ItemLinkPerspectiveViewModel({
      id: p.id,
      linkId: p.linkId,
      name,
      targets: p.targets ?? [],
      description: p.description,
      definedIn: p.definedIn ?? null,
      minCardinality: p.minCardinality,
      maxCardinality: p.maxCardinality,
      link,
    });
  }
}

export class TraitAssignmentViewModel {
  readonly id: string;
  readonly name: string;
  readonly isNew: boolean;
  isRemoved: boolean = false;

  constructor(ref: TraitRef, isNew = false) {
    this.id = ref.id;
    this.name = ref.name;
    this.isNew = isNew;
  }

  get isDirty(): boolean { return this.isNew || this.isRemoved; }
}

export class ItemDefinitionViewModel {
  id: string | null;
  readonly entityId: string | null;
  name: string;
  description: string | null;
  properties: PropertyDefinitionViewModel[];
  links: Record<string, ItemLinkPerspectiveViewModel[]>;
  traitAssignments: TraitAssignmentViewModel[];
  groups: PropertyGroupViewModel[];

  readonly originalName: string;
  readonly originalDescription: string | null;
  readonly isNew: boolean;

  private constructor(args: {
    id: string | null;
    entityId: string | null;
    name: string;
    description: string | null;
    properties: PropertyDefinitionViewModel[];
    links: Record<string, ItemLinkPerspectiveViewModel[]>;
    traitAssignments: TraitAssignmentViewModel[];
    groups: PropertyGroupViewModel[];
    isNew: boolean;
  }) {
    this.id = args.id;
    this.entityId = args.entityId;
    this.name = args.name;
    this.originalName = args.name;
    this.description = args.description;
    this.originalDescription = args.description;
    this.properties = args.properties;
    this.links = args.links;
    this.traitAssignments = args.traitAssignments;
    this.groups = args.groups;
    this.isNew = args.isNew;
  }

  get isDirty(): boolean {
    return this.isNew
      || this.name !== this.originalName
      || (this.description ?? '') !== (this.originalDescription ?? '')
      || this.properties.some(p => p.isDirty)
      || this.traitAssignments.some(t => t.isDirty)
      || this.groups.some(g => g.isDirty)
      || Object.values(this.links).some(perspectives => perspectives.some(p => p.isDirty));
  }

  addTrait(ref: TraitRef): void {
    if (!this.traitAssignments.some(t => t.id === ref.id)) {
      this.traitAssignments = [...this.traitAssignments, new TraitAssignmentViewModel(ref, true)];
    }
  }

  removeTrait(assignment: TraitAssignmentViewModel): void {
    if (assignment.isNew) {
      this.traitAssignments = this.traitAssignments.filter(t => t !== assignment);
    } else {
      assignment.isRemoved = true;
    }
  }

  static fromAdmin(item: AdminItemDefinition, propertyTypes: PropertyTypeInfo[], linkViewModels: Map<string, LinkViewModel>): ItemDefinitionViewModel {
    const links: Record<string, ItemLinkPerspectiveViewModel[]> = {};
    for (const [name, perspectives] of Object.entries(item.links ?? {})) {
      links[name] = perspectives.map(p => ItemLinkPerspectiveViewModel.fromAdmin(p, linkViewModels.get(p.linkId)!, name));
    }
    return new ItemDefinitionViewModel({
      id: item.id,
      entityId: item.entityId,
      name: item.name,
      description: item.description,
      properties: (item.properties ?? []).map(p => PropertyDefinitionViewModel.fromAdmin(p, propertyTypes)),
      links,
      traitAssignments: (item.traits ?? []).map(t => new TraitAssignmentViewModel(t)),
      groups: (item.groups ?? []).map(g => new PropertyGroupViewModel(g)),
      isNew: false,
    });
  }

  static create(): ItemDefinitionViewModel {
    return new ItemDefinitionViewModel({
      id: null,
      entityId: null,
      name: '',
      description: null,
      properties: [],
      links: {},
      traitAssignments: [],
      groups: [],
      isNew: true,
    });
  }
}

export class TraitDefinitionViewModel {
  id: string | null;
  name: string;
  description: string | null;
  properties: PropertyDefinitionViewModel[];
  links: Record<string, ItemLinkPerspectiveViewModel[]>;

  readonly originalName: string;
  readonly originalDescription: string | null;
  readonly isNew: boolean;

  private constructor(args: {
    id: string | null;
    name: string;
    description: string | null;
    properties: PropertyDefinitionViewModel[];
    links: Record<string, ItemLinkPerspectiveViewModel[]>;
    isNew: boolean;
  }) {
    this.id = args.id;
    this.name = args.name;
    this.originalName = args.name;
    this.description = args.description;
    this.originalDescription = args.description;
    this.properties = args.properties;
    this.links = args.links;
    this.isNew = args.isNew;
  }

  get isDirty(): boolean {
    return this.isNew
      || this.name !== this.originalName
      || (this.description ?? '') !== (this.originalDescription ?? '')
      || this.properties.some(p => p.isDirty)
      || Object.values(this.links).some(perspectives => perspectives.some(p => p.isDirty));
  }

  static fromAdmin(trait: AdminTraitDefinition, propertyTypes: PropertyTypeInfo[], linkViewModels: Map<string, LinkViewModel>): TraitDefinitionViewModel {
    const links: Record<string, ItemLinkPerspectiveViewModel[]> = {};
    for (const [name, perspectives] of Object.entries(trait.links ?? {})) {
      links[name] = perspectives.map(p => ItemLinkPerspectiveViewModel.fromAdmin(p, linkViewModels.get(p.linkId)!, name));
    }
    return new TraitDefinitionViewModel({
      id: trait.id,
      name: trait.name,
      description: trait.description,
      properties: (trait.properties ?? []).map(p => PropertyDefinitionViewModel.fromAdmin(p, propertyTypes)),
      links,
      isNew: false,
    });
  }

  static create(): TraitDefinitionViewModel {
    return new TraitDefinitionViewModel({
      id: null,
      name: '',
      description: null,
      properties: [],
      links: {},
      isNew: true,
    });
  }
}
