// Minimal context pad: delete the selected node, or drag from it to start a requirement arrow.
// Identical in shape to BpmnContextPadProvider.js -- no type-specific logic needed there either,
// since a DRD only has one node type.
export default function DrdContextPadProvider(connect, contextPad, modeling) {
  this._connect = connect;
  this._modeling = modeling;

  contextPad.registerProvider(this);
}

DrdContextPadProvider.$inject = ['connect', 'contextPad', 'modeling'];

DrdContextPadProvider.prototype.getContextPadEntries = function(element) {
  const connect = this._connect;
  const modeling = this._modeling;

  const removeElement = () => modeling.removeElements([element]);
  const startConnect = (event, el, autoActivate) => connect.start(event, el, autoActivate);

  return {
    'delete': {
      group: 'edit',
      title: 'Remove',
      html: '<div class="entry ntrloc-context-pad-icon" draggable="true"><span>&times;</span></div>',
      action: {
        click: removeElement,
        dragstart: removeElement,
      },
    },
    'connect': {
      group: 'edit',
      title: 'Connect',
      html: '<div class="entry ntrloc-context-pad-icon" draggable="true"><span>&rarr;</span></div>',
      action: {
        click: startConnect,
        dragstart: startConnect,
      },
    },
  };
};
