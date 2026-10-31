import Switch from '../../../../controls/switch/Switch'
import DiagramInspector, { InspectorField } from '../../../../controls/diagram/DiagramInspector'
import CheckboxOptionList from '../../../../controls/option-list/CheckboxOptionList'
import PopoverButton from '../../../../controls/popover-button/PopoverButton'
import { subjectOf } from './pipelineDraft'
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

// Which of the item type's markers a block adds, removes or sets: chosen from a checklist.
function MarkerChoice({ name, block, vm }) {
  const chosen = new Set(block.markerIds)
  const summary = vm.markerOptions.filter((o) => chosen.has(o.value)).map((o) => o.label).join(', ') || 'None'
  return (
    <PopoverButton name={name} summary={summary}>
      <CheckboxOptionList options={vm.markerOptions} selectedValues={block.markerIds} onChange={(ids) => vm.setMarkers(block.id, ids)} />
    </PopoverButton>
  )
}

function BlockDetails({ block, label, pinDescriptions, vm }) {
  const markerBlock = MARKER_BLOCKS[block.type]
  if (markerBlock) {
    return (
      <>
        <p className="diagram-inspector-kind">{markerBlock.kind}</p>
        <MarkerChoice name={markerBlock.what} block={block} vm={vm} />
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
  if (block.type === 'SELECT') {
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

function SignalDetails({ signal, nodeId, pinDescriptions, vm }) {
  const conditions = signal.type === 'CONDITION_GROUP' ? signal.conditions : [signal]
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
      {conditions.map((c, i) => {
        const name = conditions.length > 1 ? `Condition ${i + 1}` : 'Test'
        // Counting and all/any/none tests aren't editable yet.
        if (c.predicate?.type === 'COUNTED' || c.predicate?.type === 'TOTALITY') {
          return <InspectorField key={c.id} name={name} value={pinDescriptions.get(c.id)} />
        }
        // Under the item itself, a test reads something of its own: one of the item's properties, a
        // linked item, a state.
        const itself = !subjectOf(signal) || subjectOf(signal).type === 'SELF'
        return (
          <condition-test key={c.id}>
            {itself && (
              <SubjectPicker
                name={conditions.length > 1 ? `${name} reads` : 'Reads'}
                schema={vm.schema}
                itemTypeId={vm.itemTypeId}
                subject={c.predicate?.subject ?? null}
                counting={false}
                itselfAllowed={false}
                onChange={(subject) => vm.setTestSubject(signal, c, subject)}
              />
            )}
            <TestPicker
              name={name}
              tests={vm.testsOf(signal, c)}
              end={subjectEnd(vm.schema, vm.itemTypeId, vm.testSubjectOf(signal, c))}
              predicate={c.predicate}
              onChange={(predicate, key) => vm.setConditionTest(c.id, predicate, key && `${c.id}-${key}`)}
            />
          </condition-test>
        )
      })}
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
