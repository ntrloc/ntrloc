// Properties can nest (e.g. "dimensions.widthCm"), so this flattens to dot-path/value pairs for
// display -- formatting doesn't matter yet, just showing everything a projection actually returned.
// Shared by SearchResultItemView and ItemPaneView -- both display a projected item's properties the
// same way.
export function flattenProperties(properties, prefix = '') {
  return Object.entries(properties ?? {}).flatMap(([key, value]) => {
    const path = prefix ? `${prefix}.${key}` : key
    if (value !== null && typeof value === 'object' && !Array.isArray(value)) {
      return flattenProperties(value, path)
    }
    return [[path, value]]
  })
}
