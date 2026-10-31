import Button from '../../../../controls/button/Button'
import DiagramCanvas from '../../../../controls/diagram/DiagramCanvas'
import Dialog from '../../../../controls/dialog/Dialog'
import Input from '../../../../controls/input/Input'
import StateMachineInspector from './StateMachineInspector'
import StateMachineShapes from './StateMachineShapes'
import { useStateMachineEditorViewModel } from './StateMachineEditorViewModel'
import './StateMachineEditDialog.css'

const IGNORE = () => {}

// Editing one state machine: its name and description over the diagram, and the selected state's or
// transition's details beside it. With no changes, Close (or Escape) closes it; with changes, Save
// keeps them and Cancel drops them -- Escape doesn't, so they can't be lost by a stray key. The title
// keeps the saved name until then.
function StateMachineEditDialog({ machine, onClose }) {
  const vm = useStateMachineEditorViewModel(machine, onClose)
  return (
    <Dialog
      title={
        <>
          Edit state machine <strong>{machine.name}</strong>
        </>
      }
      size="large"
      onCancel={vm.hasChanges ? IGNORE : vm.cancel}
      actions={
        <>
          <Button variant="ghost-secondary" icon="plus" className="state-machine-add-state" onClick={vm.addState}>
            Add state
          </Button>
          {vm.hasChanges ? (
            <>
              {vm.saveError && <span className="state-machine-edit-error">{vm.saveError}</span>}
              <Button variant="ghost-secondary" icon="x" onClick={vm.cancel} disabled={vm.saving}>
                Cancel
              </Button>
              <Button variant="ghost-secondary" icon="check" onClick={vm.save} disabled={vm.saving || vm.invalid}>
                {vm.saving ? 'Saving…' : 'Save'}
              </Button>
            </>
          ) : (
            <Button variant="ghost-secondary" icon="x" onClick={vm.cancel}>
              Close
            </Button>
          )}
        </>
      }
    >
      <state-machine-edit-dialog>
        <state-machine-edit-fields>
          <Input name="Name" value={vm.name} onChange={(e) => vm.setName(e.target.value)} />
          <Input name="Description" value={vm.description} onChange={(e) => vm.setDescription(e.target.value)} />
        </state-machine-edit-fields>
        {vm.nameProblem && <p className="state-machine-edit-problem">{vm.nameProblem}</p>}
        <diagram-editor-body>
          <DiagramCanvas
            canvas={vm.canvas}
            className="sm-diagram"
            label={`${machine.name} state machine`}
            onBackgroundPress={vm.clearSelection}
          >
            <StateMachineShapes
              machine={vm.edited}
              layout={vm.layout}
              selection={vm.selection}
              targetStateId={vm.targetStateId}
              invalidIds={vm.problems}
              onStatePointerDown={vm.onStatePointerDown}
              onSegmentPointerDown={vm.onSegmentPointerDown}
              onEndPointerDown={vm.onEndPointerDown}
              editing={{
                stateButtons: vm.stateButtons,
                onAddPointerDown: vm.onAddPointerDown,
                onDeleteState: vm.deleteState,
                onDeleteTransition: vm.deleteTransition,
              }}
            />
          </DiagramCanvas>
          <StateMachineInspector
            machine={vm.draft}
            selection={vm.selection}
            panel={vm.inspector}
            fieldsOf={vm.fieldsOf}
            onEdit={vm.editField}
            problems={vm.problems}
          />
        </diagram-editor-body>
      </state-machine-edit-dialog>
    </Dialog>
  )
}

export default StateMachineEditDialog
