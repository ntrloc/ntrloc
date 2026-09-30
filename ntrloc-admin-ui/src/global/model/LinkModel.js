import { useSchemaSnapshot } from './SchemaCache'
import { toPropertyTree } from './propertyTreeShaping'

// Every link is really two perspectives (one on each side) sharing a linkId -- this item type's own
// side is right there in its own links map, but the *reverse* perspective's own name/cardinality
// only exists on the target item type's own links map. Found by scanning it for whichever entry
// shares this perspective's linkId but isn't this exact perspective (id differs) -- the id check is
// what keeps a self-link (an item type linking to its own kind) from matching itself as its own
// reverse. Only the first target is resolved for now -- a link naming more than one possible target
// type is a real (if rarer) case this doesn't yet have a diagram for.
function toItemTypeLinkDetails(adminSchema, itemTypeId) {
  const item = adminSchema.items.find((it) => it.id === itemTypeId)
  if (!item || !item.links) return []

  return Object.entries(item.links).flatMap(([name, perspectives]) =>
    perspectives.map((perspective) => {
      const target = perspective.targets[0] ?? null
      const targetItem = target ? adminSchema.items.find((it) => it.name === target.name) : null

      let reverse = null
      if (targetItem?.links) {
        for (const [reverseName, reversePerspectives] of Object.entries(targetItem.links)) {
          const match = reversePerspectives.find((p) => p.linkId === perspective.linkId && p.id !== perspective.id)
          if (match) {
            reverse = { name: reverseName, cardinality: { min: match.minCardinality, max: match.maxCardinality } }
            break
          }
        }
      }

      return {
        id: perspective.id,
        linkId: perspective.linkId,
        name,
        cardinality: { min: perspective.minCardinality, max: perspective.maxCardinality },
        target: target ? { name: target.name } : null,
        reverse,
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
