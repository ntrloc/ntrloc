import FieldLabel from '../../../../controls/field-label/FieldLabel'
import OptionList from '../../../../controls/option-list/OptionList'
import PopoverButton from '../../../../controls/popover-button/PopoverButton'
import { useControlledListValues } from '../../../../global/model/ControlledListModel'
import { formatInstant, operatorOf, operatorsFor, predicateFor, testOf, valueField } from './pipelineTests'
import './TestPicker.css'

// A condition's test: which one (only those its subject can have -- see pipelineTests.testsFor),
// the operator when the test leaves it open (a list's "sum"), and its value(s), shaped by what the
// value is. A test that doesn't fit the subject reads as "Choose a test".
//
// end: what the subject ends on (pipelineTests.subjectEnd). onChange(predicate, key): key groups a
// field's typing into one undo step.
function TestPicker({ name, tests, end, predicate, onChange }) {
  const test = testOf(predicate, tests)
  if (tests.length === 0) {
    return (
      <test-picker>
        <FieldLabel>{name}</FieldLabel>
        <p className="test-picker-note">Choose a subject to test.</p>
      </test-picker>
    )
  }
  const operator = test ? operatorOf(test, predicate) : null
  const set = (field, value, key) => onChange({ ...predicate, [field]: value }, key)

  return (
    <test-picker>
      <FieldLabel>{name}</FieldLabel>
      <Choice
        summary={test?.label ?? 'Choose a test'}
        options={tests.map((t) => ({ value: t.id, label: t.label }))}
        value={test?.id}
        onChange={(id) => onChange(predicateFor(tests.find((t) => t.id === id), predicate))}
      />
      {test?.operatorKind && (
        <Choice
          summary={operatorsFor(test.operatorKind).find((o) => o.operator === operator)?.label}
          options={operatorsFor(test.operatorKind).map((o) => ({ value: o.operator, label: o.label }))}
          value={operator}
          onChange={(op) => onChange({ ...predicate, operator: op, valueTo: op === 'BETWEEN' ? predicate.valueTo ?? null : undefined })}
        />
      )}
      {test?.valueKind && (
        <ValueField
          valueKind={test.valueKind}
          end={end}
          value={predicate[valueField(test)]}
          onChange={(value, key) => set(valueField(test), value, key)}
          fieldKey={`${valueField(test)}`}
        />
      )}
      {test?.valueKind && operator === 'BETWEEN' && (
        <>
          <span className="test-picker-and">and</span>
          <ValueField valueKind={test.valueKind} end={end} value={predicate.valueTo} onChange={(value, key) => set('valueTo', value, key)} fieldKey="valueTo" />
        </>
      )}
    </test-picker>
  )
}

function Choice({ summary, options, value, onChange }) {
  return (
    <PopoverButton summary={summary}>
      {({ close }) => (
        <OptionList
          options={options}
          value={value}
          onChange={(v) => {
            onChange(v)
            close()
          }}
        />
      )}
    </PopoverButton>
  )
}

// One value of a test, as what it is: typed text, a number, a date, a value from the property's
// controlled list, or one of the machine's states. Dates are kept as instants (midnight UTC for a date).
function ValueField({ valueKind, end, value, onChange, fieldKey }) {
  const listId = valueKind === 'listValue' ? end.property?.controlledListId : null
  const listValues = useControlledListValues(listId)
  const typing = (v) => onChange(v, fieldKey)

  if (valueKind === 'listValue') {
    const options = listValues.values
    return <Choice summary={options.find((o) => o.value === value)?.label ?? value ?? 'Choose a value'} options={options} value={value} onChange={onChange} />
  }
  if (valueKind === 'state') {
    // Start and End are where a machine begins and ends, not states an item is in.
    const options = (end.machine?.states ?? []).filter((s) => s.kind !== 'START' && s.kind !== 'END').map((s) => ({ value: s.id, label: s.name }))
    return <Choice summary={options.find((o) => o.value === value)?.label ?? 'Choose a state'} options={options} value={value} onChange={onChange} />
  }
  if (valueKind === 'number' || valueKind === 'count') {
    return (
      <input
        className="input-control-field"
        type="number"
        step={valueKind === 'count' ? 1 : 'any'}
        min={valueKind === 'count' ? 0 : undefined}
        value={value ?? ''}
        aria-label="Value"
        onChange={(e) => typing(e.target.value === '' ? null : valueKind === 'count' ? Math.trunc(Number(e.target.value)) : Number(e.target.value))}
      />
    )
  }
  if (valueKind === 'date' || valueKind === 'dateTime') {
    const dated = valueKind === 'date'
    const shown = value ? formatInstant(value).replace(' ', 'T').slice(0, dated ? 10 : 16) : ''
    return (
      <input
        className="input-control-field"
        type={dated ? 'date' : 'datetime-local'}
        value={shown}
        aria-label="Value"
        onChange={(e) => typing(e.target.value === '' ? null : dated ? `${e.target.value}T00:00:00Z` : `${e.target.value}:00Z`)}
      />
    )
  }
  return <input className="input-control-field" type="text" value={value ?? ''} aria-label="Value" onChange={(e) => typing(e.target.value)} />
}

export default TestPicker
