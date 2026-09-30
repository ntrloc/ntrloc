import { useItemTypeOverviewViewModel } from './ItemTypeOverviewViewModel'
import '../EntityOverviewList.css'

// Parent type and implemented traits are both links -- the same "click to jump there" affordance
// already established for a property group's own "(via <trait>)" link, just reused here.
function ItemTypeOverviewView({ itemType, onSelectItemType, onSelectTrait }) {
  const { traits } = useItemTypeOverviewViewModel(itemType)

  return (
    <dl className="entity-overview-list">
      <div className="entity-overview-row">
        <dt>Abstract type</dt>
        <dd>{itemType.abstractType ? 'Yes' : 'No'}</dd>
      </div>
      <div className="entity-overview-row">
        <dt>Parent type</dt>
        <dd>
          {itemType.supertype ? (
            <button type="button" className="entity-overview-link" onClick={() => onSelectItemType(itemType.supertype.id)}>
              {itemType.supertype.name}
            </button>
          ) : (
            <span className="entity-overview-empty">None</span>
          )}
        </dd>
      </div>
      <div className="entity-overview-row">
        <dt>Implements</dt>
        <dd>
          {traits.length === 0 ? (
            <span className="entity-overview-empty">None</span>
          ) : (
            <ul className="entity-overview-link-list">
              {traits.map((trait) => (
                <li key={trait.id}>
                  <button type="button" className="entity-overview-link" onClick={() => onSelectTrait(trait.id)}>
                    {trait.name}
                  </button>
                </li>
              ))}
            </ul>
          )}
        </dd>
      </div>
      <div className="entity-overview-row">
        <dt>Display label</dt>
        <dd>{itemType.displayLabelPattern || <span className="entity-overview-empty">Default</span>}</dd>
      </div>
    </dl>
  )
}

export default ItemTypeOverviewView
