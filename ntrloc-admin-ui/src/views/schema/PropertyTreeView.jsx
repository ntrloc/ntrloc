import Filter from '../../controls/filter/Filter'
import ChevronIcon from '../../controls/chevron-icon/ChevronIcon'
import IconButton from '../../controls/icon-button/IconButton'
import { usePropertyTreeViewModel } from './PropertyTreeViewModel'
import { formatPropertyType } from './propertyLabels'
import './PropertyTreeView.css'

// Add-group and add-property buttons for a container: the tree's top level (`name` null) or a group.
function AddButtons({ name, onAddGroup, onAddProperty }) {
  const where = name ? ` to ${name}` : ''
  return (
    <span className="property-tree-add">
      <IconButton icon="folder-plus" label={`Add group${where}`} variant="secondary" onClick={onAddGroup} />
      <IconButton icon="file-plus" label={`Add property${where}`} variant="secondary" onClick={onAddProperty} />
    </span>
  )
}

function DeleteToggle({ id, name, deletion, variant }) {
  const marked = deletion.isMarked(id)
  return (
    <IconButton
      icon={marked ? 'undo' : 'trash'}
      label={marked ? `Keep ${name}` : `Delete ${name}`}
      variant={variant}
      onClick={() => deletion.onToggle(id)}
    />
  )
}

// "(trait)" or "(supertype)" after something inherited, linking to where it comes from -- the same
// marker an inherited link carries.
function SourceMarker({ source, actions }) {
  const open = source.kind === 'trait' ? actions.onSelectTrait : actions.onSelectItemType
  return (
    <span className="property-tree-source">
      {'('}
      {open && source.id ? (
        <button
          type="button"
          className="property-tree-trait-link"
          aria-label={`Open ${source.kind} ${source.name}`}
          onClick={() => open(source.id)}
        >
          {source.kind}
        </button>
      ) : (
        source.kind
      )}
      {')'}
    </span>
  )
}

// One container's own properties. Inside a group marked for deletion (`withinDeleted`), they go
// with it, so they get no buttons of their own.
function PropertyTable({ properties, actions, withinDeleted }) {
  const { deletion, onEdit } = actions
  return (
    <table className="property-tree-table" data-deletable={deletion ? true : undefined}>
      <thead>
        <tr>
          <th>Name</th>
          <th>Description</th>
          <th>Type</th>
          {deletion && <th aria-label="Actions" />}
        </tr>
      </thead>
      <tbody>
        {properties.map((property) => {
          const changeable = deletion && !property.inherited && !withinDeleted
          const marked = deletion?.isMarked(property.id)
          return (
            <tr key={property.id} data-marked-for-delete={marked || undefined} data-edited={property.edited || undefined}>
              <td>
                <span className="property-tree-name">{property.name}</span>
                {property.source && <SourceMarker source={property.source} actions={actions} />}
              </td>
              <td>{property.description}</td>
              <td>{formatPropertyType(property)}</td>
              {deletion && (
                <td className="property-tree-actions">
                  {changeable && onEdit && !marked && (
                    <IconButton icon="edit" label={`Edit ${property.name}`} onClick={() => onEdit(property.id)} />
                  )}
                  {changeable && <DeleteToggle id={property.id} name={property.name} deletion={deletion} />}
                </td>
              )}
            </tr>
          )
        })}
      </tbody>
    </table>
  )
}

function ContainerContents({ node, actions, withinDeleted }) {
  if (node.properties.length === 0 && node.groups.length === 0) {
    return <p className="property-tree-empty">No properties yet.</p>
  }
  return (
    <>
      {node.properties.length > 0 && (
        <PropertyTable properties={node.properties} actions={actions} withinDeleted={withinDeleted} />
      )}
      {node.groups.map((group) => (
        <GroupBox key={group.id} group={group} actions={actions} withinDeleted={withinDeleted} />
      ))}
    </>
  )
}

// A group as its own box: a header with its name and, when editing a group this container declares
// itself, its add, edit, and delete buttons, then its contents in the same shape as the top level.
function GroupBox({ group, actions, withinDeleted }) {
  const { deletion, onEdit, onAddGroup, onAddProperty, toggleGroup } = actions
  const marked = deletion?.isMarked(group.id)
  const changeable = deletion && !group.inherited && !withinDeleted

  return (
    <section
      className="property-tree-group"
      aria-label={`Group ${group.name}`}
      data-marked-for-delete={marked || undefined}
      data-edited={group.edited || undefined}
    >
      <header className="property-tree-group-header">
        <button type="button" className="property-tree-group-toggle" onClick={() => toggleGroup(group.id)}>
          <ChevronIcon expanded={!group.collapsed} />
          <span className="property-tree-group-name">{group.name}</span>
        </button>
        {group.source && <SourceMarker source={group.source} actions={actions} />}
        {changeable && !marked && onAddGroup && (
          <AddButtons name={group.name} onAddGroup={() => onAddGroup(group.id)} onAddProperty={() => onAddProperty(group.id)} />
        )}
        {changeable && (
          <span className="property-tree-group-actions">
            {onEdit && !marked && (
              <IconButton icon="edit" label={`Edit ${group.name}`} variant="secondary" onClick={() => onEdit(group.id)} />
            )}
            <DeleteToggle id={group.id} name={group.name} deletion={deletion} variant="secondary" />
          </span>
        )}
      </header>
      {!group.collapsed && (
        <div className="property-tree-group-body">
          <ContainerContents node={group} actions={actions} withinDeleted={withinDeleted || marked} />
        </div>
      )}
    </section>
  )
}

// Shared by the item type and trait editors' Properties tab and each link's properties. Each group
// is a box of its own, nested inside its parent's, holding its own properties table and then its
// subgroups -- so every button sits on the thing it acts on. One filter narrows the whole tree by
// property name or description, hiding groups with nothing matching. `filter`, when given, comes
// from a filter box the caller shows elsewhere (e.g. its card's header) rather than this one's own.
// `heading`, when given, is shown at the top of the box -- and `collapsed` leaves only it showing.
//
// `deletion` ({ isMarked, onToggle }), when given, puts edit and delete/undo buttons on every
// property and group this container declares itself -- marking it for deletion rather than deleting
// it outright; a group marked for deletion takes everything inside it along. `onEdit` opens the
// editor on a property or group, and `onAddProperty`/`onAddGroup` add one at the top level (null) or
// inside a group (its id). Anything inherited from a trait or supertype gets no buttons, and a
// "(trait)" or "(supertype)" marker linking there through onSelectTrait/onSelectItemType. Pending
// edits (`edited` on a node) are flagged until they're saved.
function PropertyTreeView({
  tree,
  filter: externalFilter,
  heading,
  collapsed = false,
  onSelectTrait,
  onSelectItemType,
  deletion,
  onEdit,
  onAddProperty,
  onAddGroup,
}) {
  const { root, toggleGroup, isEmpty, noMatches, filter, setFilter } = usePropertyTreeViewModel(tree, externalFilter)
  const actions = { deletion, onEdit, onAddGroup, onAddProperty, onSelectTrait, onSelectItemType, toggleGroup }
  const editable = Boolean(deletion && onAddGroup)
  const showFilter = !isEmpty && externalFilter === undefined

  if (isEmpty && !editable && !heading) return <p>No properties yet.</p>

  let contents
  if (noMatches) contents = <p className="property-tree-empty">No matching properties.</p>
  else if (isEmpty && !editable) contents = <p className="property-tree-empty">No properties yet.</p>
  else contents = <ContainerContents node={root} actions={actions} withinDeleted={false} />

  return (
    <property-tree>
      {heading}
      {!collapsed && (editable || showFilter) && (
        <div className="property-tree-toolbar">
          {editable && <AddButtons name={null} onAddGroup={() => onAddGroup(null)} onAddProperty={() => onAddProperty(null)} />}
          {showFilter && <Filter name="property-filter" value={filter} onChange={setFilter} placeholder="Filter properties" />}
        </div>
      )}
      {!collapsed && contents}
    </property-tree>
  )
}

export default PropertyTreeView
