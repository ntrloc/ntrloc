import FieldLabel from '../../../../controls/field-label/FieldLabel'
import OptionList from '../../../../controls/option-list/OptionList'
import PopoverButton from '../../../../controls/popover-button/PopoverButton'
import { findItemType, linksOf, propertiesOf, stateMachinesOf } from './pipelineSchema'
import { subjectFromSteps, subjectSteps } from './pipelineDraft'
import './SubjectPicker.css'

const ITSELF = 'itself'

// What a condition reads, as a chain from the pipeline's own item type: one dropdown per item type the
// subject passes through, each choosing its next step -- that item ("the Cover"), one of its properties, an
// item it's linked to (which adds a row for that item's type), or its state in one of its state
// machines. Offers only what the evaluator can read: no object-typed properties (nothing steps into
// them yet), no links to traits; and, outside a counting condition, only links to at most one item
// (a plain condition reads one value). A counting condition steps through a link to several items
// once for each.
//
// subject: a Context, or null for the item itself. counting: it's a counting condition's subject.
// name: its label ("Subject", or "Reads" for a test's own). itselfAllowed: whether the pipeline's own
// item is a choice -- not for what a test reads, which is something of the item's.
function SubjectPicker({ name = 'Subject', schema, itemTypeId, subject, counting, itselfAllowed = true, onChange }) {
  const steps = subjectSteps(subject)
  // The item type each row stands on: the pipeline's, then each linked item's.
  const rows = [itemTypeId]
  for (const step of steps) {
    if (step.type !== 'STEP_TO_ITEM') break
    rows.push(linksOf(schema, rows.at(-1)).find((l) => l.id === step.linkPerspectiveId)?.targetItemTypeId ?? null)
  }

  function choose(row, value) {
    const kept = steps.slice(0, row)
    if (value === ITSELF) return onChange(kept.length === 0 ? null : subjectFromSteps(kept))
    const [kind, id] = value.split(':')
    const rowItemType = rows[row]
    let step
    if (kind === 'property') step = { type: 'STEP_TO_PROPERTY', propertyId: id }
    else if (kind === 'state') step = { type: 'STEP_TO_STATE_MACHINE', stateMachineId: id }
    else step = { type: 'STEP_TO_ITEM', linkPerspectiveId: id, each: linksOf(schema, rowItemType).find((l) => l.id === id)?.toMany ?? false }
    onChange(subjectFromSteps([...kept, step]))
  }

  return (
    <subject-picker>
      <FieldLabel>{name}</FieldLabel>
      {rows.map((rowItemType, row) => {
        const all = optionsFor(schema, rowItemType, counting)
        const options = row === 0 && !itselfAllowed ? all.filter((o) => o.value !== ITSELF) : all
        const step = steps[row]
        const value = step ? valueOf(step) : ITSELF
        return (
          <subject-picker-row key={steps.slice(0, row).map(valueOf).join('/') || 'start'}>
            <PopoverButton summary={options.find((o) => o.value === value)?.label ?? (value === ITSELF ? 'Choose…' : 'Unknown')} disabled={!rowItemType}>
              {({ close }) => (
                <OptionList
                  options={options}
                  value={value}
                  onChange={(v) => {
                    choose(row, v)
                    close()
                  }}
                />
              )}
            </PopoverButton>
          </subject-picker-row>
        )
      })}
    </subject-picker>
  )
}

function valueOf(step) {
  if (step.type === 'STEP_TO_PROPERTY') return `property:${step.propertyId}`
  if (step.type === 'STEP_TO_STATE_MACHINE') return `state:${step.stateMachineId}`
  return `link:${step.linkPerspectiveId}`
}

function optionsFor(schema, itemTypeId, counting) {
  const byName = (a, b) => a.label.localeCompare(b.label)
  return [
    { value: ITSELF, label: `the ${findItemType(schema, itemTypeId)?.name ?? 'item'}` },
    ...propertiesOf(schema, itemTypeId)
      .filter((p) => p.type !== 'OBJECT')
      .map((p) => ({ value: `property:${p.id}`, label: `Property: ${p.name}` }))
      .sort(byName),
    ...linksOf(schema, itemTypeId)
      .filter((l) => l.targetItemTypeId && (counting || !l.toMany))
      .map((l) => ({ value: `link:${l.id}`, label: `Linked ${l.toMany ? 'items, for each' : 'item'}: ${l.name} (${l.targetName})` }))
      .sort(byName),
    ...stateMachinesOf(schema, itemTypeId)
      .map((m) => ({ value: `state:${m.id}`, label: `State of: ${m.name}` }))
      .sort(byName),
  ]
}

export default SubjectPicker
