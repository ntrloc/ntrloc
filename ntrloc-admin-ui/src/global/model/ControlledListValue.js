// A controlled-list-backed property whose list actually has labels projects as {value,label}
// rather than a bare scalar (domain-graph-starter's ControlledListManager.getLabelsByValue /
// RegisterPartitionManager.applyControlledListLabels) -- a list with no labels at all still
// projects as a bare scalar, unchanged. This is the one shared place that recognizes that exact
// shape, so every property-display/edit path (ItemPaneViewModel, PropertyTreeView,
// flattenProperties) agrees on what counts as "a controlled-list value object" versus a real
// nested property group, which also shows up as a plain object.
//
// The two-key {value,label} shape is a deliberately narrow signature: a real schema group could
// in principle define leaves literally named "value" and "label" and nothing else, which would
// collide with this check. That's the same kind of reserved-shape tradeoff BINARY properties
// already make with their own injected "id"/"url" keys.
export function isControlledListValue(value) {
  return (
    value !== null &&
    typeof value === 'object' &&
    !Array.isArray(value) &&
    Object.keys(value).length === 2 &&
    'value' in value &&
    'label' in value
  )
}

// Display form: the resolved label, or the bare value for an unwrapped (unlabeled-list, or
// non-controlled-list) scalar. Arrays (SET/LIST cardinality) are handled element-wise by the
// caller, same as every other leaf-shape concern in this codebase.
export function controlledListDisplayValue(value) {
  return isControlledListValue(value) ? value.label ?? value.value : value
}

// Edit/diff form: the raw value a mutation actually submits -- never the label (rules (1)-(3) of
// the controlled-list design: label is never read on the mutation/validation path). Unwraps one
// level of {value,label}, or a SET/LIST array of them; a plain scalar or array passes through
// unchanged.
export function controlledListRawValue(value) {
  if (Array.isArray(value)) return value.map(controlledListRawValue)
  return isControlledListValue(value) ? value.value : value
}
