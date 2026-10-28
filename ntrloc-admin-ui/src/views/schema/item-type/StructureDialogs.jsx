import PropertyEditDialog from './PropertyEditDialog'
import GroupEditDialog from './GroupEditDialog'
import LinkEditDialog from './LinkEditDialog'

// Whichever of the property, group and link editors is open on an item type or a trait. `vm` is that
// editor's view model.
function StructureDialogs({ vm }) {
  return (
    <>
      {vm.groupEditor && (
        <GroupEditDialog
          group={vm.groupEditor.group}
          isNew={vm.groupEditor.isNew}
          onApply={vm.groupEditor.apply}
          onCancel={vm.groupEditor.cancel}
        />
      )}
      {vm.linkEditor && (
        <LinkEditDialog
          isNew={vm.linkEditor.isNew}
          sourceName={vm.linkEditor.sourceName}
          targetOptions={vm.linkEditor.targetOptions}
          targetId={vm.linkEditor.targetId}
          targetName={vm.linkEditor.targetName}
          forward={vm.linkEditor.forward}
          reverse={vm.linkEditor.reverse}
          onApply={vm.linkEditor.apply}
          onCancel={vm.linkEditor.cancel}
        />
      )}
      {vm.propertyEditor && (
        <PropertyEditDialog
          property={vm.propertyEditor.property}
          isNew={vm.propertyEditor.isNew}
          controlledLists={vm.propertyEditor.controlledLists}
          onApply={vm.propertyEditor.apply}
          onCancel={vm.propertyEditor.cancel}
        />
      )}
    </>
  )
}

export default StructureDialogs
