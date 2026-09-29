// Turns a flat list of dot-notation property paths (e.g. "File.content.metadata.length", as returned
// by SchemaModel.getProjectableProperties) into the recursive { key, label, children } shape
// CheckboxTree expects, with one synthetic root node standing in for "everything."
//
// The root is a real, meaningful choice, not just a UI convenience: checking it means "send no
// properties restriction at all" (CollectionProjectionSpec.properties() === null) -- genuinely
// different from checking every individual property currently visible, which would only cover
// today's known fields, not ones discovered later (see docs/ntrloc-dynamic-properties-design-notes.md
// section 10's own "omit entirely" decision). ALL_PROPERTIES_KEY is exported so callers can recognize
// it without hardcoding the sentinel value themselves.
export const ALL_PROPERTIES_KEY = ''

export function buildPropertyTree(propertyPaths) {
  const root = { key: ALL_PROPERTIES_KEY, label: '(All properties)', children: [] }
  propertyPaths.forEach((path) => {
    let cursor = root
    let keyPrefix = ''
    path.split('.').forEach((segment) => {
      keyPrefix = keyPrefix ? `${keyPrefix}.${segment}` : segment
      let child = cursor.children.find((c) => c.key === keyPrefix)
      if (!child) {
        child = { key: keyPrefix, label: segment, children: [] }
        cursor.children.push(child)
      }
      cursor = child
    })
  })
  return root
}
