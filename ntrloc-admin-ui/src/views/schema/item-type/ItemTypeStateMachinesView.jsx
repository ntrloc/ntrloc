import StateMachineDiagramView from '../StateMachineDiagramView'
import { useItemTypeStateMachinesViewModel } from './ItemTypeStateMachinesViewModel'
import './ItemTypeStateMachinesView.css'

// First pass, display only -- one StateMachineDiagramView per machine. Horizontal orientation: this
// tab gets a full-width panel, not the narrow fixed-width side panel a future editor's own diagram
// pane would use (see stateMachineLayout's own comment on why orientation gets its own node sizing).
function ItemTypeStateMachinesView({ itemTypeId }) {
  const { machines } = useItemTypeStateMachinesViewModel(itemTypeId)

  if (machines.length === 0) return <p>No state machines yet.</p>

  return (
    <div className="item-type-state-machines-view">
      {machines.map((machine) => (
        <div key={machine.id} className="item-type-state-machine">
          <h3 className="item-type-state-machine-name">{machine.name}</h3>
          <StateMachineDiagramView machine={machine} orientation="horizontal" />
        </div>
      ))}
    </div>
  )
}

export default ItemTypeStateMachinesView
