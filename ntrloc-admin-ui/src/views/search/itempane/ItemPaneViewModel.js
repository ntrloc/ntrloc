import { useEffect, useState, useSyncExternalStore } from 'react'
import { ItemPaneModel } from './ItemPaneModel'
import { useItemTypeModel } from '../../../global/model/ItemTypeModel'
import { controlledListRawValue, isControlledListValue } from '../../../global/model/ControlledListValue'

// One ItemPaneModel per pane, created once and reused across re-renders -- same convention as
// useSearchPaneViewModel. itemTypeName/itemId are fixed for the pane's lifetime (an ItemPane always
// shows one specific item), so the load effect only re-runs if those ever actually change.
// groupedProperties/editableGroups are derived here (not left to the view) so
// ItemPanePropertyGroupsView/ItemPanePropertyGroupsEditView stay dumb map-over-groups renderers --
// same view/viewmodel split SearchPaneControlsViewModel's own summary strings already use.
export function useItemPaneViewModel(itemTypeName, itemId) {
  const [model] = useState(() => new ItemPaneModel())
  const status = useSyncExternalStore(model.subscribe, model.getStatus)
  const item = useSyncExternalStore(model.subscribe, model.getItem)
  const { getEditablePropertyGroups } = useItemTypeModel()

  useEffect(() => {
    model.load(itemTypeName, itemId)
  }, [model, itemTypeName, itemId])

  return {
    status,
    item,
    groupedProperties: item ? groupProperties(item.properties) : [],
    // Schema-driven (every property the type defines, not just ones this item happens to have a
    // value for) merged with the item's own current values -- what editing needs that read-only
    // display doesn't, see ItemTypeModel.getEditablePropertyGroups' own comment.
    editableGroups: item ? mergeEditableGroups(getEditablePropertyGroups(itemTypeName), item.properties) : [],
    reload: () => model.load(itemTypeName, itemId),
  }
}

// Flattens a (possibly nested) properties object into an ordered list of { path, leaves } groups:
// one entry per level that has its own direct leaf values, path being that level's full dot-path
// (null for the root level, which gets no header of its own -- there's nothing to say "this is the
// top" that isn't already obvious). Within a level, leaves are listed before descending into its
// subgroups, and both bands are sorted alphabetically on their own -- the same two-band ordering
// PropertyTreeView already uses (direct values read like a summary of a group, before drilling into
// what it nests), just flattened into a list of groups instead of recursive JSX.
export function groupProperties(properties, path = null) {
  const entries = Object.entries(properties ?? {})
  const leafEntries = entries.filter(([, value]) => !isGroup(value)).sort(([a], [b]) => a.localeCompare(b))
  const groupEntries = entries.filter(([, value]) => isGroup(value)).sort(([a], [b]) => a.localeCompare(b))

  const groups = []
  if (leafEntries.length > 0) {
    groups.push({ path, leaves: leafEntries.map(([name, value]) => ({ name, value })) })
  }
  for (const [key, value] of groupEntries) {
    groups.push(...groupProperties(value, path ? `${path}.${key}` : key))
  }
  return groups
}

// Same leaf/group split PropertyTreeView.js already uses: a plain object is a nested group to
// recurse into, everything else (including arrays) is a leaf value as-is -- except a
// {value,label} object, which is a controlled-list leaf's own value, not a group to descend into
// (see ControlledListValue.js's own comment on why that shape is reserved).
function isGroup(value) {
  return value !== null && typeof value === 'object' && !Array.isArray(value) && !isControlledListValue(value)
}

// Reads a (possibly nested) properties object down to the object living at `path` -- the same
// navigation resolveGroupValue performs server-side, just read-only here.
function resolveGroupValue(properties, path) {
  if (!path) return properties ?? {}
  return path.split('.').reduce((node, key) => (node && typeof node === 'object' ? node[key] : undefined), properties) ?? {}
}

function mergeEditableGroups(schemaGroups, properties) {
  return schemaGroups.map((group) => {
    const groupValue = resolveGroupValue(properties, group.path)
    return { path: group.path, leaves: group.leaves.map((leaf) => ({ ...leaf, value: groupValue[leaf.name] })) }
  })
}

// The draft key an edit form's own <input> is keyed/controlled by -- the leaf's full dot-path, the
// same identity buildPropertyDiff below re-parses it back from.
export function leafKey(path, name) {
  return path ? `${path}.${name}` : name
}

// Seeds an edit form's draft state from editableGroups' current values -- every editable leaf gets
// a defined entry so each <input> is controlled from the moment edit mode opens, never undefined on
// the first render. A controlled-list-backed SET/LIST keeps its real array (the checkbox picker's
// own selected-values shape); a free-text SET/LIST instead joins into one comma-separated string
// (the plain-text editor's own shape, parsed back apart in buildPropertyDiff). BOOLEAN becomes a
// real boolean for a checkbox; everything else keeps its raw value, or '' when unset.
export function buildDraft(editableGroups) {
  const draft = {}
  for (const group of editableGroups) {
    for (const leaf of group.leaves) {
      if (!leaf.editable) continue
      const key = leafKey(group.path, leaf.name)
      // Every picker/input downstream (CheckboxOptionList's selectedValues, OptionList's value,
      // a plain text input) works against raw values, never a {value,label} wrapper -- see
      // ControlledListValue.js's own comment on why a labeled controlled list projects wrapped.
      const value = controlledListRawValue(leaf.value)
      if (leaf.cardinality !== 'SINGLE') {
        draft[key] = leaf.controlledListId
          ? Array.isArray(value)
            ? [...value]
            : []
          : Array.isArray(value)
            ? value.join(', ')
            : ''
      } else if (leaf.type === 'BOOLEAN') {
        draft[key] = Boolean(value)
      } else {
        draft[key] = value ?? ''
      }
    }
  }
  return draft
}

// Builds the nested properties diff ItemUpdateMutation expects (see SearchDelegate.updateItem's own
// comment): only leaves whose draft value actually differs from the item's current value, nested
// back into the same group shape the server's own resolveGroupValue walks. A field emptied back to
// nothing sends null (the protocol's own "clear this property" sentinel) when the property
// currently has a value, and is left out entirely when it was already unset -- there is no
// meaningful change to report in that case.
export function buildPropertyDiff(editableGroups, draft) {
  const diff = {}
  for (const group of editableGroups) {
    for (const leaf of group.leaves) {
      if (!leaf.editable) continue
      const key = leafKey(group.path, leaf.name)
      const nextValue = toWireValue(leaf, draft[key])
      // leaf.value may still be the server's {value,label}-wrapped shape -- unwrap before
      // comparing, or every labeled controlled-list field would always look "changed" against
      // nextValue's bare wire shape even when the draft was never touched.
      if (!valuesDiffer(controlledListRawValue(leaf.value), nextValue)) continue
      setNestedValue(diff, group.path, leaf.name, nextValue)
    }
  }
  return diff
}

function toWireValue(leaf, rawDraft) {
  if (leaf.cardinality !== 'SINGLE') {
    // A controlled-list value already came straight from the list's own API response -- it's
    // already correctly typed (STRING/INT/LONG, matching the list's own valueType), so this is a
    // plain pass-through rather than re-parsing user-typed text through coerceScalar.
    if (leaf.controlledListId) {
      const values = Array.isArray(rawDraft) ? rawDraft : []
      return values.length > 0 ? values : null
    }
    const values = String(rawDraft ?? '')
      .split(',')
      .map((s) => s.trim())
      .filter((s) => s.length > 0)
      .map((s) => coerceScalar(leaf.type, s))
      .filter((v) => v !== undefined)
    return values.length > 0 ? values : null
  }
  if (leaf.type === 'BOOLEAN') return Boolean(rawDraft)
  const coerced = coerceScalar(leaf.type, rawDraft)
  return coerced === undefined || coerced === '' ? null : coerced
}

function coerceScalar(type, raw) {
  switch (type) {
    case 'INT':
    case 'LONG': {
      const n = Number.parseInt(raw, 10)
      return Number.isNaN(n) ? undefined : n
    }
    case 'DOUBLE': {
      const n = Number.parseFloat(raw)
      return Number.isNaN(n) ? undefined : n
    }
    case 'BOOLEAN':
      return Boolean(raw)
    // A native datetime-local input has no seconds or offset ("yyyy-MM-ddTHH:mm") -- the server
    // requires a real offset/instant (OffsetDateTime/Instant.parse), so this assumes the input is
    // UTC and completes it into one.
    case 'DATETIME':
      return raw ? `${raw}:00Z` : undefined
    default:
      return raw
  }
}

function valuesDiffer(original, next) {
  return JSON.stringify(original ?? null) !== JSON.stringify(next)
}

function setNestedValue(target, path, name, value) {
  if (!path) {
    target[name] = value
    return
  }
  let node = target
  for (const segment of path.split('.')) {
    node[segment] ??= {}
    node = node[segment]
  }
  node[name] = value
}
