import { CONTROLLED_LIST_TYPES, TYPE_LABELS } from '../propertyLabels'

// How a controlled list's values are entered, checked, compared and shown, by the list's value
// type. A value is kept as text in the form the server shows it -- a DATE as 2026-01-31, a DATETIME
// as a UTC instant like 2026-01-31T09:30:00Z -- and sent back that way.

const WHOLE_NUMBER = /^-?\d+$/
const NUMBER = /^-?(\d+\.?\d*|\.\d+)(e[-+]?\d+)?$/i
const ISO_DATE = /^\d{4}-\d{2}-\d{2}$/

const asText = (value) => value

// Each type's `problem` says what's wrong with a value (null when nothing is); `key` is what two
// values are compared by, given values without a problem -- 2 and 2.0 are one DECIMAL value.
// `input` is the field to enter one in, with `toInput`/`fromInput` converting to and from it.
const VALUE_TYPES = {
  STRING: { problem: () => null, key: asText },
  INTEGER: {
    inputMode: 'numeric',
    problem: (value) => (WHOLE_NUMBER.test(value) ? null : 'Must be a whole number.'),
    key: (value) => BigInt(value).toString(),
  },
  DECIMAL: {
    inputMode: 'decimal',
    problem: (value) => (NUMBER.test(value) ? null : 'Must be a number.'),
    key: (value) => String(Number(value)),
  },
  DATE: {
    input: 'date',
    problem: (value) => (ISO_DATE.test(value) && !Number.isNaN(Date.parse(value)) ? null : 'Must be a date.'),
    key: asText,
  },
  // Entered as UTC, as the item editor's date/time fields are.
  DATETIME: {
    input: 'datetime-local',
    problem: (value) => (Number.isNaN(Date.parse(value)) ? 'Must be a date and time.' : null),
    key: (value) => new Date(value).toISOString(),
    toInput: (value) => value.slice(0, 16),
    fromInput: (value) => (value ? `${value}:00Z` : ''),
    display: (value) => `${value.slice(0, 16).replace('T', ' ')} UTC`,
  },
}

export const VALUE_TYPE_OPTIONS = CONTROLLED_LIST_TYPES.map((type) => ({ value: type, label: TYPE_LABELS[type] }))

export function valueType(type) {
  const rules = VALUE_TYPES[type] ?? VALUE_TYPES.STRING
  return { toInput: asText, fromInput: asText, display: asText, ...rules }
}
