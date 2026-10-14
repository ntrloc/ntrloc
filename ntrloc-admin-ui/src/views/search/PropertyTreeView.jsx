import './PropertyTreeView.css'

// Displays a projected item's (possibly nested) properties as a tree grouped by dot path, rather
// than flattened to full dot-path/value rows (see flattenProperties.js, still used elsewhere) --
// the shared ancestry a flat list spells out on every single row (e.g.
// "File.content.metadata.exif.Make", "File.content.metadata.exif.Flash", ...) only needs to be
// said once, at the group it's nested under. Within a level, leaves come before subtrees (a
// group's own direct values read like a summary of it, before drilling into its nested groups),
// and each of those two bands is sorted alphabetically on its own -- independent of whatever order
// the server returned them in, since insertion order isn't meaningful here.
function PropertyTreeView({ properties }) {
  return (
    <property-tree-view>
      <PropertyTreeLevel properties={properties} />
    </property-tree-view>
  )
}

function PropertyTreeLevel({ properties }) {
  const keys = Object.keys(properties ?? {}).sort((a, b) => {
    const aIsGroup = isGroup(properties[a])
    const bIsGroup = isGroup(properties[b])
    if (aIsGroup !== bIsGroup) return aIsGroup ? 1 : -1
    return a.localeCompare(b)
  })

  return (
    <ul className="property-tree-level">
      {keys.map((key) => {
        const value = properties[key]
        return (
          <li key={key} className="property-tree-node">
            {isGroup(value) ? (
              <>
                <span className="property-tree-group-name">{key}</span>
                <PropertyTreeLevel properties={value} />
              </>
            ) : (
              <div className="property-tree-leaf">
                <span className="property-tree-leaf-name">{key}</span>
                <span className="property-tree-leaf-value">{String(value)}</span>
              </div>
            )}
          </li>
        )
      })}
    </ul>
  )
}

// Same leaf/group split flattenProperties.js already uses: a plain object is a nested group to
// recurse into, everything else (including arrays) is a leaf value as-is.
function isGroup(value) {
  return value !== null && typeof value === 'object' && !Array.isArray(value)
}

export default PropertyTreeView
