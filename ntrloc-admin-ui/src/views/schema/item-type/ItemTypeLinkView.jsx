import { useState } from 'react'
import { TriangleIcon } from '../../../controls/collapsible-card/CollapsibleCard'
import Filter from '../../../controls/filter/Filter'
import Icon from '../../../controls/icon/Icon'
import IconButton from '../../../controls/icon-button/IconButton'
import PropertyTreeView from '../PropertyTreeView'
import './ItemTypeLinkView.css'

// max unset means "any" -- nothing meaningful to show alongside min then except the range itself.
// "(1)" when min and max are the same, "(0-1)" when they differ, "(0-any)" with no max.
function formatCardinality({ min, max }) {
  if (max == null) return `${min}-any`
  return min === max ? `${max}` : `${min}-${max}`
}

// A perspective's name and cardinality, then -- when it has a description -- an (i) that shows it
// on hover, and gives it to screen readers.
function ArrowLabel({ perspective }) {
  return (
    <span className="item-type-link-arrow-label" data-edited={perspective.edited || undefined}>
      <span className="item-type-link-name">{perspective.name}</span> ({formatCardinality(perspective.cardinality)})
      {perspective.description && (
        <span
          className="item-type-link-description"
          role="img"
          aria-label={perspective.description}
          title={perspective.description}
        >
          <Icon name="info" size={18} />
        </span>
      )}
    </span>
  )
}

// One link, as a box of its own: the arrow out to its target labeled above with this item type's
// name for it, the arrow back labeled below with the target's, and then the link's properties --
// shown and edited the same way as the item type's own, in a box with its own heading and filter.
// The triangle folds the box down to just the arrows. An inherited link says where it comes from,
// linking there, and can't be changed here.
//
// `expanded`/`propertiesExpanded` and their toggles are owned by the caller, so they last while
// browsing. onToggleDelete and onEdit, when given, add delete/undo and edit buttons for the link
// itself; propertyEditing ({ deletion, onEdit, onAddProperty, onAddGroup }) makes its properties
// editable, adding at their top level adding to the link itself.
function ItemTypeLinkView({
  link,
  expanded,
  onToggleExpanded,
  propertiesExpanded,
  onTogglePropertiesExpanded,
  onSelectTrait,
  onSelectItemType,
  markedForDelete = false,
  onToggleDelete,
  onEdit,
  propertyEditing,
}) {
  const [filter, setFilter] = useState('')
  const targetName = link.target?.name ?? 'Unknown'
  const editing = propertyEditing && !markedForDelete
  const { source } = link
  const openSource = source?.id && (source.kind === 'trait' ? onSelectTrait : onSelectItemType)

  return (
    <item-type-link-view data-marked-for-delete={markedForDelete || undefined}>
      <div className="item-type-link-top">
        <button
          type="button"
          className="item-type-link-toggle"
          aria-expanded={expanded}
          aria-label={`${link.name} link`}
          onClick={onToggleExpanded}
        >
          <TriangleIcon expanded={expanded} />
        </button>
        <ArrowLabel perspective={link} />
        {source && (
          <span className="item-type-link-source">
            (
            {openSource ? (
              <button
                type="button"
                className="property-tree-trait-link"
                aria-label={`Open ${source.kind} ${source.name}`}
                onClick={() => openSource(source.id)}
              >
                {source.kind}
              </button>
            ) : (
              source.kind
            )}
            )
          </span>
        )}
        {onToggleDelete && (
          <span className="item-type-link-actions">
            <IconButton
              icon={markedForDelete ? 'undo' : 'trash'}
              label={markedForDelete ? `Keep ${link.name}` : `Delete ${link.name}`}
              variant="secondary"
              onClick={onToggleDelete}
            />
          </span>
        )}
      </div>
      <div className="item-type-link-diagram">
        <div className="item-type-link-arrows">
          <div className="item-type-link-arrow item-type-link-arrow-forward" />
          <div className="item-type-link-arrow-middle">
            {onEdit && !markedForDelete && (
              <IconButton icon="edit" label={`Edit link ${link.name}`} variant="secondary" onClick={onEdit} />
            )}
          </div>
          {link.reverse && (
            <>
              <div className="item-type-link-arrow item-type-link-arrow-backward" />
              <ArrowLabel perspective={link.reverse} />
            </>
          )}
        </div>
        <div className="item-type-link-box">
          {targetName}
          {link.target?.kind === 'trait' && <span className="item-type-link-box-kind"> (trait)</span>}
        </div>
      </div>
      {expanded && (
        <PropertyTreeView
          tree={link.propertyTree}
          filter={filter}
          collapsed={!propertiesExpanded}
          heading={
            <div className="item-type-link-properties-header">
              <button
                type="button"
                className="item-type-link-properties-toggle"
                aria-expanded={propertiesExpanded}
                onClick={onTogglePropertiesExpanded}
              >
                <TriangleIcon expanded={propertiesExpanded} />
                Properties
              </button>
              {propertiesExpanded && (
                <Filter name="link-property-filter" value={filter} onChange={setFilter} placeholder="Filter properties" />
              )}
            </div>
          }
          onSelectTrait={onSelectTrait}
          onSelectItemType={onSelectItemType}
          deletion={editing ? propertyEditing.deletion : undefined}
          onEdit={editing ? propertyEditing.onEdit : undefined}
          onAddProperty={editing ? (parentId) => propertyEditing.onAddProperty(parentId ?? link.linkId) : undefined}
          onAddGroup={editing ? (parentId) => propertyEditing.onAddGroup(parentId ?? link.linkId) : undefined}
        />
      )}
    </item-type-link-view>
  )
}

export default ItemTypeLinkView
