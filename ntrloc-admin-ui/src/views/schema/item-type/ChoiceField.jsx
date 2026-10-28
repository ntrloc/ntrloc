import PopoverButton from '../../../controls/popover-button/PopoverButton'
import OptionList from '../../../controls/option-list/OptionList'

// A single-select field: a labeled PopoverButton whose list closes as soon as something is picked.
// `placeholder` is shown while nothing is picked.
function ChoiceField({ name, options, value, onChange, placeholder }) {
  return (
    <PopoverButton name={name} summary={options.find((opt) => opt.value === value)?.label ?? placeholder}>
      {({ close }) => (
        <OptionList
          options={options}
          value={value}
          onChange={(picked) => {
            onChange(picked)
            close()
          }}
        />
      )}
    </PopoverButton>
  )
}

export default ChoiceField
