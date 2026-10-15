import { isControlledListValue } from '../../global/model/ControlledListValue'

// Properties can nest (e.g. "dimensions.widthCm"), so this flattens to dot-path/value pairs for
// display -- formatting doesn't matter yet, just showing everything a projection actually returned.
// Shared by SearchResultItemView and ItemPaneView -- both display a projected item's properties the
// same way. A {value,label} leaf (a labeled controlled-list value) is kept as-is, not descended
// into -- see ControlledListValue.js's own comment on why that shape isn't a nested group.
export function flattenProperties(properties, prefix = '') {
  return Object.entries(properties ?? {}).flatMap(([key, value]) => {
    const path = prefix ? `${prefix}.${key}` : key
    if (value !== null && typeof value === 'object' && !Array.isArray(value) && !isControlledListValue(value)) {
      return flattenProperties(value, path)
    }
    return [[path, value]]
  })
}
