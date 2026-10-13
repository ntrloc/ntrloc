// Turns a Condition/ConditionGroup's own subject chain + predicate tree into the short, readable
// text the diagram shows inside a signal block -- the one place in this diagram that *does* surface
// gate/condition detail, deliberately, since a Condition has no admin-authored name of its own the
// way a marker or a block's title does; this is the only way to say what it actually reads. Schema-
// aware (needs property/link/state-machine names by id, crossing item-type boundaries as a subject
// chain follows a link to another type), so this lives here rather than in markerPipelineLayout.js,
// which stays deliberately schema-free pure math.
//
// Pragmatic, not exhaustive: every predicate type gets *some* text (falls back to a humanized type
// name), but only the shapes this pipeline actually uses (STRING_EQUALS/STRING_LIST_INCLUDES/NUMBER/
// STATE_EQUALS/EXISTS, COUNTED, TOTALITY) get real phrasing. `schema` is the raw admin schema
// snapshot from useSchemaSnapshot() -- the one place this diagram reaches past the shaped per-concept
// Models (ItemTypeModel/LinkModel/StateMachineModel), which are each scoped to a single item type and
// can't resolve an id against whatever type a chain has stepped onto by then.

function findItemType(schema, itemTypeId) {
  return schema?.items.find((it) => it.id === itemTypeId) ?? null
}

function resolvePropertyName(schema, itemTypeId, propertyId) {
  const item = findItemType(schema, itemTypeId)
  if (!item) return propertyId
  function walk(container) {
    for (const p of container.properties ?? []) if (p.id === propertyId) return p.name
    for (const g of container.groups ?? []) {
      const found = walk(g)
      if (found) return found
    }
    return null
  }
  return walk(item) ?? propertyId
}

// A link is really two perspectives (one per item type it connects); this item type's own links map
// names this side, but the perspective itself carries only the *target* type's name, not its id --
// resolved here by name against the schema's own item list, same trick LinkModel's own reverse-
// perspective lookup uses.
function resolveLink(schema, itemTypeId, linkPerspectiveId) {
  const item = findItemType(schema, itemTypeId)
  if (!item?.links) return null
  for (const [name, perspectives] of Object.entries(item.links)) {
    const perspective = perspectives.find((p) => p.id === linkPerspectiveId)
    if (perspective) {
      const targetName = perspective.targets?.[0]?.name
      const targetItem = targetName ? schema.items.find((it) => it.name === targetName) : null
      return { name, targetItemTypeId: targetItem?.id ?? null }
    }
  }
  return null
}

function resolveStateMachine(schema, itemTypeId, stateMachineId) {
  const item = findItemType(schema, itemTypeId)
  return item?.stateMachines?.find((m) => m.id === stateMachineId) ?? null
}

function resolveStateName(machine, stateId) {
  return machine?.states?.find((s) => s.id === stateId)?.name ?? stateId
}

// Walks a Context chain (Self | StepToProperty | StepToItem | StepToStateMachine) into a dotted path
// of human names plus what kind of thing it ends on -- a property, a state machine (StepToStateMachine
// is always a terminal step, never carries its own `next`), or nothing at all (bare Self). StepToItem
// is the only step that changes "whose properties/state-machines am I now resolving against" for
// everything past it, since it follows a link onto another item type.
export function describeConditionSubject(schema, itemTypeId, context) {
  if (!context || context.type === 'SELF') {
    return { path: [], itemTypeId, terminal: { kind: 'self' } }
  }
  if (context.type === 'STEP_TO_PROPERTY') {
    const name = resolvePropertyName(schema, itemTypeId, context.propertyId)
    const rest = describeConditionSubject(schema, itemTypeId, context.next)
    if (rest.terminal.kind === 'self') return { path: [name], itemTypeId, terminal: { kind: 'property', name } }
    return { path: [name, ...rest.path], itemTypeId: rest.itemTypeId, terminal: rest.terminal }
  }
  if (context.type === 'STEP_TO_ITEM') {
    const link = resolveLink(schema, itemTypeId, context.linkPerspectiveId)
    const rest = describeConditionSubject(schema, link?.targetItemTypeId ?? null, context.next)
    return { path: [link?.name ?? '?', ...rest.path], itemTypeId: rest.itemTypeId, terminal: rest.terminal }
  }
  if (context.type === 'STEP_TO_STATE_MACHINE') {
    const machine = resolveStateMachine(schema, itemTypeId, context.stateMachineId)
    return { path: [], itemTypeId, terminal: { kind: 'stateMachine', machine } }
  }
  return { path: [], itemTypeId, terminal: { kind: 'unknown' } }
}

function operatorSymbol(operator) {
  switch (operator) {
    case 'GREATER_THAN':
      return '>'
    case 'GREATER_THAN_OR_EQUAL':
      return '>='
    case 'LESS_THAN':
      return '<'
    case 'LESS_THAN_OR_EQUAL':
      return '<='
    case 'EQUALS':
      return '='
    default:
      return operator ?? ''
  }
}

function humanizeType(type) {
  return (type ?? '').toLowerCase().replaceAll('_', ' ')
}

// A leaf predicate's own text, ignoring its subject entirely -- the subject (if any) is shown
// separately, by whatever's walking the subject chain above this leaf (see describeCondition and
// describeConditionShort below), since the same leaf shape shows up both with and without one
// (e.g. a ConditionGroup member's subject is inherited from the group, not its own).
function describeLeafPredicate(predicate) {
  if (!predicate) return ''
  switch (predicate.type) {
    case 'STRING_EQUALS':
      return `= ${predicate.value}`
    case 'STRING_LIST_INCLUDES':
      return `= ${predicate.value}`
    case 'NUMBER':
      return `${operatorSymbol(predicate.operator)} ${predicate.value}`
    case 'IS_TRUE':
      return 'is true'
    case 'IS_FALSE':
      return 'is false'
    case 'STRING_EXISTS':
      return 'exists'
    default:
      return humanizeType(predicate.type)
  }
}

// The count-predicate's own quantifier phrase -- the two shapes an admin actually reaches for
// ("at least one" / "none") get real words; anything else falls back to the bare comparison.
function describeCountQuantifier(countPredicate) {
  if (countPredicate?.type !== 'NUMBER') return describeLeafPredicate(countPredicate)
  const { operator, value } = countPredicate
  if (operator === 'GREATER_THAN' && value === 0) return 'any match'
  if (operator === 'EQUALS' && value === 0) return 'none match'
  return `count ${operatorSymbol(operator)} ${value}`
}

// Every predicate -- leaf or compound, at any depth -- renders as its own bordered box, labeled with
// a fixed, predicate-family label rather than its exact type: 'count' for a CountedPredicate,
// 'totality' for a TotalityPredicate, 'value' for every leaf comparison type alike (there are ~30 of
// those -- STRING_EQUALS, NUMBER, STATE_EQUALS, etc. -- and spelling out which one it is would be
// noise; "value" already says "this is a comparison", which is all an admin needs to read it). A
// box's own `testText` is its own associated comparison/quantifier phrase, when it has one directly --
// every leaf does; CountedPredicate's is its own countPredicate (describeCountQuantifier); TOTALITY's
// is its own ALL/ANY/NONE qualifier -- shown inside this box, never a child's. `children` is this
// predicate's own nested sub-predicate(s) (TOTALITY's subPredicates, CountedPredicate's single
// subPredicate), each its own recursively described box -- CountedPredicate.java's own doc comment
// allows subPredicate to itself be another CountedPredicate or a TotalityPredicate, nested arbitrarily
// deep, so this recurses rather than assuming a child is always a leaf.
function describePredicateBox(schema, itemTypeId, predicate) {
  if (!predicate) return null
  if (predicate.type === 'COUNTED') {
    const chain = describeConditionSubject(schema, itemTypeId, predicate.subject)
    const subjectText = `linked item: ${chain.path.join('.')}`
    const testText = describeCountQuantifier(predicate.countPredicate)
    // The common case in practice: a subPredicate with nothing else to read from a linked item is
    // almost always "what state is it in" (same own-or-inherited subject rule SignalEvaluator.java
    // enforces server-side) -- special-cased for the nicer "<machine> = <state>" phrasing a generic
    // leaf render can't produce (it has no access to the chain's own state-machine terminal).
    if (chain.terminal.kind === 'stateMachine' && predicate.subPredicate?.type === 'STATE_EQUALS') {
      const stateName = resolveStateName(chain.terminal.machine, predicate.subPredicate.stateId)
      const child = { label: 'value', subjectText: null, testText: `${chain.terminal.machine?.name ?? 'state'} = ${stateName}`, children: [] }
      return { label: 'count', subjectText, testText, children: [child] }
    }
    const child = describePredicateBox(schema, chain.itemTypeId, predicate.subPredicate)
    return { label: 'count', subjectText, testText, children: child ? [child] : [] }
  }
  if (predicate.type === 'TOTALITY') {
    const qualifier = predicate.quantity === 'ALL' ? 'all match' : predicate.quantity === 'ANY' ? 'any match' : 'none match'
    const children = (predicate.subPredicates ?? []).map((p) => describePredicateBox(schema, itemTypeId, p)).filter(Boolean)
    return { label: 'totality', subjectText: null, testText: qualifier, children }
  }
  return { label: 'value', subjectText: null, testText: describeLeafPredicate(predicate), children: [] }
}

// The full, standalone rendering of one Condition -- always one recursive predicate box (see
// describePredicateBox), rooted either at the Condition's own leaf predicate (a 'value' box, carrying
// a subject chip only if its chain actually traverses something beyond bare Self) or, for a COUNTED/
// TOTALITY predicate, at that compound predicate's own box.
export function describeCondition(schema, itemTypeId, condition) {
  const predicate = condition.predicate
  if (!predicate) return null
  if (predicate.type === 'COUNTED' || predicate.type === 'TOTALITY') {
    return describePredicateBox(schema, itemTypeId, predicate)
  }
  const effectiveSubject = predicate.subject ?? condition.subject ?? { type: 'SELF' }
  const chain = describeConditionSubject(schema, itemTypeId, effectiveSubject)
  return {
    label: 'value',
    subjectText: chain.path.length ? chain.path.join('.') : null,
    testText: describeLeafPredicate(predicate),
    children: [],
  }
}

// A ConditionGroup's own subject, as the "property: X" header shown once for the whole group (every
// member condition's subject is inherited from this, never its own -- see ConditionGroup.java).
export function describeConditionGroupSubject(schema, itemTypeId, group) {
  const chain = describeConditionSubject(schema, itemTypeId, group.subject)
  return chain.path.join('.')
}

// A single member condition's own short label, the way a ConditionGroup's branch reads inline
// wherever it's consumed (e.g. a <select> row) -- just the leaf predicate's own distinguishing value,
// no subject prefix (the group's own header already said what property this is).
export function describeConditionShort(predicate) {
  return describeLeafPredicate(predicate)
}
