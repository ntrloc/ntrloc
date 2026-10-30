import DiagramCanvas from '../../../../controls/diagram/DiagramCanvas'
import { useDiagramView } from '../../../../controls/diagram/useDiagramView'
import StateMachineShapes from './StateMachineShapes'
import { layoutStateMachine, savedLayout } from './stateMachineDiagram'

// A machine's diagram as it was saved, read-only, fitted to the width it's given.
function StateMachineDiagramView({ machine }) {
  const { positions, edgeLayouts } = savedLayout(machine)
  const layout = layoutStateMachine(machine, positions, edgeLayouts)
  const canvas = useDiagramView(layout.bounds)
  return (
    <DiagramCanvas canvas={canvas} className="sm-diagram" label={`${machine.name} state machine`}>
      <StateMachineShapes machine={machine} layout={layout} />
    </DiagramCanvas>
  )
}

export default StateMachineDiagramView
