import Filter from '../../controls/filter/Filter'
import ChevronIcon from '../../controls/chevron-icon/ChevronIcon'
import { usePropertyTreeViewModel } from './PropertyTreeViewModel'
import './PropertyTreeView.css'

const INDENT_PX = 20

const TYPE_LABELS = {
  STRING: 'String',
  INT: 'Integer',
  LONG: 'Long',
  DOUBLE: 'Double',
  DATE: 'Date',
  DATETIME: 'Date/Time',
  BOOLEAN: 'Boolean',
  BINARY: 'Binary',
}

const CARDINALITY_SUFFIXES = {
  SINGLE: '',
  LIST: ' List',
  SET: ' Set',
}

const USAGE_LABELS = {
  OPTIONAL: 'optional',
  REQUIRED: 'required',
  DEPRECATED: 'deprecated',
}

// A single-glance summary in place of separate Type/Cardinality/Usage columns -- a prior granular,
// per-value table (meant to double as an editor) turned out not worth it for a read-only view.
// e.g. "String (required)", "Integer List (optional)".
function formatPropertyType(property) {
  const type = TYPE_LABELS[property.type] ?? property.type
  const cardinality = CARDINALITY_SUFFIXES[property.cardinality] ?? ''
  const usage = USAGE_LABELS[property.usage] ?? property.usage
  return `${type}${cardinality} (${usage})`
}

// Shared by both the item type and trait editors' Properties tab -- first pass, display only
// (property/group editing is a separate, not-yet-requested piece of work). A single tree table
// rather than a properties grid plus a separate groups list: groups nest to any depth, shown only
// in their own "Group" column (with an expand/collapse toggle). Unfiltered, a property's row
// leaves that column blank -- which group it's under is conveyed by table position/indentation
// instead. Filtering only ever matches a property's own name/description (never a group's), and
// collapses the tree into a flat list of matches with no structure left to convey nesting -- there,
// the Group column shows each match's own immediate parent group instead.
function PropertyTreeView({ tree, onSelectTrait }) {
  const { rows, toggleGroup, isEmpty, nameFilter, setNameFilter, descriptionFilter, setDescriptionFilter } =
    usePropertyTreeViewModel(tree)

  if (isEmpty) return <p>No properties yet.</p>

  return (
    <table className="property-tree-table">
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
        </tr>
      </thead>
      <tbody>
        {rows.length === 0 && (
          <tr>
            <td className="property-tree-no-matches" colSpan={4}>
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
                    <span className="property-tree-group-via">
                      (via{' '}
                      <button
                        type="button"
                        className="property-tree-trait-link"
                        onClick={() => onSelectTrait(row.id)}
                      >
                        {row.name}
                      </button>)
                    </span>
                  )}
                </div>
              </td>
              <td />
              <td />
              <td />
            </tr>
          ) : (
            <tr key={row.id}>
              <td>{row.groupName}</td>
              <td>{row.name}</td>
              <td>{row.description}</td>
              <td>{formatPropertyType(row)}</td>
            </tr>
          ),
        )}
      </tbody>
    </table>
  )
}

export default PropertyTreeView
