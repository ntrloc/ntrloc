// Per-entity dirty-tracking wrapper classes, direct port of the Angular reference's
// model/schema.viewmodel.ts. Each wraps a DTO (or starts blank for a not-yet-saved entity) and
// remembers its own "original" values so schema-view-model.js's collectMutations() can diff
// against them without ever diffing against the server.

class PropertyDefinitionViewModel {
  constructor(args) {
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
    this.isNew = args.isNew;
    this.isDeleted = false;
  }

  get isReadonly() {
    return this.definedIn != null;
  }

  get isDirty() {
    if (this.isReadonly) return false;
    return this.isNew
      || this.isDeleted
      || this.name !== this.originalName
      || (this.description ?? '') !== (this.originalDescription ?? '')
      || this.type !== this.originalType
      || this.cardinality !== this.originalCardinality
      || this.usage !== this.originalUsage;
  }

  revert() {
    this.name = this.originalName;
    this.description = this.originalDescription;
    this.type = this.originalType;
    this.cardinality = this.originalCardinality;
    this.usage = this.originalUsage;
    this.isDeleted = false;
  }

  updateType(newType, propertyTypes) {
    this.type = newType;
    const typeInfo = propertyTypes.find((t) => t.type === newType);
    this.validCardinalities = typeInfo?.validCardinalities ?? [this.cardinality];
    if (!this.validCardinalities.includes(this.cardinality)) {
      this.cardinality = this.validCardinalities[0];
    }
  }

  static fromAdmin(p, propertyTypes) {
    const typeInfo = propertyTypes.find((t) => t.type === p.type);
    return new PropertyDefinitionViewModel({
      id: p.id,
      name: p.name,
      description: p.description,
      type: p.type,
      cardinality: p.cardinality,
      usage: p.usage,
      validCardinalities: typeInfo?.validCardinalities ?? [p.cardinality],
      definedIn: p.definedIn ?? null,
      controlledListId: p.controlledListId ?? null,
      isNew: false,
    });
  }

  static create(propertyTypes) {
    const defaultType = propertyTypes[0];
    return new PropertyDefinitionViewModel({
      id: null,
      name: '',
      description: null,
      type: defaultType?.type ?? 'STRING',
      cardinality: defaultType?.validCardinalities[0] ?? 'SINGLE',
      usage: 'OPTIONAL',
      validCardinalities: defaultType?.validCardinalities ?? ['SINGLE'],
      definedIn: null,
      controlledListId: null,
      isNew: true,
    });
  }
}

class LinkViewModel {
  constructor(args) {
    this.id = args.id;
    this.properties = args.properties;
  }

  get isDirty() {
    return this.properties.some((p) => p.isDirty);
  }

  static fromAdmin(link, propertyTypes) {
    return new LinkViewModel({
      id: link.id,
      properties: (link.properties ?? []).map((p) => PropertyDefinitionViewModel.fromAdmin(p, propertyTypes)),
    });
  }
}

class ItemLinkPerspectiveViewModel {
  constructor(args) {
    this.id = args.id;
    this.linkId = args.linkId;
    this.name = args.name;
    this.originalName = args.name;
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

  get isReadonly() {
    return this.definedIn != null;
  }

  get isDirty() {
    if (this.isReadonly) return false;
    return this.isDeleted
      || this.name !== this.originalName
      || this.minCardinality !== this.originalMinCardinality
      || this.maxCardinality !== this.originalMaxCardinality
      || this.link.isDirty;
  }

  revert() {
    this.name = this.originalName;
    this.minCardinality = this.originalMinCardinality;
    this.maxCardinality = this.originalMaxCardinality;
    this.isDeleted = false;
  }

  static fromAdmin(p, link, name) {
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

class TraitAssignmentViewModel {
  constructor(ref, isNew = false) {
    this.id = ref.id;
    this.name = ref.name;
    this.isNew = isNew;
    this.isRemoved = false;
  }

  get isDirty() {
    return this.isNew || this.isRemoved;
  }
}

class ItemDefinitionViewModel {
  constructor(args) {
    this.id = args.id;
    this.name = args.name;
    this.originalName = args.name;
    this.description = args.description;
    this.originalDescription = args.description;
    this.properties = args.properties;
    this.links = args.links;
    this.traitAssignments = args.traitAssignments;
    this.isNew = args.isNew;
  }

  get isDirty() {
    return this.isNew
      || this.name !== this.originalName
      || (this.description ?? '') !== (this.originalDescription ?? '')
      || this.properties.some((p) => p.isDirty)
      || this.traitAssignments.some((t) => t.isDirty)
      || Object.values(this.links).some((perspectives) => perspectives.some((p) => p.isDirty));
  }

  addTrait(ref) {
    if (!this.traitAssignments.some((t) => t.id === ref.id)) {
      this.traitAssignments = [...this.traitAssignments, new TraitAssignmentViewModel(ref, true)];
    }
  }

  removeTrait(assignment) {
    if (assignment.isNew) {
      this.traitAssignments = this.traitAssignments.filter((t) => t !== assignment);
    } else {
      assignment.isRemoved = true;
    }
  }

  static fromAdmin(item, propertyTypes, linkViewModelsById) {
    const links = {};
    for (const [name, perspectives] of Object.entries(item.links ?? {})) {
      links[name] = perspectives.map((p) => ItemLinkPerspectiveViewModel.fromAdmin(p, linkViewModelsById.get(p.linkId), name));
    }
    return new ItemDefinitionViewModel({
      id: item.id,
      name: item.name,
      description: item.description,
      properties: (item.properties ?? []).map((p) => PropertyDefinitionViewModel.fromAdmin(p, propertyTypes)),
      links,
      traitAssignments: (item.traits ?? []).map((t) => new TraitAssignmentViewModel(t)),
      isNew: false,
    });
  }

  static create() {
    return new ItemDefinitionViewModel({
      id: null,
      name: '',
      description: null,
      properties: [],
      links: {},
      traitAssignments: [],
      isNew: true,
    });
  }
}

class TraitDefinitionViewModel {
  constructor(args) {
    this.id = args.id;
    this.name = args.name;
    this.originalName = args.name;
    this.description = args.description;
    this.originalDescription = args.description;
    this.properties = args.properties;
    this.links = args.links;
    this.isNew = args.isNew;
  }

  get isDirty() {
    return this.isNew
      || this.name !== this.originalName
      || (this.description ?? '') !== (this.originalDescription ?? '')
      || this.properties.some((p) => p.isDirty)
      || Object.values(this.links).some((perspectives) => perspectives.some((p) => p.isDirty));
  }

  static fromAdmin(trait, propertyTypes, linkViewModelsById) {
    const links = {};
    for (const [name, perspectives] of Object.entries(trait.links ?? {})) {
      links[name] = perspectives.map((p) => ItemLinkPerspectiveViewModel.fromAdmin(p, linkViewModelsById.get(p.linkId), name));
    }
    return new TraitDefinitionViewModel({
      id: trait.id,
      name: trait.name,
      description: trait.description,
      properties: (trait.properties ?? []).map((p) => PropertyDefinitionViewModel.fromAdmin(p, propertyTypes)),
      links,
      isNew: false,
    });
  }

  static create() {
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

// A not-yet-saved link, tracked separately from items/traits (schemaViewModel.pendingNewLinks,
// not item.links) since a link spans two item types and doesn't belong to either one alone --
// unlike every other entity here, it has no fromAdmin() because it never represents a saved
// entity; collectMutations() emits it as a single CREATE_LINK and it's discarded (not converted
// into a real ItemLinkPerspectiveViewModel) once the save succeeds and the schema reloads from
// the server.
class PendingLinkViewModel {
  constructor(firstItemId) {
    this.firstItemId = firstItemId;
    this.firstPerspectiveName = '';
    this.firstMinCardinality = 0;
    this.firstMaxCardinality = null;
    this.secondItemId = null;
    this.secondPerspectiveName = '';
    this.secondMinCardinality = 0;
    this.secondMaxCardinality = null;
    this.properties = [];
  }

  // Both sides picked and distinct (the backend has no friendly error for a self-referential
  // link -- it hits an unhandled UNIQUE constraint violation -- so this is enforced by construction
  // in the UI: the target picker never offers the current item, but this is the last line of
  // defense collectMutations() checks before ever building the mutation), and both perspective
  // names present (NOT NULL columns server-side, same unfriendly-500 concern).
  get isValid() {
    return this.firstItemId != null
      && this.secondItemId != null
      && this.firstItemId !== this.secondItemId
      && this.firstPerspectiveName.trim() !== ''
      && this.secondPerspectiveName.trim() !== '';
  }

  get isDirty() {
    return true;
  }

  static create(firstItemId) {
    return new PendingLinkViewModel(firstItemId);
  }
}
