// What a pipeline's conditions can read, from the raw admin schema snapshot (useSchemaSnapshot): an
// item type's properties, links and state machines, by id. Shared by the condition text (what a
// subject says) and the subject picker (what a subject can be).

export function findItemType(schema, itemTypeId) {
  return schema?.items.find((it) => it.id === itemTypeId) ?? null
}

// An item type's properties, its groups' included.
export function propertiesOf(schema, itemTypeId) {
  const properties = []
  function walk(container) {
    properties.push(...(container.properties ?? []))
    for (const g of container.groups ?? []) walk(g)
  }
  const item = findItemType(schema, itemTypeId)
  if (item) walk(item)
  return properties
}

// An item type's links, one per perspective from its side: { id, name, targetItemTypeId, targetName,
// toMany }. A perspective names its target type by name; targetItemTypeId is null for one whose target
// isn't an item type (a trait).
export function linksOf(schema, itemTypeId) {
  const item = findItemType(schema, itemTypeId)
  return Object.entries(item?.links ?? {}).flatMap(([name, perspectives]) =>
    perspectives.map((p) => {
      const target = p.targets?.[0]
      const targetItem = target?.kind === 'item' ? schema.items.find((it) => it.name === target.name) : null
      return {
        id: p.id,
        name,
        targetItemTypeId: targetItem?.id ?? null,
        targetName: target?.name ?? null,
        toMany: p.maxCardinality == null || p.maxCardinality > 1,
      }
    }),
  )
}

export function stateMachinesOf(schema, itemTypeId) {
  return findItemType(schema, itemTypeId)?.stateMachines ?? []
}
