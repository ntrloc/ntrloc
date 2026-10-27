import Filter from '../../controls/filter/Filter'
import ChevronIcon from '../../controls/chevron-icon/ChevronIcon'
import IconButton from '../../controls/icon-button/IconButton'
import { usePropertyTreeViewModel } from './PropertyTreeViewModel'
import { formatPropertyType } from './propertyLabels'
import './PropertyTreeView.css'

const INDENT_PX = 20

// Shared by both the item type and trait editors' Properties tab -- first pass, display only
// (property/group editing is a separate, not-yet-requested piece of work). A single tree table
// rather than a properties grid plus a separate groups list: groups nest to any depth, shown only
// in their own "Group" column (with an expand/collapse toggle). Unfiltered, a property's row
// leaves that column blank -- which group it's under is conveyed by table position/indentation
// instead. Filtering only ever matches a property's own name/description (never a group's), and
// collapses the tree into a flat list of matches with no structure left to convey nesting -- there,
// the Group column shows each match's own immediate parent group instead.
//
// `deletion` ({ isMarked, onToggle }), when given, adds a trailing column with a delete/undo toggle
// on each property this container declares itself -- marking it for deletion rather than deleting
// it outright. `onEdit`, given with it, adds an edit button beside each toggle (not on a property
// marked for deletion). Properties inherited from a trait or supertype get neither. A property
// with pending edits (`edited` on its node) is flagged until they're saved. `onAddProperty` and
// `onAddGroup`, given with `deletion`, put add buttons on each group the container declares itself;
// a group not yet saved (`isNew`) also gets the edit and delete buttons.
function PropertyTreeView({ tree, onSelectTrait, deletion, onEdit, onAddProperty, onAddGroup }) {
  const { rows, toggleGroup, isEmpty, nameFilter, setNameFilter, descriptionFilter, setDescriptionFilter } =
    usePropertyTreeViewModel(tree)

  if (isEmpty) return <p>No properties yet.</p>

  const columnCount = deletion ? 5 : 4

  return (
    <table className="property-tree-table" data-deletable={deletion ? true : undefined}>
      <thead>
        <tr>
          <th>Group</th>
          <th>
            <div className="property-tree-header-with-filter">
              <span>Name</span>
              <Filter name="property-name-filter" value={nameFilter} onChange={setNameFilter} placeholder="Filter" />
            </div>
          </th>
          <th>
            <div className="property-tree-header-with-filter">
              <span>Description</span>
              <Filter
                name="property-description-filter"
                value={descriptionFilter}
                onChange={setDescriptionFilter}
                placeholder="Filter"
              />
            </div>
          </th>
          <th>Type</th>
          {deletion && <th aria-label="Actions" />}
        </tr>
      </thead>
      <tbody>
        {rows.length === 0 && (
          <tr>
            <td className="property-tree-no-matches" colSpan={columnCount}>
              No matching properties.
            </td>
          </tr>
        )}
        {rows.map((row) =>
          row.kind === 'group' ? (
            <tr key={row.id}>
              <td style={{ paddingLeft: row.depth * INDENT_PX }}>
                <div className="property-tree-group-cell">
                  {row.hasChildren ? (
                    <button type="button" className="property-tree-group-toggle" onClick={() => toggleGroup(row.id)}>
                      <ChevronIcon expanded={!row.collapsed} />
                      {row.name}
                    </button>
                  ) : (
                    <span className="property-tree-group-label">{row.name}</span>
                  )}
                  {/* traitNamespace groups aren't real groups the container declared -- they're a
                      stand-in for everything a trait it implements contributes, and their id is
                      literally that trait's own id (see propertyTreeShaping's own toGroupNode comment). */}
                  {row.traitNamespace && (
                    <span className="property-tree-group-kind">
                      (
                      <button
                        type="button"
                        className="property-tree-trait-link"
                        aria-label={`Open trait ${row.name}`}
                        onClick={() => onSelectTrait(row.id)}
                      >
                        trait
                      </button>
                      )
                    </span>
                  )}
                </div>
              </td>
              <td />
              <td />
              <td />
              {deletion && (
                <td className="property-tree-actions">
                  {!row.inherited && onAddGroup && (
                    <IconButton icon="folder-plus" label={`Add group to ${row.name}`} onClick={() => onAddGroup(row.id)} />
                  )}
                  {!row.inherited && onAddProperty && (
                    <IconButton icon="file-plus" label={`Add property to ${row.name}`} onClick={() => onAddProperty(row.id)} />
                  )}
                  {row.isNew && onEdit && <IconButton icon="edit" label={`Edit ${row.name}`} onClick={() => onEdit(row.id)} />}
                  {row.isNew && (
                    <IconButton icon="trash" label={`Delete ${row.name}`} onClick={() => deletion.onToggle(row.id)} />
                  )}
                </td>
              )}
            </tr>
          ) : (
            <tr
              key={row.id}
              data-marked-for-delete={deletion?.isMarked(row.id) || undefined}
              data-edited={row.edited || undefined}
            >
              <td>{row.groupName}</td>
              <td>{row.name}</td>
              <td>{row.description}</td>
              <td>{formatPropertyType(row)}</td>
              {deletion && (
                <td className="property-tree-actions">
                  {!row.inherited && onEdit && !deletion.isMarked(row.id) && (
                    <IconButton icon="edit" label={`Edit ${row.name}`} onClick={() => onEdit(row.id)} />
                  )}
                  {!row.inherited && (
                    <IconButton
                      icon={deletion.isMarked(row.id) ? 'undo' : 'trash'}
                      label={deletion.isMarked(row.id) ? `Keep ${row.name}` : `Delete ${row.name}`}
                      onClick={() => deletion.onToggle(row.id)}
                    />
                  )}
                </td>
              )}
            </tr>
          ),
        )}
      </tbody>
    </table>
  )
}

export default PropertyTreeView
