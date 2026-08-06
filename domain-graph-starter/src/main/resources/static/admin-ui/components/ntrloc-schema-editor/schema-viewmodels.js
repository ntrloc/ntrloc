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

// A transition's target state (toStateId) is fixed at creation time -- UpdateTransitionMutation
// has no toStateId field, so the backend has no way to retarget an existing transition. The
// state-machine editor respects this by never offering to change it after creation; retargeting
// means delete-and-recreate, same as the user would have to do themselves.
class TransitionViewModel {
  constructor(args) {
    this.id = args.id;
    this.toStateId = args.toStateId;
    this.toStateName = args.toStateName;
    this.name = args.name;
    this.originalName = args.name;
    this.description = args.description;
    this.originalDescription = args.description;
    this.processId = args.processId;
    this.originalProcessId = args.processId;
    this.guardCondition = args.guardCondition;
    this.originalGuardCondition = args.guardCondition;
    this.isNew = args.isNew;
    this.isDeleted = false;
  }

  get isDirty() {
    return this.isNew
      || this.isDeleted
      || this.name !== this.originalName
      || (this.description ?? '') !== (this.originalDescription ?? '')
      || this.processId !== this.originalProcessId
      || JSON.stringify(this.guardCondition ?? null) !== JSON.stringify(this.originalGuardCondition ?? null);
  }

  static fromAdmin(t) {
    return new TransitionViewModel({
      id: t.id,
      toStateId: t.toStateId,
      toStateName: t.toStateName,
      name: t.name,
      description: t.description,
      processId: t.processId,
      guardCondition: t.guardCondition ?? null,
      isNew: false,
    });
  }

  static create(toStateId, toStateName) {
    return new TransitionViewModel({
      // A real (non-null) id here, unlike every other *ViewModel.create() in this file -- unlike
      // properties/traits/links, transitions are rendered into ntrloc-state-machine-diagram.js,
      // which keys its layout Map by id. Multiple new transitions with id:null would collapse
      // onto the same Map key. isNew is still what collectMutations() actually checks; this id is
      // purely a client-side rendering handle and is never sent in a CREATE_TRANSITION mutation.
      id: crypto.randomUUID(),
      toStateId,
      toStateName,
      name: '',
      description: null,
      processId: null,
      guardCondition: null,
      isNew: true,
    });
  }
}

class StateViewModel {
  constructor(args) {
    this.id = args.id;
    this.name = args.name;
    this.originalName = args.name;
    this.description = args.description;
    this.originalDescription = args.description;
    this.isInitial = args.isInitial;
    this.originalIsInitial = args.isInitial;
    this.entryProcessId = args.entryProcessId;
    this.originalEntryProcessId = args.entryProcessId;
    this.exitProcessId = args.exitProcessId;
    this.originalExitProcessId = args.exitProcessId;
    this.transitions = args.transitions;
    this.isNew = args.isNew;
    this.isDeleted = false;
  }

  get isDirty() {
    return this.isNew
      || this.isDeleted
      || this.name !== this.originalName
      || (this.description ?? '') !== (this.originalDescription ?? '')
      || this.isInitial !== this.originalIsInitial
      || this.entryProcessId !== this.originalEntryProcessId
      || this.exitProcessId !== this.originalExitProcessId
      || this.transitions.some((t) => t.isDirty);
  }

  addTransition(toStateId, toStateName) {
    const vm = TransitionViewModel.create(toStateId, toStateName);
    this.transitions = [...this.transitions, vm];
    return vm;
  }

  removeTransition(transition) {
    if (transition.isNew) {
      this.transitions = this.transitions.filter((t) => t !== transition);
    } else {
      transition.isDeleted = true;
    }
  }

  static fromAdmin(s) {
    return new StateViewModel({
      id: s.id,
      name: s.name,
      description: s.description,
      isInitial: s.isInitial,
      entryProcessId: s.entryProcessId,
      exitProcessId: s.exitProcessId,
      transitions: (s.transitions ?? []).map((t) => TransitionViewModel.fromAdmin(t)),
      isNew: false,
    });
  }

  static create() {
    return new StateViewModel({
      // See TransitionViewModel.create()'s comment -- same reasoning: the diagram keys its layout
      // by id, so multiple new states need distinguishable ids even before any is saved.
      id: crypto.randomUUID(),
      name: '',
      description: null,
      isInitial: false,
      entryProcessId: null,
      exitProcessId: null,
      transitions: [],
      isNew: true,
    });
  }
}

class StateMachineViewModel {
  constructor(args) {
    this.id = args.id;
    this.name = args.name;
    this.originalName = args.name;
    this.description = args.description;
    this.originalDescription = args.description;
    this.states = args.states;
    this.isNew = args.isNew;
    this.isDeleted = false;
  }

  get isDirty() {
    return this.isNew
      || this.isDeleted
      || this.name !== this.originalName
      || (this.description ?? '') !== (this.originalDescription ?? '')
      || this.states.some((s) => s.isDirty);
  }

  // Mirrors StateViewModel's own "save this first" guard, one level down -- a not-yet-saved state
  // machine has no real id for CREATE_STATE's stateMachineId to point at yet.
  addState() {
    const vm = StateViewModel.create();
    this.states = [...this.states, vm];
    return vm;
  }

  removeState(state) {
    if (state.isNew) {
      this.states = this.states.filter((s) => s !== state);
    } else {
      state.isDeleted = true;
    }
  }

  static fromAdmin(m) {
    return new StateMachineViewModel({
      id: m.id,
      name: m.name,
      description: m.description,
      states: (m.states ?? []).map((s) => StateViewModel.fromAdmin(s)),
      isNew: false,
    });
  }

  static create() {
    return new StateMachineViewModel({
      // Same reasoning as StateViewModel.create()/TransitionViewModel.create() -- a real client-side
      // id purely for list-keying (e.g. the state-machine-editor's tab row), never sent as-is in a
      // CREATE_STATE_MACHINE mutation.
      id: crypto.randomUUID(),
      name: '',
      description: null,
      states: [],
      isNew: true,
    });
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
    this.stateMachines = args.stateMachines;
    this.isNew = args.isNew;
    this.isDeleted = false;
  }

  get isDirty() {
    return this.isNew
      || this.isDeleted
      || this.name !== this.originalName
      || (this.description ?? '') !== (this.originalDescription ?? '')
      || this.properties.some((p) => p.isDirty)
      || this.traitAssignments.some((t) => t.isDirty)
      || Object.values(this.links).some((perspectives) => perspectives.some((p) => p.isDirty))
      || this.stateMachines.some((m) => m.isDirty);
  }

  // Mirrors newLink()'s own "save this first" guard -- a not-yet-saved item type has no real id
  // for a state machine to point back to via itemDefinitionId, so state machines can't be added
  // until it's saved. Callers are expected to check !this.isNew before offering this (see
  // ntrloc-item-detail.js's States panel, matching its existing Links-panel affordance).
  addStateMachine() {
    const vm = StateMachineViewModel.create();
    this.stateMachines = [...this.stateMachines, vm];
    return vm;
  }

  removeStateMachine(machine) {
    if (machine.isNew) {
      this.stateMachines = this.stateMachines.filter((m) => m !== machine);
    } else {
      machine.isDeleted = true;
    }
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
      stateMachines: (item.stateMachines ?? []).map((m) => StateMachineViewModel.fromAdmin(m)),
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
      stateMachines: [],
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
    this.isDeleted = false;
  }

  get isDirty() {
    return this.isNew
      || this.isDeleted
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
