import DiagramInspector, { InspectorField } from '../../../../controls/diagram/DiagramInspector'

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

const MARKER_BLOCKS = {
  ADD_MARKERS: { kind: 'Add markers', what: 'Adds' },
  REMOVE_MARKERS: { kind: 'Remove markers', what: 'Removes' },
  SET_MARKERS: { kind: 'Set markers', what: 'Sets the markers to' },
}

function BlockDetails({ block, label, pinDescriptions }) {
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
        {label.propertyLabel && <InspectorField name="Chooses by" value={label.propertyLabel} />}
        {block.adds.map((row, i) => (
          <InspectorField key={row.id} name={describeGate(row.gate, pinDescriptions)} value={label.rows[i].markerNames.join(', ')} />
        ))}
        <InspectorField name="Otherwise" value="Passes items on unchanged" />
      </>
    )
  }
  const { kind, what } = MARKER_BLOCKS[block.type] ?? { kind: block.type, what: 'Markers' }
  return (
    <>
      <p className="diagram-inspector-kind">{kind}</p>
      <InspectorField name={what} value={label.markerNames.join(', ')} />
      <InspectorField name="When" value={describeGate(block.gate, pinDescriptions)} />
    </>
  )
}

function SignalDetails({ signal, pinDescriptions }) {
  const conditions = signal.type === 'CONDITION_GROUP' ? signal.conditions : [signal]
  return (
    <>
      <p className="diagram-inspector-kind">{signal.type === 'CONDITION_GROUP' ? 'Condition group' : 'Condition'}</p>
      {conditions.map((c, i) => (
        <InspectorField key={c.id} name={conditions.length > 1 ? `Condition ${i + 1}` : 'Holds when'} value={pinDescriptions.get(c.id)} />
      ))}
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

// The selected node's details, in the pipeline editor's Details panel -- read-only -- and what it
// still needs before it can be saved. vm: the editor's view model (useMarkerPipelineViewModel).
function MarkerPipelineInspector({ pipeline, vm, panel }) {
  const nodeId = vm.selection?.kind === 'node' ? vm.selection.id : null
  let details = null
  if (nodeId === pipeline.start.id) details = <PseudoDetails kind="START" />
  else if (nodeId === pipeline.end.id) details = <PseudoDetails kind="END" />
  else if (nodeId) {
    const block = pipeline.carrierBlocks.find((b) => b.id === nodeId)
    const signalNode = vm.layout.signalNodes.find((n) => n.id === nodeId)
    if (block) details = <BlockDetails block={block} label={vm.blocks.get(block.id)} pinDescriptions={vm.pinDescriptions} />
    else if (signalNode) details = <SignalDetails signal={signalNode.signal} pinDescriptions={vm.pinDescriptions} />
  }
  const problems = (nodeId && vm.problems.get(nodeId)) ?? []
  return (
    <DiagramInspector panel={panel} emptyText="Select a block to see its details.">
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
