import { useTraitOverviewViewModel } from './TraitOverviewViewModel'
import '../EntityOverviewList.css'

// Implementing item types are links -- the same "click to jump there" affordance already
// established for a property group's own "(via <trait>)" link and ItemTypeOverviewView's own
// parent-type/implements links, just reused here from the other direction.
function TraitOverviewView({ trait, onSelectItemType }) {
  const { implementingItemTypes } = useTraitOverviewViewModel(trait)

  return (
    <dl className="entity-overview-list">
      <div className="entity-overview-row">
        <dt>Implemented by</dt>
        <dd>
          {implementingItemTypes.length === 0 ? (
            <span className="entity-overview-empty">None</span>
          ) : (
            <ul className="entity-overview-link-list">
              {implementingItemTypes.map((itemType) => (
                <li key={itemType.id}>
                  <button type="button" className="entity-overview-link" onClick={() => onSelectItemType(itemType.id)}>
                    {itemType.name}
                  </button>
                </li>
              ))}
            </ul>
          )}
        </dd>
      </div>
    </dl>
  )
}

export default TraitOverviewView
