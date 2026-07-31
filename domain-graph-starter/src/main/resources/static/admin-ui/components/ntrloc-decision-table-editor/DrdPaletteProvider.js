import { paletteIconHtml, DMN_TASK_ICON_KEY } from '../ntrloc-process-editor/bpmn-icons.js';
import { DECISION_NODE, createDrdShape } from './DrdElements.js';
import { newDecisionNode } from './dmn-io.js';

// Single palette entry -- a DRD here has exactly one node type (decision table); Input Data and
// Knowledge Model nodes are deferred. Reuses the exact same icon as the BPMN editor's "DMN Task"
// palette entry (bpmn-icons.js), reinforcing that both represent the same underlying thing.
export default function DrdPaletteProvider(create, elementFactory, palette, elementRegistry) {
  this._create = create;
  this._elementFactory = elementFactory;
  this._elementRegistry = elementRegistry;

  palette.registerProvider(this);
}

DrdPaletteProvider.$inject = ['create', 'elementFactory', 'palette', 'elementRegistry'];

DrdPaletteProvider.prototype.getPaletteEntries = function() {
  const create = this._create;
  const elementFactory = this._elementFactory;
  const elementRegistry = this._elementRegistry;

  return {
    'create.decision-node': {
      group: 'drd',
      title: 'Decision Table',
      html: `
        <div class="entry ntrloc-palette-entry" draggable="true">
          <span class="ntrloc-palette-icon">${paletteIconHtml(DMN_TASK_ICON_KEY)}</span>
          <div class="ntrloc-palette-flyout">
            <strong>Decision Table</strong>
            <p>Adds a new decision table to this diagram. Draw an arrow from one table to another to mark that the first feeds into the second.</p>
          </div>
        </div>`,
      action: {
        click: (event) => {
          // Default name just counts existing nodes -- the user renames it once they drill in;
          // matches the low-ceremony "make it valid, then let the panel refine it" approach
          // createBpmnShape's own script-task defaults take on the BPMN side.
          const count = elementRegistry.filter((el) => el.type === DECISION_NODE).length;
          const node = newDecisionNode(`Decision ${count + 1}`);
          const shape = createDrdShape(elementFactory, node);
          create.start(event, shape);
        },
      },
    },
  };
};
