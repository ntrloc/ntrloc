import Input from '../../../controls/input/Input'
import Switch from '../../../controls/switch/Switch'
import PopoverButton from '../../../controls/popover-button/PopoverButton'
import OptionList from '../../../controls/option-list/OptionList'
import CheckboxOptionList from '../../../controls/option-list/CheckboxOptionList'
import './ItemTypeOverviewView.css'

// `overview` is ItemTypeViewModel's Overview slice: current field values plus their setters.
function ItemTypeOverviewView({ overview, editable }) {
  const { fields } = overview

  return (
    <item-type-overview>
      <Input name="Item type name" editable={editable} value={fields.name} onChange={(e) => overview.setName(e.target.value)} />
      <Input
        name="Description"
        editable={editable}
        value={fields.description}
        onChange={(e) => overview.setDescription(e.target.value)}
      />
      <Input
        name="Display label"
        editable={editable}
        placeholder="Default"
        value={editable ? fields.displayLabelPattern : fields.displayLabelPattern || 'Default'}
        onChange={(e) => overview.setDisplayLabelPattern(e.target.value)}
      />
      <Switch name="Abstract type" editable={editable} on={fields.abstractType} onChange={overview.setAbstractType} />
      <PopoverButton name="Parent type" editable={editable} summary={overview.supertypeSummary}>
        {({ close }) => (
          <OptionList
            options={overview.supertypeOptions}
            value={overview.supertypeValue}
            onChange={(value) => {
              overview.setSupertypeValue(value)
              close()
            }}
          />
        )}
      </PopoverButton>
      <PopoverButton name="Traits" editable={editable} summary={overview.traitSummary}>
        <CheckboxOptionList options={overview.traitOptions} selectedValues={fields.traitIds} onChange={overview.setTraitIds} />
      </PopoverButton>
    </item-type-overview>
  )
}

export default ItemTypeOverviewView
