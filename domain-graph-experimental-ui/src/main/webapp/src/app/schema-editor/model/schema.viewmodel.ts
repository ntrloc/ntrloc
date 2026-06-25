import {
  AdminItemDefinition,
  AdminItemLinkPerspective,
  AdminLink,
  AdminPropertyDefinition,
  PropertyCardinality,
  PropertyUsage,
  PropertyType,
  PropertyTypeInfo,
} from './schema.model';

export class PropertyDefinitionViewModel {
  id: string | null;
  name: string;
  description: string | null;
  type: PropertyType;
  cardinality: PropertyCardinality;
  usage: PropertyUsage;
  validCardinalities: PropertyCardinality[];

  readonly originalName: string;
  readonly originalDescription: string | null;
  readonly originalType: PropertyType;
  readonly originalCardinality: PropertyCardinality;
  readonly originalUsage: PropertyUsage;
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
    this.isNew = args.isNew;
    this.isDeleted = false;
  }

  get isDirty(): boolean {
    return this.isNew
      || this.isDeleted
      || this.name !== this.originalName
      || (this.description ?? '') !== (this.originalDescription ?? '')
      || this.type !== this.originalType
      || this.cardinality !== this.originalCardinality
      || this.usage !== this.originalUsage;
  }

  revert(): void {
    this.name = this.originalName;
    this.description = this.originalDescription;
    this.type = this.originalType;
    this.cardinality = this.originalCardinality;
    this.usage = this.originalUsage;
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
  readonly itemType: string;
  readonly description: string | null;
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
    itemType: string;
    description: string | null;
    minCardinality: number;
    maxCardinality: number | null;
    link: LinkViewModel;
  }) {
    this.id = args.id;
    this.linkId = args.linkId;
    this.name = args.name;
    this.itemType = args.itemType;
    this.description = args.description;
    this.minCardinality = args.minCardinality;
    this.originalMinCardinality = args.minCardinality;
    this.maxCardinality = args.maxCardinality;
    this.originalMaxCardinality = args.maxCardinality;
    this.link = args.link;
    this.isDeleted = false;
  }

  get isDirty(): boolean {
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
      itemType: p.itemType,
      description: p.description,
      minCardinality: p.minCardinality,
      maxCardinality: p.maxCardinality,
      link,
    });
  }
}

export class ItemDefinitionViewModel {
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

  static fromAdmin(item: AdminItemDefinition, propertyTypes: PropertyTypeInfo[], linkViewModels: Map<string, LinkViewModel>): ItemDefinitionViewModel {
    const links: Record<string, ItemLinkPerspectiveViewModel[]> = {};
    for (const [name, perspectives] of Object.entries(item.links ?? {})) {
      links[name] = perspectives.map(p => ItemLinkPerspectiveViewModel.fromAdmin(p, linkViewModels.get(p.linkId)!, name));
    }
    return new ItemDefinitionViewModel({
      id: item.id,
      name: item.name,
      description: item.description,
      properties: (item.properties ?? []).map(p => PropertyDefinitionViewModel.fromAdmin(p, propertyTypes)),
      links,
      isNew: false,
    });
  }

  static create(): ItemDefinitionViewModel {
    return new ItemDefinitionViewModel({
      id: null,
      name: '',
      description: null,
      properties: [],
      links: {},
      isNew: true,
    });
  }
}

