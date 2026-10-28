import { useSchemaSnapshot } from './SchemaCache'
import { toPropertyTree } from './propertyTreeShaping'

// Every link is really two perspectives (one on each side) sharing a linkId -- this item type's own
// side is right there in its own links map, but the *reverse* perspective's own name/cardinality
// only exists on the target item type's own links map. Found by scanning it for whichever entry
// shares this perspective's linkId but isn't this exact perspective (id differs) -- the id check is
// what keeps a self-link (an item type linking to its own kind) from matching itself as its own
// reverse. Only the first target is resolved for now -- a link naming more than one possible target
// type is a real (if rarer) case this doesn't yet have a diagram for.
// Where an inherited link comes from -- { kind: 'trait' | 'supertype', id, name } -- found by name,
// which is all the server's definedIn carries.
function toSource(adminSchema, definedIn) {
  const kind = definedIn.entityType
  const candidates = kind === 'trait' ? adminSchema.traits : adminSchema.items
  return { kind, id: candidates.find((c) => c.name === definedIn.entityName)?.id ?? null, name: definedIn.entityName }
}

function toItemTypeLinkDetails(adminSchema, itemTypeId) {
  const item = adminSchema.items.find((it) => it.id === itemTypeId)
  if (!item?.links) return []

  return Object.entries(item.links).flatMap(([name, perspectives]) =>
    perspectives.map((perspective) => {
      const target = perspective.targets[0] ?? null
      // The far end can be a trait as well as an item type.
      const targetEntities = target?.kind === 'trait' ? adminSchema.traits : adminSchema.items
      const targetItem = target ? targetEntities.find((it) => it.name === target.name) : null

      let reverse = null
      if (targetItem?.links) {
        for (const [reverseName, reversePerspectives] of Object.entries(targetItem.links)) {
          const match = reversePerspectives.find((p) => p.linkId === perspective.linkId && p.id !== perspective.id)
          if (match) {
            reverse = {
              id: match.id,
              name: reverseName,
              description: match.description ?? '',
              cardinality: { min: match.minCardinality, max: match.maxCardinality },
            }
            break
          }
        }
      }

      return {
        id: perspective.id,
        linkId: perspective.linkId,
        name,
        description: perspective.description ?? '',
        cardinality: { min: perspective.minCardinality, max: perspective.maxCardinality },
        target: target ? { name: target.name, kind: target.kind === 'trait' ? 'trait' : 'item' } : null,
        reverse,
        // From a trait or supertype rather than declared on this item type.
        inherited: Boolean(perspective.definedIn),
        source: perspective.definedIn ? toSource(adminSchema, perspective.definedIn) : null,
      }
    }),
  )
}

// A link's own properties/groups live in their own top-level list on the wire (one entry per
// linkId, shared by both perspectives), not on either item type -- unlike an item type/trait's
// properties, which live right on the container itself.
function toLinkPropertyTree(adminSchema, linkId) {
  const link = adminSchema.links.find((l) => l.id === linkId)
  return link ? toPropertyTree(link) : { properties: [], groups: [] }
}

export function useLinkModel() {
  const schema = useSchemaSnapshot()
  return {
    getItemTypeLinkDetails: (itemTypeId) => (schema ? toItemTypeLinkDetails(schema, itemTypeId) : []),
    getLinkPropertyTree: (linkId) =>
      schema ? toLinkPropertyTree(schema, linkId) : { properties: [], groups: [] },
  }
}
