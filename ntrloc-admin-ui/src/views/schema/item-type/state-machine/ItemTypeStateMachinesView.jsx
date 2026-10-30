import { useState } from 'react'
import CollapsibleCard from '../../../../controls/collapsible-card/CollapsibleCard'
import Button from '../../../../controls/button/Button'
import StateMachineDiagramView from './StateMachineDiagramView'
import StateMachineEditDialog from './StateMachineEditDialog'
import { useItemTypeStateMachinesViewModel } from './ItemTypeStateMachinesViewModel'
import './ItemTypeStateMachinesView.css'

// One collapsible card per machine, titled by its name, like the Structure tab's.
function ItemTypeStateMachinesView({ itemTypeId, collapsed, onToggleCollapsed, onNew }) {
  const { machines } = useItemTypeStateMachinesViewModel(itemTypeId)
  const [editingId, setEditingId] = useState(null)
  const editing = machines.find((machine) => machine.id === editingId)

  return (
    <item-type-state-machines>
      <item-type-state-machines-toolbar>
        <Button variant="ghost-secondary" icon="plus" onClick={onNew}>
          New state machine
        </Button>
      </item-type-state-machines-toolbar>
      {machines.length === 0 && <p>No state machines yet.</p>}
      {machines.map((machine) => {
        const key = `state-machine-${machine.id}`
        return (
          <CollapsibleCard
            key={machine.id}
            title={machine.name}
            expanded={!collapsed.has(key)}
            onToggle={() => onToggleCollapsed(key)}
            actions={
              <Button variant="ghost-secondary" icon="edit" onClick={() => setEditingId(machine.id)}>
                Edit
              </Button>
            }
          >
            {machine.description && <p className="item-type-state-machine-description">{machine.description}</p>}
            <StateMachineDiagramView machine={machine} />
          </CollapsibleCard>
        )
      })}
      {editing && <StateMachineEditDialog machine={editing} onClose={() => setEditingId(null)} />}
    </item-type-state-machines>
  )
}

export default ItemTypeStateMachinesView
