import './OptionList.css'
import './CheckboxOptionList.css'

// Multi-select counterpart to OptionList (flat, single-select, closes its own PopoverButton on
// pick) -- a controlled-list-backed SET/LIST property's own editor, where picking one value isn't
// meant to close the panel, since picking more is the whole point. Shares OptionList's own list
// shell (min-width/max-height/scroll) but not its button-per-row markup -- a checkbox needs its own
// label/input pairing, not a single clickable row.
function CheckboxOptionList({ options, selectedValues, onChange }) {
  const selected = new Set(selectedValues)

  function toggle(value) {
    const next = new Set(selected)
    if (next.has(value)) next.delete(value)
    else next.add(value)
    onChange(Array.from(next))
  }

  return (
    <ul className="option-list" role="listbox" aria-multiselectable="true">
      {options.map((opt) => (
        <li key={opt.value} role="option" aria-selected={selected.has(opt.value)}>
          <label className="checkbox-option-list-option">
            <input type="checkbox" checked={selected.has(opt.value)} onChange={() => toggle(opt.value)} />
            {/* A controlled list's own label is nullable server-side (AllowedValue.label) -- an
                option with no label of its own falls back to showing its raw value rather than
                rendering a blank row next to the checkbox. */}
            {opt.label ?? opt.value}
          </label>
        </li>
      ))}
    </ul>
  )
}

export default CheckboxOptionList
