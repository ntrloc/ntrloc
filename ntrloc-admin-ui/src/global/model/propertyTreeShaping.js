// Where something inherited comes from -- { kind: 'trait' | 'supertype', id, name } -- found by name,
// which is all the server's definedIn carries.
export function toSource(adminSchema, definedIn) {
  const kind = definedIn.entityType
  const candidates = kind === 'trait' ? adminSchema.traits : adminSchema.items
  return { kind, id: candidates.find((c) => c.name === definedIn.entityName)?.id ?? null, name: definedIn.entityName }
}

function toPropertyNode(property, adminSchema) {
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
    source: property.definedIn ? toSource(adminSchema, property.definedIn) : null,
  }
}

// Recursive -- a group's own properties/groups nest to any depth, mirroring
// AdminPropertyGroupView's own shape on the backend. inherited marks a node that came from a trait
// or a supertype (the server tags it definedIn) rather than being declared here, and source says
// which one. A trait's members sit alongside the item type's own, under their own names.
function toGroupNode(group, adminSchema) {
  return {
    kind: 'group',
    id: group.id,
    name: group.name,
    description: group.description,
    inherited: Boolean(group.definedIn),
    source: group.definedIn ? toSource(adminSchema, group.definedIn) : null,
    properties: group.properties.map((p) => toPropertyNode(p, adminSchema)),
    groups: group.groups.map((g) => toGroupNode(g, adminSchema)),
  }
}

// Shared by ItemTypeModel, TraitModel, and LinkModel alike -- an item type, trait, and link all
// carry the same own properties/groups shape on the wire (AdminPropertyDefinitionView /
// AdminPropertyGroupView), so this cares only about that shape, not which kind of container it came
// from. Unflattened -- which rows are visible (a group can be collapsed) and in what order is
// PropertyTreeViewModel's concern, not this.
export function toPropertyTree(container, adminSchema) {
  return {
    properties: container.properties.map((p) => toPropertyNode(p, adminSchema)),
    groups: container.groups.map((g) => toGroupNode(g, adminSchema)),
  }
}
