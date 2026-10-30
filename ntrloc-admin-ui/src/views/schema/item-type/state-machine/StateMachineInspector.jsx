import CollapsiblePanel from '../../../../controls/collapsible-panel/CollapsiblePanel'
import Input from '../../../../controls/input/Input'
import './StateMachineInspector.css'

const NONE = '—'

function stateName(state) {
  if (state.kind === 'START') return 'Start'
  if (state.kind === 'END') return 'End'
  return state.name
}

function Detail({ name, value }) {
  return <Input name={name} editable={false} value={value || NONE} />
}

// A name and description being edited, with what's wrong with the name under it.
function EditableFields({ thing, fieldsOf, onEdit, problem }) {
  const fields = fieldsOf(thing)
  return (
    <>
      <div>
        <Input name="Name" value={fields.name} onChange={(e) => onEdit(thing, 'name', e.target.value)} />
        {problem && <p className="sm-inspector-problem">{problem}</p>}
      </div>
      <Input name="Description" value={fields.description} onChange={(e) => onEdit(thing, 'description', e.target.value)} />
    </>
  )
}

function StateDetails({ state, editing }) {
  if (state.kind !== 'NORMAL') {
    return (
      <>
        <Detail name="State" value={stateName(state)} />
        <p className="sm-inspector-note">
          {state.kind === 'START' ? 'Where every run of the machine begins.' : 'Where a run of the machine ends.'}
        </p>
      </>
    )
  }
  return (
    <>
      <p className="sm-inspector-kind">State</p>
      <EditableFields thing={state} {...editing} />
      <Detail name="Entry process" value={state.entryProcessId} />
      <Detail name="Exit process" value={state.exitProcessId} />
      <Detail name="Entry marker decision" value={state.entryMarkerDecisionKey} />
    </>
  )
}

function TransitionDetails({ transition, editing }) {
  return (
    <>
      <p className="sm-inspector-kind">Transition</p>
      <EditableFields thing={transition} {...editing} />
      <Detail name="Process" value={transition.processId} />
      <Detail name="Guard" value={transition.guardCondition ? 'Has a condition' : null} />
    </>
  )
}

// The selected state's or transition's details, in a panel on the right of the editor that
// collapses like the nav sidebar. Its name and description can be edited: fieldsOf(thing) gives them
// as edited, onEdit(thing, field, value) changes one, and problems has what's wrong with a name, by
// id. The rest is read-only for now.
function StateMachineInspector({ machine, selection, panel, fieldsOf, onEdit, problems }) {
  const statesById = new Map(machine.states.map((s) => [s.id, s]))
  const editing = (thing) => ({ fieldsOf, onEdit, problem: problems.get(thing.id) })
  let details = <p className="sm-inspector-note">Select a state or transition to see its details.</p>
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
    <CollapsiblePanel
      className="sm-inspector"
      side="right"
      title="Details"
      locked={panel.locked}
      collapsed={panel.collapsed}
      floating={panel.floating}
      onToggleLocked={panel.toggleLocked}
      onMouseEnter={panel.onMouseEnter}
      onMouseLeave={panel.onMouseLeave}
    >
      <sm-inspector-details>{details}</sm-inspector-details>
    </CollapsiblePanel>
  )
}

export default StateMachineInspector
