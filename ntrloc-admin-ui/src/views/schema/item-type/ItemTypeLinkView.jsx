import { useState } from 'react'
import ChevronIcon from '../../../controls/chevron-icon/ChevronIcon'
import Filter from '../../../controls/filter/Filter'
import IconButton from '../../../controls/icon-button/IconButton'
import PropertyTreeView from '../PropertyTreeView'
import './ItemTypeLinkView.css'

// max unset means "any" -- nothing meaningful to show alongside min then except the range itself.
// A finite max is shown alone (dropping min) -- e.g. (0, 1) reads as just "(1)" -- since which of
// optional/required a single-valued link is isn't this diagram's concern yet.
function formatCardinality({ min, max }) {
  return max == null ? `${min}-any` : `${max}`
}

// One link's own diagram -- two boxes (this item type and whatever it targets) with an arrow each
// direction, labeled with that direction's own perspective name and cardinality -- plus, below it
// in the same bordered card, the link's own properties, shown and edited the same way as the item
// type's own (with a filter beside their heading while they're expanded). onToggleDelete, when
// given, adds a delete/undo toggle that marks the whole link for deletion. propertyEditing ({
// deletion, onEdit, onAddProperty, onAddGroup }), when given, makes its properties editable; adding
// at their top level adds to the link itself.
function ItemTypeLinkView({ sourceName, link, onSelectTrait, markedForDelete = false, onToggleDelete, propertyEditing }) {
  const [propertiesExpanded, setPropertiesExpanded] = useState(true)
  const [filter, setFilter] = useState('')
  const targetName = link.target?.name ?? 'Unknown'
  const editing = propertyEditing && !markedForDelete

  return (
    <item-type-link-view data-marked-for-delete={markedForDelete || undefined}>
      <div className="item-type-link-diagram">
        <div className="item-type-link-box item-type-link-box-source">{sourceName}</div>
        <div className="item-type-link-arrows">
          <div className="item-type-link-arrow-row">
            <span className="item-type-link-arrow-label">
              {link.name} ({formatCardinality(link.cardinality)})
            </span>
            <div className="item-type-link-arrow item-type-link-arrow-forward" />
          </div>
          {link.reverse && (
            <div className="item-type-link-arrow-row">
              <div className="item-type-link-arrow item-type-link-arrow-backward" />
              <span className="item-type-link-arrow-label">
                {link.reverse.name} ({formatCardinality(link.reverse.cardinality)})
              </span>
            </div>
          )}
        </div>
        <div className="item-type-link-box">{targetName}</div>
        {onToggleDelete && (
          <IconButton
            icon={markedForDelete ? 'undo' : 'trash'}
            label={markedForDelete ? `Keep ${link.name}` : `Delete ${link.name}`}
            onClick={onToggleDelete}
          />
        )}
      </div>
      <div className="item-type-link-properties">
        <div className="item-type-link-properties-header">
          <button
            type="button"
            className="item-type-link-properties-toggle"
            onClick={() => setPropertiesExpanded((current) => !current)}
          >
            <ChevronIcon expanded={propertiesExpanded} />
            Properties
          </button>
          {propertiesExpanded && (
            <Filter name="link-property-filter" value={filter} onChange={setFilter} placeholder="Filter properties" />
          )}
        </div>
        {propertiesExpanded && (
          <PropertyTreeView
            tree={link.propertyTree}
            filter={filter}
            onSelectTrait={onSelectTrait}
            deletion={editing ? propertyEditing.deletion : undefined}
            onEdit={editing ? propertyEditing.onEdit : undefined}
            onAddProperty={editing ? (parentId) => propertyEditing.onAddProperty(parentId ?? link.linkId) : undefined}
            onAddGroup={editing ? (parentId) => propertyEditing.onAddGroup(parentId ?? link.linkId) : undefined}
          />
        )}
      </div>
    </item-type-link-view>
  )
}

export default ItemTypeLinkView
