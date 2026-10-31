// The tests a condition can make of its subject, by what the subject ends on: a property (by its type,
// and whether it holds one value or a list), a linked item, or a state machine. Each test is one
// predicate type, sometimes with its operator fixed ("is greater than"), sometimes with the operator
// left to choose (a list's "sum" can be compared any way).
import { describeConditionSubject } from './pipelineConditionText'
import { propertiesOf } from './pipelineSchema'

// What a subject ends on, for choosing its tests: { kind: 'self' } | { kind: 'item', itemTypeId } |
// { kind: 'stateMachine', machine } | { kind: 'property', property }.
export function subjectEnd(schema, itemTypeId, subject) {
  const chain = describeConditionSubject(schema, itemTypeId, subject ?? { type: 'SELF' })
  if (chain.terminal.kind === 'stateMachine') return { kind: 'stateMachine', machine: chain.terminal.machine }
  if (chain.terminal.kind === 'property') {
    let last = subject
    while (last.next && last.next.type !== 'SELF') last = last.next
    return { kind: 'property', property: propertiesOf(schema, chain.itemTypeId).find((p) => p.id === last.propertyId) ?? null }
  }
  // A chain ending on a linked item stands on that item's type.
  return chain.path.length > 0 ? { kind: 'item', itemTypeId: chain.itemTypeId } : { kind: 'self' }
}

// A test's valueKind is what its value is: 'text', 'number', 'count' (a whole number), 'date',
// 'dateTime', 'listValue' (from the property's controlled list) or 'state' (one of the machine's).
const NUMBER_OPERATORS = [
  { operator: 'EQUALS', label: 'equals', symbol: '=' },
  { operator: 'NOT_EQUALS', label: 'does not equal', symbol: '≠' },
  { operator: 'GREATER_THAN', label: 'is greater than', symbol: '>' },
  { operator: 'LESS_THAN', label: 'is less than', symbol: '<' },
  { operator: 'GREATER_THAN_OR_EQUAL', label: 'is at least', symbol: '≥' },
  { operator: 'LESS_THAN_OR_EQUAL', label: 'is at most', symbol: '≤' },
  { operator: 'BETWEEN', label: 'is between', symbol: 'between' },
]
const DATE_OPERATORS = [
  { operator: 'EQUALS', label: 'is on', symbol: 'on' },
  { operator: 'NOT_EQUALS', label: 'is not on', symbol: 'not on' },
  { operator: 'BEFORE', label: 'is before', symbol: 'before' },
  { operator: 'AFTER', label: 'is after', symbol: 'after' },
  { operator: 'BETWEEN', label: 'is between', symbol: 'between' },
]
export function operatorsFor(operatorKind) {
  return operatorKind === 'date' ? DATE_OPERATORS : NUMBER_OPERATORS
}

const EXISTS = [
  { id: 'exists', label: 'exists', type: 'EXISTS' },
  { id: 'not-exists', label: 'does not exist', type: 'NOT_EXISTS' },
]

// One test per operator, for a single number or date.
const comparisons = (type, operatorKind, valueKind) =>
  operatorsFor(operatorKind).map(({ operator, label, symbol }) => ({ id: `${type}-${operator}`, label, symbol, type, operator, valueKind }))

// One test per list aggregate, its operator chosen alongside.
const aggregate = (type, label, operatorKind, valueKind) => ({ id: type, label, symbol: label, type, operatorKind, valueKind })

function propertyTests(property) {
  const list = property.cardinality === 'LIST' || property.cardinality === 'SET'
  const dateKind = property.type === 'DATETIME' ? 'dateTime' : 'date'
  const textKind = property.controlledListId ? 'listValue' : 'text'
  switch (property.type) {
    case 'STRING':
      return list
        ? [
            { id: 'includes', label: 'includes', symbol: 'includes', type: 'STRING_LIST_INCLUDES', valueKind: textKind },
            { id: 'excludes', label: 'does not include', symbol: 'excludes', type: 'STRING_LIST_EXCLUDES', valueKind: textKind },
            aggregate('STRING_LIST_SIZE', 'number of entries', 'number', 'count'),
          ]
        : [
            { id: 'equals', label: 'equals', symbol: '=', type: 'STRING_EQUALS', valueKind: textKind },
            { id: 'not-equals', label: 'does not equal', symbol: '≠', type: 'STRING_NOT_EQUALS', valueKind: textKind },
            { id: 'starts-with', label: 'starts with', symbol: 'starts with', type: 'STRING_STARTS_WITH', valueKind: 'text', field: 'prefix' },
            { id: 'like', label: 'matches pattern', symbol: 'like', type: 'STRING_LIKE', valueKind: 'text', field: 'pattern' },
          ]
    case 'INTEGER':
    case 'DECIMAL':
      return list
        ? [
            aggregate('NUMBER_LIST_SIZE', 'number of entries', 'number', 'count'),
            aggregate('NUMBER_LIST_SUM', 'sum', 'number', 'number'),
            aggregate('NUMBER_LIST_AVG', 'average', 'number', 'number'),
            aggregate('NUMBER_LIST_MIN', 'smallest', 'number', 'number'),
            aggregate('NUMBER_LIST_MAX', 'largest', 'number', 'number'),
          ]
        : comparisons('NUMBER', 'number', 'number')
    case 'DATE':
    case 'DATETIME':
      return list
        ? [
            aggregate('DATE_LIST_SIZE', 'number of entries', 'number', 'count'),
            aggregate('DATE_LIST_MIN', 'earliest', 'date', dateKind),
            aggregate('DATE_LIST_MAX', 'latest', 'date', dateKind),
          ]
        : comparisons('DATE', 'date', dateKind)
    case 'BOOLEAN':
      return [
        { id: 'true', label: 'is true', type: 'IS_TRUE' },
        { id: 'false', label: 'is false', type: 'IS_FALSE' },
      ]
    default:
      return []
  }
}

// Every test a subject can have, "exists" / "does not exist" first. None for the item itself.
export function testsFor(end, rootItemTypeId) {
  switch (end.kind) {
    case 'property':
      return end.property ? [...EXISTS, ...propertyTests(end.property)] : []
    case 'stateMachine':
      // Whether the item is in the machine at all (a state is present or absent), or in which state.
      return [
        { id: 'exists', label: 'is present', type: 'EXISTS' },
        { id: 'not-exists', label: 'is absent', type: 'NOT_EXISTS' },
        { id: 'in-state', label: 'is', type: 'STATE_EQUALS', valueKind: 'state', field: 'stateId' },
      ]
    case 'item':
      // "Is the item itself" only means something for a link back to the same item type.
      return end.itemTypeId === rootItemTypeId ? [...EXISTS, { id: 'self', label: 'is the item itself', type: 'EQUALS_SELF' }] : EXISTS
    default:
      return []
  }
}

// The test a predicate is, among tests; null when it's none of them.
export function testOf(predicate, tests) {
  if (!predicate) return null
  return tests.find((t) => t.type === predicate.type && (!t.operator || t.operator === predicate.operator)) ?? null
}

// The field a test keeps its value in (a second, `valueTo`, for "between").
export function valueField(test) {
  return test.field ?? 'value'
}

// The operator a predicate compares with: fixed by its test, or its own.
export function operatorOf(test, predicate) {
  return test.operator ?? predicate?.operator ?? null
}

// A predicate for test, keeping what it can of the one it replaces: its value, and its operator when
// the new test lets the operator be chosen.
export function predicateFor(test, previous) {
  const predicate = previous?.subject ? { type: test.type, subject: previous.subject } : { type: test.type }
  if (test.valueKind) {
    const previousValue = previous?.value ?? previous?.prefix ?? previous?.pattern ?? previous?.stateId ?? null
    predicate[valueField(test)] = sameValueKind(test, previous) ? previousValue : null
  }
  if (test.operator) predicate.operator = test.operator
  else if (test.operatorKind) {
    const keep = previous?.operator && operatorsFor(test.operatorKind).some((o) => o.operator === previous.operator)
    predicate.operator = keep ? previous.operator : operatorsFor(test.operatorKind)[0].operator
  }
  if (predicate.operator === 'BETWEEN') predicate.valueTo = sameValueKind(test, previous) ? previous?.valueTo ?? null : null
  return predicate
}

// Whether a value carries over between two tests: text to text, a number to a number, and so on.
function sameValueKind(test, previous) {
  if (!previous?.type) return false
  const kindOf = (p) => (['NUMBER', 'NUMBER_LIST_SUM', 'NUMBER_LIST_AVG', 'NUMBER_LIST_MIN', 'NUMBER_LIST_MAX'].includes(p.type) ? 'number'
    : p.type.endsWith('_SIZE') ? 'count'
    : ['DATE', 'DATE_LIST_MIN', 'DATE_LIST_MAX'].includes(p.type) ? 'date'
    : p.type === 'STATE_EQUALS' ? 'state'
    : 'text')
  const kind = { dateTime: 'date', listValue: 'text' }[test.valueKind] ?? test.valueKind
  return kind === kindOf(previous)
}

// What's missing from a test's values, if anything.
export function testValueProblem(test, predicate) {
  if (!test.valueKind) return null
  const missing = (v) => v === null || v === undefined || v === '' || Number.isNaN(v)
  if (missing(predicate[valueField(test)])) return 'Give it a value to test against.'
  if (operatorOf(test, predicate) === 'BETWEEN' && missing(predicate.valueTo)) return 'Give it both ends of the range.'
  return null
}

// A predicate's short text, as a condition block shows it: "= Clubs", "> 5", "between 1 and 9",
// "sum > 5", "exists". stateName names a state by id. onMachine: it tests a state machine, whose state
// is present, absent, or a particular one ("is Applied").
export function describeTest(predicate, stateName = (id) => id, onMachine = false) {
  if (!predicate) return ''
  switch (predicate.type) {
    case 'EXISTS':
      return onMachine ? 'is present' : 'exists'
    case 'NOT_EXISTS':
      return onMachine ? 'is absent' : 'does not exist'
    case 'IS_TRUE':
      return 'is true'
    case 'IS_FALSE':
      return 'is false'
    case 'EQUALS_SELF':
      return 'is the item itself'
    case 'STATE_EQUALS':
      return `is ${stateName(predicate.stateId) ?? '…'}`
    case 'STRING_EQUALS':
      return `= ${predicate.value ?? '…'}`
    case 'STRING_NOT_EQUALS':
      return `≠ ${predicate.value ?? '…'}`
    case 'STRING_STARTS_WITH':
      return `starts with ${predicate.prefix ?? '…'}`
    case 'STRING_LIKE':
      return `like ${predicate.pattern ?? '…'}`
    case 'STRING_LIST_INCLUDES':
      return `includes ${predicate.value ?? '…'}`
    case 'STRING_LIST_EXCLUDES':
      return `excludes ${predicate.value ?? '…'}`
    default:
      break
  }
  if (!predicate.operator) return (predicate.type ?? '').toLowerCase().replaceAll('_', ' ')
  const dated = predicate.type.startsWith('DATE') && !predicate.type.endsWith('_SIZE')
  const operator = operatorsFor(dated ? 'date' : 'number').find((o) => o.operator === predicate.operator)
  const show = (v) => (v === null || v === undefined || v === '' ? '…' : dated ? formatInstant(v) : v)
  const comparison = predicate.operator === 'BETWEEN'
    ? `between ${show(predicate.value)} and ${show(predicate.valueTo)}`
    : `${operator?.symbol ?? predicate.operator} ${show(predicate.value)}`
  const aggregateLabel = { _SIZE: 'entries', _SUM: 'sum', _AVG: 'average', _MIN: 'min', _MAX: 'max' }
  const suffix = Object.keys(aggregateLabel).find((s) => predicate.type.endsWith(s) && predicate.type.includes('_LIST_'))
  return suffix ? `${aggregateLabel[suffix]} ${comparison}` : comparison
}

// A predicate's date (an instant) as the admin wrote it: a date at midnight UTC as just the date.
export function formatInstant(value) {
  const text = String(value)
  return text.endsWith('T00:00:00Z') ? text.slice(0, 10) : text.replace('T', ' ').replace(/:00Z$|Z$/, '')
}
