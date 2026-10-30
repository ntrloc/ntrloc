import DiagramInspector, { InspectorField } from '../../../../controls/diagram/DiagramInspector'
import Input from '../../../../controls/input/Input'

function stateName(state) {
  if (state.kind === 'START') return 'Start'
  if (state.kind === 'END') return 'End'
  return state.name
}

// A name and description being edited, with what's wrong with the name under it.
function EditableFields({ thing, fieldsOf, onEdit, problem }) {
  const fields = fieldsOf(thing)
  return (
    <>
      <div>
        <Input name="Name" value={fields.name} onChange={(e) => onEdit(thing, 'name', e.target.value)} />
        {problem && <p className="diagram-inspector-problem">{problem}</p>}
      </div>
      <Input name="Description" value={fields.description} onChange={(e) => onEdit(thing, 'description', e.target.value)} />
    </>
  )
}

function StateDetails({ state, editing }) {
  if (state.kind !== 'NORMAL') {
    return (
      <>
        <InspectorField name="State" value={stateName(state)} />
        <p className="diagram-inspector-note">
          {state.kind === 'START' ? 'Where every run of the machine begins.' : 'Where a run of the machine ends.'}
        </p>
      </>
    )
  }
  return (
    <>
      <p className="diagram-inspector-kind">State</p>
      <EditableFields thing={state} {...editing} />
      <InspectorField name="Entry process" value={state.entryProcessId} />
      <InspectorField name="Exit process" value={state.exitProcessId} />
    </>
  )
}

function TransitionDetails({ transition, editing }) {
  return (
    <>
      <p className="diagram-inspector-kind">Transition</p>
      <EditableFields thing={transition} {...editing} />
      <InspectorField name="Process" value={transition.processId} />
      <InspectorField name="Guard" value={transition.guardCondition ? 'Has a condition' : null} />
    </>
  )
}

// The selected state's or transition's details, in the editor's Details panel. Its name and description can be edited: fieldsOf(thing) gives them
// as edited, onEdit(thing, field, value) changes one, and problems has what's wrong with a name, by
// id. The rest is read-only for now.
function StateMachineInspector({ machine, selection, panel, fieldsOf, onEdit, problems }) {
  const statesById = new Map(machine.states.map((s) => [s.id, s]))
  const editing = (thing) => ({ fieldsOf, onEdit, problem: problems.get(thing.id) })
  let details = null
  if (selection?.kind === 'state' && statesById.has(selection.id)) {
    const state = statesById.get(selection.id)
    details = <StateDetails key={state.id} state={state} editing={editing(state)} />
  } else if (selection?.kind === 'transition') {
    for (const from of machine.states) {
      const transition = from.transitions.find((t) => t.id === selection.id)
      if (transition) {
        details = <TransitionDetails key={transition.id} transition={transition} editing={editing(transition)} />
        break
      }
    }
  }
  return (
    <DiagramInspector panel={panel} emptyText="Select a state or transition to see its details.">
      {details}
    </DiagramInspector>
  )
}

export default StateMachineInspector
