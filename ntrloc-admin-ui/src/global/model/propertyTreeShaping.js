function toPropertyNode(property) {
  return {
    kind: 'property',
    id: property.id,
    name: property.name,
    description: property.description,
    type: property.type,
    cardinality: property.cardinality,
    usage: property.usage,
    controlledListId: property.controlledListId ?? null,
    facetable: property.facetable,
    inherited: Boolean(property.definedIn),
  }
}

// Recursive -- a group's own properties/groups nest to any depth, mirroring
// AdminPropertyGroupView's own shape on the backend. traitNamespace marks a synthetic group (not a
// real one an item type declared) standing in for everything a trait it implements contributes --
// its id/name are literally the trait's own, which is also all a consumer needs to jump to that
// trait (see SchemaContentResolver.ownAndTraitContents on the backend). inherited marks a node that
// came from a trait or a supertype (the server tags it definedIn) rather than being declared here.
function toGroupNode(group) {
  return {
    kind: 'group',
    id: group.id,
    name: group.name,
    description: group.description,
    traitNamespace: group.traitNamespace,
    inherited: Boolean(group.definedIn),
    properties: group.properties.map(toPropertyNode),
    groups: group.groups.map(toGroupNode),
  }
}

// Shared by ItemTypeModel, TraitModel, and LinkModel alike -- an item type, trait, and link all
// carry the same own properties/groups shape on the wire (AdminPropertyDefinitionView /
// AdminPropertyGroupView), so this cares only about that shape, not which kind of container it came
// from. Unflattened -- which rows are visible (a group can be collapsed) and in what order is
// PropertyTreeViewModel's concern, not this.
export function toPropertyTree(container) {
  return {
    properties: container.properties.map(toPropertyNode),
    groups: container.groups.map(toGroupNode),
  }
}
