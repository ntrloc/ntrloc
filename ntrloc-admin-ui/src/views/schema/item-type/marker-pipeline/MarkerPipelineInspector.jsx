import Switch from '../../../../controls/switch/Switch'
import Button from '../../../../controls/button/Button'
import IconButton from '../../../../controls/icon-button/IconButton'
import DiagramInspector, { InspectorField } from '../../../../controls/diagram/DiagramInspector'
import CheckboxOptionList from '../../../../controls/option-list/CheckboxOptionList'
import PopoverButton from '../../../../controls/popover-button/PopoverButton'
import { nodeIdOf, subjectOf } from './pipelineDraft'
import SubjectPicker from './SubjectPicker'
import TestPicker from './TestPicker'
import { subjectEnd } from './pipelineTests'

const QUANTITY_JOIN = { ALL: ' and ', ANY: ' or ' }

// A gate in words: "Always" with no conditions, otherwise its conditions joined by how many of them
// have to hold.
function describeGate(gate, pinDescriptions) {
  const pins = gate?.conditionOutputPinIds ?? []
  if (pins.length === 0) return 'Always'
  const texts = pins.map((pin) => pinDescriptions.get(pin) ?? 'an unknown condition')
  if (gate.quantity === 'NONE') return texts.length === 1 ? `Not ${texts[0]}` : `None of: ${texts.join('; ')}`
  return texts.join(QUANTITY_JOIN[gate.quantity] ?? ' and ')
}

// The blocks that act on markers: what each is called, and what its markers field says it does.
const MARKER_BLOCKS = {
  ADD_MARKERS: { kind: 'Add markers', what: 'Adds' },
  REMOVE_MARKERS: { kind: 'Remove markers', what: 'Removes' },
  SET_MARKERS: { kind: 'Set markers', what: 'Sets' },
}

// Which of the item type's markers a block (or a Select's test) adds, removes or sets: chosen from a
// checklist.
function MarkerChoice({ name, markerIds, onChange, vm }) {
  const chosen = new Set(markerIds)
  const summary = vm.markerOptions.filter((o) => chosen.has(o.value)).map((o) => o.label).join(', ') || 'None'
  return (
    <PopoverButton name={name} summary={summary}>
      <CheckboxOptionList options={vm.markerOptions} selectedValues={markerIds} onChange={onChange} />
    </PopoverButton>
  )
}

function BlockDetails({ block, label, pinDescriptions, vm }) {
  const markerBlock = MARKER_BLOCKS[block.type]
  if (markerBlock) {
    return (
      <>
        <p className="diagram-inspector-kind">{markerBlock.kind}</p>
        <MarkerChoice name={markerBlock.what} markerIds={block.markerIds} onChange={(ids) => vm.setMarkers(block.id, ids)} vm={vm} />
        {/* Conditional: it applies only when the conditions connected to its "if" hold; otherwise
            items pass through it unchanged. */}
        <Switch name="Conditional" on={label.conditional} onChange={(on) => vm.setConditional(block.id, on)} />
      </>
    )
  }
  if (block.type === 'FILTER') {
    return (
      <>
        <p className="diagram-inspector-kind">Filter</p>
        <InspectorField name="Lets items through when" value={describeGate(block.signal, pinDescriptions)} />
      </>
    )
  }
  if (block.type === 'SELECT' && vm.selectGroupOf(block.id)) return <SelectDetails select={block} group={vm.selectGroupOf(block.id)} vm={vm} />
  if (block.type === 'SELECT') {
    // One whose tests aren't its own (read from conditions elsewhere too): shown, not edited.
    return (
      <>
        <p className="diagram-inspector-kind">Select</p>
        {label.subjectLabel && <InspectorField name="Chooses by" value={label.subjectLabel} />}
        {block.adds.map((row, i) => (
          <InspectorField key={row.id} name={describeGate(row.gate, pinDescriptions)} value={label.rows[i].markerNames.join(', ')} />
        ))}
        <InspectorField name="Otherwise" value="Passes items on unchanged" />
      </>
    )
  }
  return <p className="diagram-inspector-kind">{block.type}</p>
}

// What a condition (or group) is called, by what it does.
function conditionKind(signal) {
  if (signal.type === 'CONDITION_GROUP') return 'Condition group'
  if (signal.predicate?.type === 'COUNTED') return 'Counting condition'
  if (signal.predicate?.type === 'TOTALITY') return 'All / any / none of'
  return 'Condition'
}

// A Select: the subject its tests read, and each test with the markers it adds -- every test that
// holds adds its markers, on its own row; when none does, Otherwise passes items on unchanged.
function SelectDetails({ select, group, vm }) {
  const conditionOf = (row) => group.conditions.find((c) => c.id === row.gate?.conditionOutputPinIds?.[0])
  return (
    <>
      <p className="diagram-inspector-kind">Select</p>
      <SubjectPicker
        schema={vm.schema}
        itemTypeId={vm.itemTypeId}
        subject={group.subject}
        counting={false}
        onChange={(subject) => vm.setConditionSubject(nodeIdOf({ signal: group }), subject)}
      />
      {select.adds.map((row, i) => {
        const condition = conditionOf(row)
        if (!condition) return null
        return (
          <ConditionEditor
            key={row.id}
            signal={group}
            condition={condition}
            heading={`Test ${i + 1}`}
            onRemove={select.adds.length > 1 ? () => vm.removeTestFromSelect(select.id, row.id) : null}
            vm={vm}
          >
            <MarkerChoice name="Adds" markerIds={row.markerIds} onChange={(ids) => vm.setRowMarkers(row.id, ids)} vm={vm} />
          </ConditionEditor>
        )
      })}
      <Button variant="ghost-secondary" icon="plus" onClick={() => vm.addTestToSelect(select.id)}>
        Add test
      </Button>
      <InspectorField name="Otherwise" value="Passes items on unchanged" />
    </>
  )
}

// One condition's test, in a condition (or group, or Select): what it reads, under the item itself
// (one of the item's properties, a linked item, a state), and the test. heading/onRemove: a group's
// or Select's numbering, and its remove button (none when it's the last). children: more of it, after.
function ConditionEditor({ signal, condition, heading, onRemove, vm, children }) {
  const itself = !subjectOf(signal) || subjectOf(signal).type === 'SELF'
  return (
    <condition-test>
      {heading && (
        <condition-test-header>
          <span>{heading}</span>
          {onRemove && <IconButton icon="trash" label={`Remove ${heading.toLowerCase()}`} onClick={onRemove} />}
        </condition-test-header>
      )}
      {itself && (
        <SubjectPicker
          name="Reads"
          schema={vm.schema}
          itemTypeId={vm.itemTypeId}
          subject={condition.predicate?.subject ?? null}
          counting={false}
          itselfAllowed={false}
          onChange={(subject) => vm.setTestSubject(signal, condition, subject)}
        />
      )}
      <TestPicker
        name="Test"
        tests={vm.testsOf(signal, condition)}
        end={subjectEnd(vm.schema, vm.itemTypeId, vm.testSubjectOf(signal, condition))}
        predicate={condition.predicate}
        onChange={(predicate, key) => vm.setConditionTest(condition.id, predicate, key && `${condition.id}-${key}`)}
      />
      {children}
    </condition-test>
  )
}

function SignalDetails({ signal, nodeId, pinDescriptions, vm }) {
  const isGroup = signal.type === 'CONDITION_GROUP'
  const conditions = isGroup ? signal.conditions : [signal]
  return (
    <>
      <p className="diagram-inspector-kind">{conditionKind(signal)}</p>
      <SubjectPicker
        schema={vm.schema}
        itemTypeId={vm.itemTypeId}
        subject={subjectOf(signal)}
        counting={signal.predicate?.type === 'COUNTED'}
        onChange={(subject) => vm.setConditionSubject(nodeId, subject)}
      />
      {conditions.map((c, i) =>
        // Counting and all/any/none tests aren't editable yet.
        c.predicate?.type === 'COUNTED' || c.predicate?.type === 'TOTALITY' ? (
          <InspectorField key={c.id} name="Test" value={pinDescriptions.get(c.id)} />
        ) : (
          <ConditionEditor
            key={c.id}
            signal={signal}
            condition={c}
            heading={isGroup ? `Condition ${i + 1}` : null}
            onRemove={isGroup && conditions.length > 1 ? () => vm.removeConditionFromGroup(nodeId, c.id) : null}
            vm={vm}
          />
        ),
      )}
      {isGroup && (
        <Button variant="ghost-secondary" icon="plus" onClick={() => vm.addConditionToGroup(nodeId)}>
          Add condition
        </Button>
      )}
    </>
  )
}

function PseudoDetails({ kind }) {
  return (
    <>
      <p className="diagram-inspector-kind">{kind === 'START' ? 'Start' : 'End'}</p>
      <p className="diagram-inspector-note">
        {kind === 'START'
          ? 'Where an item begins its way through the decision, with no markers.'
          : 'The markers an item arrives here with are the ones it gets.'}
      </p>
    </>
  )
}

// What a carrier pin (or gate) belongs to, in words: Start, End, a block's kind, or a <select> row's
// condition.
function pinOwnerName(pipeline, vm, id) {
  if (id === pipeline.start.id) return 'Start'
  if (id === pipeline.end.id) return 'End'
  const block = pipeline.carrierBlocks.find((b) => b.id === id)
  if (block) return vm.blocks.get(block.id)?.title ?? block.type
  for (const select of pipeline.carrierBlocks.filter((b) => b.type === 'SELECT')) {
    const row = select.adds.find((r) => r.id === id)
    if (row) return `Select row: ${describeGate(row.gate, vm.pinDescriptions)}`
  }
  return 'Unknown'
}

function ConnectionDetails({ edge, pipeline, vm }) {
  if (edge.outputPinId) {
    return (
      <>
        <p className="diagram-inspector-kind">Carrier connection</p>
        <InspectorField name="From" value={pinOwnerName(pipeline, vm, edge.outputPinId)} />
        <InspectorField name="To" value={pinOwnerName(pipeline, vm, edge.targetId)} />
      </>
    )
  }
  return (
    <>
      <p className="diagram-inspector-kind">Condition connection</p>
      <InspectorField name="Condition" value={vm.pinDescriptions.get(edge.pinId)} />
      <InspectorField name="Read by" value={pinOwnerName(pipeline, vm, edge.ownerId)} />
    </>
  )
}

// The selected node's (or connection's) details, in the pipeline editor's Details panel -- an Add,
// Remove or Set block's markers can be chosen; the rest is read-only for now -- and what it still needs before it can be saved. vm: the editor's view model (useMarkerPipelineViewModel).
function MarkerPipelineInspector({ pipeline, vm, panel }) {
  const nodeId = vm.selection?.kind === 'node' ? vm.selection.id : null
  let details = null
  if (nodeId === pipeline.start.id) details = <PseudoDetails kind="START" />
  else if (nodeId === pipeline.end.id) details = <PseudoDetails kind="END" />
  else if (nodeId) {
    const block = pipeline.carrierBlocks.find((b) => b.id === nodeId)
    const signalNode = vm.layout.signalNodes.find((n) => n.id === nodeId)
    if (block) details = <BlockDetails block={block} label={vm.blocks.get(block.id)} pinDescriptions={vm.pinDescriptions} vm={vm} />
    else if (signalNode) details = <SignalDetails signal={signalNode.signal} nodeId={nodeId} pinDescriptions={vm.pinDescriptions} vm={vm} />
  }
  if (vm.selection?.kind === 'edge') {
    const edge = [...vm.layout.edges, ...vm.layout.signalEdges].find((e) => e.key === vm.selection.id)
    if (edge) details = <ConnectionDetails edge={edge} pipeline={pipeline} vm={vm} />
  }
  const problems = (nodeId && vm.problems.get(nodeId)) ?? []
  return (
    <DiagramInspector panel={panel} emptyText="Select a block or connection to see its details.">
      {details && (
        <>
          {details}
          {problems.map((problem) => (
            <p key={problem} className="diagram-inspector-problem">
              {problem}
            </p>
          ))}
        </>
      )}
    </DiagramInspector>
  )
}

export default MarkerPipelineInspector
