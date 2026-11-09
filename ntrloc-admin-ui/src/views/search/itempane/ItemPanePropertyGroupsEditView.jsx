import Input from '../../../controls/input/Input'
import PopoverButton from '../../../controls/popover-button/PopoverButton'
import OptionList from '../../../controls/option-list/OptionList'
import CheckboxOptionList from '../../../controls/option-list/CheckboxOptionList'
import { useControlledListValues } from '../../../global/model/ControlledListModel'
import { controlledListDisplayValue } from '../../../global/model/ControlledListValue'
import { isWideLeaf, leafKey } from './ItemPaneViewModel'
import './ItemPanePropertyGroupsView.css'

// Edit-mode counterpart to ItemPanePropertyGroupsView -- same flattened-dot-path/grid shell, but
// `groups` here is ItemPaneViewModel's schema-merged editableGroups (every property the type
// defines, not just populated ones) and each leaf renders a controlled input instead of plain text.
// Purely a renderer, same as the read-only view: draft state and the save diff live in
// ItemPaneView/ItemPaneViewModel, not here.
function ItemPanePropertyGroupsEditView({ groups, draft, onChange }) {
  return (
    <item-pane-property-groups>
      {groups.map((group) => (
        <div key={group.path ?? '\0root'} className="item-pane-property-group">
          {group.path && <div className="item-pane-property-group-path">{group.path}</div>}
          <div className="item-pane-property-group-leaves">
            {group.leaves.map((leaf) => {
              const key = leafKey(group.path, leaf.name)
              const className = isWideLeaf(leaf.name, formatReadOnly(leaf.value))
                ? 'item-pane-property-group-leaf wide'
                : 'item-pane-property-group-leaf'
              return (
                <div key={leaf.name} className={className}>
                  <ItemPanePropertyInput leaf={leaf} value={draft[key]} onChange={(value) => onChange(key, value)} />
                </div>
              )
            })}
          </div>
        </div>
      ))}
    </item-pane-property-groups>
  )
}

// A controlled-list-backed property is never free text -- the list itself is the only valid set
// of values, so the editor is a picker over controlledList.values, not an <input> at all. SET/LIST
// gets a checkbox multi-select (any number of the list's values); SINGLE gets the same
// closes-on-pick single-select OptionList the item-type/sort-field pickers elsewhere already use.
function ControlledListPropertyInput({ leaf, value, onChange }) {
  const controlledList = useControlledListValues(leaf.controlledListId)
  const isMulti = leaf.cardinality !== 'SINGLE'
  let selected = value
  if (isMulti) selected = Array.isArray(value) ? value : []
  const summary = controlledListSummary(controlledList, selected, isMulti)
  return (
    <Input name={leaf.name} value={formatReadOnly(leaf.value)}>
      <PopoverButton summary={summary} disabled={controlledList.status !== 'ready'}>
        {isMulti
          ? () => <CheckboxOptionList options={controlledList.values} selectedValues={selected} onChange={onChange} />
          : ({ close }) => (
              <OptionList
                options={controlledList.values}
                value={selected}
                onChange={(nextValue) => {
                  onChange(nextValue)
                  close()
                }}
              />
            )}
      </PopoverButton>
    </Input>
  )
}

// Every leaf renders through the shared Input control (name label + plain-text/underlined-field
// switch) -- BINARY (not editable) and BOOLEAN (a checkbox, not a text field) both still get that
// same name label and view-mode text for free, via Input's own `children` override for the actual
// editing widget.
function ItemPanePropertyInput({ leaf, value, onChange }) {
  if (!leaf.editable) {
    return <Input name={leaf.name} editable={false} value={formatReadOnly(leaf.value)} />
  }

  if (leaf.controlledListId) {
    return <ControlledListPropertyInput leaf={leaf} value={value} onChange={onChange} />
  }

  if (leaf.type === 'BOOLEAN') {
    return (
      <Input name={leaf.name} value={formatReadOnly(leaf.value)}>
        <input type="checkbox" checked={Boolean(value)} onChange={(e) => onChange(e.target.checked)} />
      </Input>
    )
  }

  // SET/LIST share one plain comma-separated text editor regardless of element type -- a real
  // chip/tag editor is a reasonable follow-up, not v1 scope; ItemPaneViewModel.buildPropertyDiff is
  // what actually parses this back into typed elements on save.
  if (leaf.cardinality !== 'SINGLE') {
    return (
      <Input name={leaf.name} type="text" value={value} placeholder="Comma-separated" onChange={(e) => onChange(e.target.value)} />
    )
  }

  switch (leaf.type) {
    case 'INTEGER':
      return <Input name={leaf.name} type="number" step="1" value={value} onChange={(e) => onChange(e.target.value)} />
    case 'DECIMAL':
      return <Input name={leaf.name} type="number" step="any" value={value} onChange={(e) => onChange(e.target.value)} />
    case 'DATE':
      return <Input name={leaf.name} type="date" value={value} onChange={(e) => onChange(e.target.value)} />
    case 'DATETIME':
      return <Input name={leaf.name} type="datetime-local" value={value} onChange={(e) => onChange(e.target.value)} />
    default:
      return <Input name={leaf.name} type="text" value={value} onChange={(e) => onChange(e.target.value)} />
  }
}

// The PopoverButton trigger's own closed-state label -- resolves selected value(s) to their list
// labels where already loaded, falling back to the raw value(s) (or a placeholder) otherwise, same
// reasoning SearchPaneControlsViewModel's own summaries already use: only the thing that
// knows what a selection *means* should compute its label.
function controlledListSummary({ status, values: options }, selected, isMulti) {
  if (status === 'loading') return 'Loading…'
  const labelFor = (v) => options.find((opt) => opt.value === v)?.label ?? v
  if (isMulti) {
    return selected.length > 0 ? selected.map(labelFor).join(', ') : 'None selected'
  }
  // Not a truthiness test: 0 is a real value of an INTEGER or DECIMAL list.
  return selected === null || selected === undefined || selected === '' ? 'None' : labelFor(selected)
}

function formatReadOnly(value) {
  if (value === undefined || value === null) return '—'
  // A SET/LIST controlled-list leaf is an array of {value,label} objects; a SINGLE one is just
  // one. Neither should fall into the generic JSON.stringify branch below, which exists for a
  // real nested object this leaf-level formatter was never meant to receive.
  if (Array.isArray(value)) return value.map((v) => controlledListDisplayValue(v)).join(', ')
  if (typeof value === 'object') {
    const display = controlledListDisplayValue(value)
    return display === value ? JSON.stringify(value) : String(display)
  }
  return String(value)
}

export default ItemPanePropertyGroupsEditView
