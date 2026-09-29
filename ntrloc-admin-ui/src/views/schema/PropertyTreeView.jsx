import Filter from '../../controls/filter/Filter'
import { usePropertyTreeViewModel } from './PropertyTreeViewModel'
import './PropertyTreeView.css'

const INDENT_PX = 20

function ChevronIcon({ expanded }) {
  return (
    <svg
      className="property-tree-group-chevron"
      data-expanded={expanded || undefined}
      viewBox="0 0 24 24"
      width="14"
      height="14"
      fill="none"
      stroke="currentColor"
      strokeWidth="2"
      strokeLinecap="round"
      strokeLinejoin="round"
    >
      <polyline points="9 18 15 12 9 6" />
    </svg>
  )
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
          <th>Cardinality</th>
          <th>Usage</th>
        </tr>
      </thead>
      <tbody>
        {rows.length === 0 && (
          <tr>
            <td className="property-tree-no-matches" colSpan={6}>
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
                      literally that trait's own id (see SchemaModel's own toGroupNode comment). */}
                  {row.traitNamespace && (
                    <span className="property-tree-group-via">
                      (via{' '}
                      <button
                        type="button"
                        className="property-tree-trait-link"
                        onClick={() => onSelectTrait(row.id)}
                      >
                        {row.name}
                      </button>
                      )
                    </span>
                  )}
                </div>
              </td>
              <td />
              <td />
              <td />
              <td />
              <td />
            </tr>
          ) : (
            <tr key={row.id}>
              <td>{row.groupName}</td>
              <td>{row.name}</td>
              <td>{row.description}</td>
              <td>{row.type}</td>
              <td>{row.cardinality}</td>
              <td>{row.usage}</td>
            </tr>
          ),
        )}
      </tbody>
    </table>
  )
}

export default PropertyTreeView
