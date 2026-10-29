// How property types, cardinalities and usages read in the UI -- shared by the property table's
// summary column and the property editor's own fields.

export const TYPE_LABELS = {
  STRING: 'String',
  INTEGER: 'Integer',
  DECIMAL: 'Decimal',
  DATE: 'Date',
  DATETIME: 'Date/Time',
  BOOLEAN: 'Boolean',
  BINARY: 'Binary',
}

export const CARDINALITY_LABELS = {
  SINGLE: 'Single value',
  LIST: 'List',
  SET: 'Set',
}

const CARDINALITY_SUFFIXES = {
  SINGLE: '',
  LIST: ' List',
  SET: ' Set',
}

export const USAGE_LABELS = {
  OPTIONAL: 'optional',
  REQUIRED: 'required',
  DEPRECATED: 'deprecated',
}

// The property types a controlled list can back (ControlledListManager.SUPPORTED_TYPES).
export const CONTROLLED_LIST_TYPES = ['STRING', 'INTEGER', 'DECIMAL', 'DATE', 'DATETIME']

// Mirrors PropertyType.validCardinalities on the server.
export function validCardinalities(type) {
  return type === 'BOOLEAN' || type === 'BINARY' ? ['SINGLE'] : ['SINGLE', 'LIST', 'SET']
}

// A single-glance summary in place of separate Type/Cardinality/Usage columns -- a prior granular,
// per-value table (meant to double as an editor) turned out not worth it for a read-only view.
// e.g. "String (required)", "Integer List (optional)".
export function formatPropertyType(property) {
  const type = TYPE_LABELS[property.type] ?? property.type
  const cardinality = CARDINALITY_SUFFIXES[property.cardinality] ?? ''
  const usage = USAGE_LABELS[property.usage] ?? property.usage
  return `${type}${cardinality} (${usage})`
}
