import FieldLabel from '../../../controls/field-label/FieldLabel'
import Input from '../../../controls/input/Input'
import '../EntityOverviewList.css'
import './TraitOverviewView.css'

// A trait's own fields -- the part of an item type's Overview that applies to a trait -- and the item
// types that implement it, each a link to that item type. `overview` is the trait view model's
// Overview slice.
function TraitOverviewView({ overview, editable, onSelectItemType }) {
  const { fields, implementingItemTypes } = overview
  return (
    <trait-overview>
      <Input name="Trait name" editable={editable} value={fields.name} onChange={(e) => overview.setName(e.target.value)} />
      <Input
        name="Description"
        editable={editable}
        value={fields.description}
        onChange={(e) => overview.setDescription(e.target.value)}
      />
      <input-control>
        <FieldLabel>Implemented by</FieldLabel>
        {implementingItemTypes.length === 0 ? (
          <span className="input-control-value">None</span>
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
      </input-control>
    </trait-overview>
  )
}

export default TraitOverviewView
