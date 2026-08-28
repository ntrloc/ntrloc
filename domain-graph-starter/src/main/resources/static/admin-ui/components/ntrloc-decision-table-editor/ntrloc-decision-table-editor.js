import Diagram from '../../vendor/diagram-js/diagram-js/lib/Diagram.js';
import SelectionModule from '../../vendor/diagram-js/diagram-js/lib/features/selection/index.js';
import OutlineModule from '../../vendor/diagram-js/diagram-js/lib/features/outline/index.js';
import MoveModule from '../../vendor/diagram-js/diagram-js/lib/features/move/index.js';
import CreateModule from '../../vendor/diagram-js/diagram-js/lib/features/create/index.js';
import ConnectModule from '../../vendor/diagram-js/diagram-js/lib/features/connect/index.js';
import ModelingModule from '../../vendor/diagram-js/diagram-js/lib/features/modeling/index.js';
import PaletteModule from '../../vendor/diagram-js/diagram-js/lib/features/palette/index.js';
import ContextPadModule from '../../vendor/diagram-js/diagram-js/lib/features/context-pad/index.js';
import RulesModule from '../../vendor/diagram-js/diagram-js/lib/features/rules/index.js';
import MoveCanvasModule from '../../vendor/diagram-js/diagram-js/lib/navigation/movecanvas/index.js';
import ZoomScrollModule from '../../vendor/diagram-js/diagram-js/lib/navigation/zoomscroll/index.js';

import ConnectionLayouter from '../diagram-shared/ConnectionLayouter.js';
import ConnectionMovePreview from '../diagram-shared/ConnectionMovePreview.js';
import { addArrowheadMarker, nextArrowheadMarkerId } from '../diagram-shared/arrowhead-marker.js';
import { centerDiagram as centerDiagramOnCanvas } from '../diagram-shared/center-diagram.js';

import DrdRenderer from './DrdRenderer.js';
import DrdPaletteProvider from './DrdPaletteProvider.js';
import DrdRuleProvider from './DrdRuleProvider.js';
import DrdContextPadProvider from './DrdContextPadProvider.js';
import { DECISION_NODE, REQUIREMENT, isDecisionNode, createDrdShape } from './DrdElements.js';
import { HIT_POLICIES, TYPE_REFS, nextId, emptyDrdModel, parseDrd, serializeDrd } from './dmn-io.js';

injectStyles('ntrloc-decision-table-editor-styles', `
  ntrloc-decision-table-editor {
    display: contents;
  }
  .drd-meta {
    display: flex;
    gap: 12px;
    align-items: center;
  }
  .drd-meta .field {
    display: flex;
    align-items: center;
    gap: 6px;
  }
  .drd-meta label {
    font-size: 11px;
    font-weight: bold;
    letter-spacing: 0.05em;
    color: var(--muted);
  }
  .drd-meta input {
    padding: 5px 8px;
    border: 1px solid var(--border);
    border-radius: 4px;
    background: var(--bg);
    color: var(--text);
    font-size: 13px;
  }
  .canvas-wrap, .table-wrap {
    flex: 1;
    min-width: 0;
    display: flex;
    flex-direction: column;
    min-height: 0;
  }
  .drd-table-toolbar {
    display: flex;
    align-items: flex-end;
    gap: 20px;
    padding: 12px 24px 0;
    flex-shrink: 0;
  }
  .drd-table-toolbar .field label {
    display: block;
    font-size: 11px;
    font-weight: bold;
    letter-spacing: 0.05em;
    color: var(--muted);
    margin-bottom: 4px;
  }
  .drd-table-toolbar input, .drd-table-toolbar select {
    padding: 6px 8px;
    border: 1px solid var(--border);
    border-radius: 4px;
    background: var(--bg);
    color: var(--text);
    font-size: 13px;
  }
  .dmn-body {
    flex: 1;
    overflow: auto;
    padding: 16px 24px;
  }
  .dmn-table-wrap {
    overflow-x: auto;
  }
  /* No min-width: 100% -- that forced the table to stretch to fill its container regardless of
     how little its content actually needed, which is the opposite of "only as wide as needed."
     Plain table-layout: auto (the default) already sizes each column to its widest cell once
     nothing inside is allowed to wrap (white-space: nowrap below) -- letting the table find its
     own natural width, rather than fighting the browser to reproduce that by hand. */
  table.dmn-table {
    border-collapse: collapse;
  }
  table.dmn-table th, table.dmn-table td {
    border: 1px solid var(--border);
    padding: 10px 50px;
    vertical-align: top;
    white-space: nowrap;
  }
  /* The wider 50px horizontal padding above is for the input/output data columns specifically --
     these two are narrow utility columns (a row number, a delete button), not data the reader
     scans column-width-conscious the same way, so they keep a plain, uniform, tighter padding. */
  table.dmn-table th.rule-num-col, table.dmn-table td.rule-num-col {
    text-align: center;
    color: var(--muted);
    font-size: 12px;
    padding: 10px;
  }
  table.dmn-table th.rule-actions-col, table.dmn-table td.rule-actions-col {
    text-align: center;
    padding: 10px;
  }
  /* Marks the boundary between the input columns and the output columns -- applied to the last
     input column specifically (both its header and every rule row's cell), not derived via a
     CSS-only "last of class" selector, since input/output <td> cells in a rule row carry no class
     of their own to select against (only the <input> inside does) -- simpler to have the render
     loop, which already knows node.inputs.length, mark the one cell that needs it. */
  table.dmn-table .col-divider {
    border-right: 3px solid var(--border);
  }
  table.dmn-table th input, table.dmn-table td input {
    box-sizing: border-box;
    padding: 4px 6px;
    border: 1px solid var(--border);
    border-radius: 4px;
    background: var(--bg);
    color: var(--text);
    font-size: 12px;
    margin-top: 2px;
    /* Sizes the input itself to its actual value, not a fixed/default width -- without this, a
       rule cell's input reverts to the browser's default ~20-character intrinsic size regardless
       of how short or long its real value is, which is exactly the "wider than it needs to be"
       problem this whole change is fixing everywhere else. */
    field-sizing: content;
    min-width: 3em;
  }
  .remove-rule-button {
    border: none;
    background: none;
    color: var(--muted);
    cursor: pointer;
    font-size: 14px;
    line-height: 1;
    padding: 2px 4px;
    border-radius: 3px;
    flex-shrink: 0;
  }
  .remove-rule-button:hover {
    background: var(--panel-bg);
    color: var(--text);
  }

  /* Compact 2-line column header: name / type -- replaces the old always-editable label/
     expression/type inputs stacked in the header itself. Editing now happens in the column-
     properties dialog (openColumnPropertiesDialog), opened by the name itself (col-name-button),
     not a separate menu icon -- the name *is* the button, hence no chrome of its own beyond the
     hover treatment, so it doesn't look like a control until you're actually over it. */
  /* The spanning "Input"/"Output" row above the per-column headers. position: relative so the "+"
     button can pin to the cell's own right edge (col-group-add-button) regardless of how wide the
     colspan is, independent of the centered label -- also the only way to add the first column of
     a kind once none exist (colspan falls back to 1 in that case, see renderTable). */
  .col-group-header {
    position: relative;
    text-align: center;
    font-size: 10px;
    font-weight: bold;
    text-transform: uppercase;
    letter-spacing: 0.05em;
    color: var(--muted);
  }
  .col-group-add-button {
    position: absolute;
    right: 8px;
    top: 50%;
    transform: translateY(-50%);
    border: none;
    background: none;
    color: var(--muted);
    cursor: pointer;
    font-size: 16px;
    line-height: 1;
    padding: 2px 7px;
    border-radius: 3px;
  }
  .col-group-add-button:hover {
    background: var(--panel-bg);
    color: var(--text);
  }
  .col-name-button {
    display: block;
    width: 100%;
    border: none;
    background: none;
    padding: 0;
    margin: 0;
    font: inherit;
    text-align: inherit;
    font-weight: bold;
    font-size: 13px;
    color: var(--text);
    cursor: pointer;
  }
  .col-name-button:hover {
    /* Same amber already used for the "admin" badge elsewhere in this app -- reused here rather
       than inventing a second "attention" color. */
    color: #e8a735;
  }
  .col-type {
    font-size: 11px;
    color: var(--muted);
    margin-top: 2px;
  }

  /* Column properties dialog -- same shape as ntrloc-create-marker-dialog.js's own .marker-dialog
     (promise-based, appended to document.body), scoped separately since that file isn't a shared
     dependency of this one. */
  .column-props-dialog .column-props-content {
    display: flex;
    flex-direction: column;
    gap: 14px;
    min-width: 320px;
  }
  .column-props-dialog label {
    display: block;
    font-size: 11px;
    font-weight: bold;
    letter-spacing: 0.05em;
    color: var(--muted);
    margin-bottom: 4px;
  }
  .column-props-dialog input, .column-props-dialog select {
    width: 100%;
    box-sizing: border-box;
    background: var(--panel-bg);
    border: 1px solid var(--border);
    border-radius: 6px;
    color: var(--text);
    padding: 8px 10px;
    font-size: 14px;
  }
  .column-props-dialog input:focus, .column-props-dialog select:focus {
    outline: none;
    border-color: var(--accent);
  }
  .column-props-dialog .column-props-error {
    color: #f85149;
    font-size: 13px;
    margin: 0;
  }
`);

// DRD (Decision Requirements Diagram) editor: a diagram-js canvas of decision-table nodes linked
// by dependency arrows (see dmn-io.js for the always-bundled <decisionService> XML this reads/
// writes), with a drill-in table editor for each node's rule grid -- the user's explicit call
// over a split canvas+table view, since editing dependencies and editing a table's rule content
// are never needed at the same time. Both the canvas and the table view are mounted once and
// toggled via .canvas-wrap/.table-wrap visibility (never destroyed/recreated), matching ntrloc-
// tab-workspace's own mount-once pattern. A node's diagram-js shape.businessObject IS the plain
// model object itself (see DrdElements.js) -- the drill-in editor mutates it in place, so only a
// node's x/y/width/height (tracked on the shape, not businessObject) need reading back out of the
// element registry at save time, in collectDrdModel().
class NtrlocDecisionTableEditor extends HTMLElement {
  constructor() {
    super();
    this._decisionId = null;
    this._isNew = false;
    this._model = null;
    this._diagram = null;
    this._activeNode = null;
    this._view = 'canvas';
    this._dirty = false;
  }

  connectedCallback() {
    this._decisionId = this.dataset.decisionId;
    // Same reserved-placeholder-prefix detection as ntrloc-process-editor.js and the prior
    // single-table version of this editor -- DMN decision ids are plain UUIDs assigned by
    // DecisionDataManagerImpl.assignIdIfMissing(), no composite format to infer "new" from.
    this._isNew = !this._decisionId || this._decisionId.startsWith('new-decision-');
    this.renderShell();
    if (this._isNew) {
      this._model = emptyDrdModel();
      this._model.decisionServiceName = 'New Decision Table';
      this.syncMetaInputs();
      this.initCanvas();
      this.centerDiagram();
      this.reportDirty(false);
    } else {
      this.load();
    }
  }

  disconnectedCallback() {
    if (this._diagram) {
      this._diagram.destroy();
      this._diagram = null;
    }
  }

  async load() {
    try {
      const response = await fetch(`/api/admin/dmn/decisions/xml?id=${encodeURIComponent(this._decisionId)}`, { credentials: 'include' });
      if (!response.ok) throw new Error('Request failed: ' + response.status);
      const xml = await response.text();
      this._model = parseDrd(xml);
      this.syncMetaInputs();
      this.initCanvas();
      this.centerDiagram();
      this.reportDirty(false);
    } catch (e) {
      this.setStatus('Failed to load decision table: ' + e.message, true);
    }
  }

  async save() {
    if (!this._model) return;
    if (!this._model.decisionServiceKey) {
      this.setStatus('Enter a key before saving.', true);
      return;
    }
    try {
      this.setStatus('Saving...', false);
      this.collectDrdModel();
      const xml = serializeDrd(this._model);
      const response = await fetch(`/api/admin/dmn/decisions/${encodeURIComponent(this._model.decisionServiceKey)}/versions`, {
        method: 'POST',
        credentials: 'include',
        headers: { 'Content-Type': 'application/xml' },
        body: xml,
      });
      if (!response.ok) {
        const error = await response.json().catch(() => ({ message: 'Request failed: ' + response.status }));
        throw new Error(error.message || 'Request failed: ' + response.status);
      }
      const deployed = await response.json();
      this._decisionId = deployed.id;
      this._isNew = false;
      const keyInput = this.querySelector('.key-input');
      keyInput.disabled = true;
      keyInput.title = 'Key is fixed once a decision table is saved';
      this.reportDirty(false);
      this.setStatus(`Saved as version ${deployed.version}.`, false);
    } catch (e) {
      this.setStatus('Failed to save: ' + e.message, true);
    }
  }

  // Reads the current x/y/width/height of every node's shape (and the current set of requirement
  // connections) back out of the element registry into this._model -- mirrors bpmn-io.js's own
  // exportXml discipline of collecting from live diagram-js state rather than trusting a
  // separately-tracked copy that could have drifted from what's actually on screen.
  collectDrdModel() {
    const elementRegistry = this._diagram.get('elementRegistry');
    this._model.decisions = elementRegistry.filter((el) => el.type === DECISION_NODE).map((shape) => {
      const node = shape.businessObject;
      node.x = shape.x;
      node.y = shape.y;
      node.width = shape.width;
      node.height = shape.height;
      return node;
    });
    this._model.requirements = elementRegistry.filter((el) => el.type === REQUIREMENT).map((conn) => conn.businessObject);
  }

  initCanvas() {
    const container = this.querySelector('.editor-canvas');
    // Generated before the Diagram exists (see arrowhead-marker.js's header comment for why) --
    // this exact string is what DrdRenderer.js's marker-end ends up referencing.
    const arrowheadMarkerId = nextArrowheadMarkerId('ntrloc-drd-requirement-end');
    this._diagram = new Diagram({
      canvas: { container },
      modules: [
        SelectionModule, OutlineModule, MoveModule, CreateModule, ConnectModule,
        ModelingModule, PaletteModule, ContextPadModule, RulesModule,
        MoveCanvasModule, ZoomScrollModule,
        {
          __init__: [
            'drdRenderer', 'drdPaletteProvider', 'drdRuleProvider', 'drdContextPadProvider',
            'drdConnectionMovePreview',
          ],
          arrowheadMarkerId: ['value', arrowheadMarkerId],
          drdRenderer: ['type', DrdRenderer],
          drdPaletteProvider: ['type', DrdPaletteProvider],
          drdRuleProvider: ['type', DrdRuleProvider],
          drdContextPadProvider: ['type', DrdContextPadProvider],
          drdConnectionMovePreview: ['type', ConnectionMovePreview],
          // Overrides ModelingModule's default 'layouter' binding (BaseLayouter, plain
          // center-to-center, no cropping) -- must be listed after ModelingModule above for this
          // override to win; Didi's provider registration is last-write-wins per key.
          layouter: ['type', ConnectionLayouter],
        },
      ],
    });

    addArrowheadMarker(container, arrowheadMarkerId);

    const canvas = this._diagram.get('canvas');
    const elementFactory = this._diagram.get('elementFactory');

    const shapesById = new Map();
    this._model.decisions.forEach((node) => {
      const shape = createDrdShape(elementFactory, node);
      canvas.addShape(shape);
      shapesById.set(node.id, shape);
    });
    this._model.requirements.forEach((req) => {
      const source = shapesById.get(req.requiredDecisionId);
      const target = shapesById.get(req.dependentDecisionId);
      if (!source || !target) return;
      const connection = elementFactory.createConnection({
        id: req.id,
        type: REQUIREMENT,
        businessObject: req,
        source,
        target,
        waypoints: [
          { x: source.x + source.width, y: source.y + source.height / 2 },
          { x: target.x, y: target.y + target.height / 2 },
        ],
      });
      canvas.addConnection(connection);
    });

    this._diagram.get('eventBus').on('commandStack.changed', () => this.markDirty());
    this._diagram.get('eventBus').on('element.dblclick', (event) => {
      if (isDecisionNode(event.element)) this.drillInto(event.element.businessObject);
    });
  }

  drillInto(node) {
    this._activeNode = node;
    this._view = 'table';
    this.querySelector('.canvas-wrap').style.display = 'none';
    this.querySelector('.table-wrap').style.display = '';
    this.renderTable();
  }

  backToDiagram() {
    this._activeNode = null;
    this._view = 'canvas';
    this.querySelector('.table-wrap').style.display = 'none';
    this.querySelector('.canvas-wrap').style.display = '';
  }

  // No dedicated "re-render just this element" API is wired up (same reduced module set as
  // ntrloc-process-editor.js) -- dropping and re-adding the shape is a simple, correct way to
  // force GraphicsFactory to redraw it with its updated businessObject.name.
  redrawNode(nodeId) {
    const elementRegistry = this._diagram.get('elementRegistry');
    const shape = elementRegistry.get(nodeId);
    if (!shape) return;
    const canvas = this._diagram.get('canvas');
    canvas.removeShape(shape);
    canvas.addShape(shape);
  }

  markDirty() {
    this.reportDirty(true);
  }

  reportDirty(dirty) {
    this._dirty = dirty;
    this.dispatchEvent(new CustomEvent('dirty-changed', { bubbles: true, detail: { dirty } }));
  }

  setStatus(message, isError) {
    const el = this.querySelector('.editor-status');
    if (el) {
      el.textContent = message;
      el.style.color = isError ? '#e24a4a' : 'var(--muted)';
    }
  }

  // Kept as an instance method for the same reason ntrloc-process-editor.js's is: ntrloc-
  // processes.js's recenterActiveDiagram() calls it generically on the active tab's content
  // element without knowing which editor type it is. A no-op while drilled into a table (no
  // canvas visible), matching that command's own existing no-op behavior for editor types it
  // doesn't apply to.
  centerDiagram() {
    if (this._view === 'table') return;
    centerDiagramOnCanvas(this._diagram);
  }

  renderShell() {
    this.innerHTML = `
      <div class="editor-toolbar">
        <div class="drd-meta">
          <div class="field">
            <label>Name</label>
            <input type="text" class="name-input">
          </div>
          <div class="field">
            <label>Key</label>
            <input type="text" class="key-input" placeholder="e.g. approvalDecision">
          </div>
        </div>
        <span class="editor-status status"></span>
        <span class="spacer"></span>
        <md-filled-button class="save-button">Save</md-filled-button>
      </div>
      <div class="editor-body">
        <div class="canvas-wrap">
          <div class="editor-canvas"></div>
        </div>
        <div class="table-wrap" style="display: none;"></div>
      </div>
    `;
    this.querySelector('.save-button').addEventListener('click', () => this.save());
    this.querySelector('.name-input').addEventListener('change', (e) => {
      this._model.decisionServiceName = e.target.value;
      this.markDirty();
    });
    this.querySelector('.key-input').addEventListener('change', (e) => {
      this._model.decisionServiceKey = e.target.value.trim();
      this.markDirty();
    });
  }

  syncMetaInputs() {
    const nameInput = this.querySelector('.name-input');
    const keyInput = this.querySelector('.key-input');
    nameInput.value = this._model.decisionServiceName || '';
    keyInput.value = this._model.decisionServiceKey || '';
    keyInput.disabled = !this._isNew;
    keyInput.title = this._isNew ? '' : 'Key is fixed once a decision table is saved';
  }

  renderTable() {
    const node = this._activeNode;
    const wrap = this.querySelector('.table-wrap');
    wrap.innerHTML = `
      <div class="drd-table-toolbar">
        <md-text-button class="back-button">&larr; Back to Diagram</md-text-button>
        <div class="field">
          <label>Table Name</label>
          <input type="text" class="node-name-input" value="${escapeHtml(node.name)}">
        </div>
        <div class="field">
          <label>Hit Policy</label>
          <select class="hit-policy-select">
            ${HIT_POLICIES.map((p) => `<option value="${p}" ${p === node.hitPolicy ? 'selected' : ''}>${p}</option>`).join('')}
          </select>
        </div>
      </div>
      <div class="dmn-body">
        <div class="dmn-table-wrap">
          <table class="dmn-table">
            <thead>
              <tr>
                <th class="rule-num-col" rowspan="2">#</th>
                <th class="col-group-header col-divider" colspan="${Math.max(node.inputs.length, 1)}">
                  Input
                  <button class="col-group-add-button add-input-button" title="Add input column">+</button>
                </th>
                <th class="col-group-header" colspan="${Math.max(node.outputs.length, 1)}">
                  Output
                  <button class="col-group-add-button add-output-button" title="Add output column">+</button>
                </th>
                <th class="rule-actions-col" rowspan="2"></th>
              </tr>
              <tr>
                ${node.inputs.map((input, i) => `
                  <th class="input-col ${i === node.inputs.length - 1 ? 'col-divider' : ''}">
                    <button class="col-name-button" data-kind="input" data-index="${i}" title="Edit">${escapeHtml(input.label || input.expression)}</button>
                    <div class="col-type">${escapeHtml(input.typeRef)}</div>
                  </th>
                `).join('')}
                ${node.outputs.map((output, i) => `
                  <th class="output-col">
                    <button class="col-name-button" data-kind="output" data-index="${i}" title="Edit">${escapeHtml(output.label || output.name)}</button>
                    <div class="col-type">${escapeHtml(output.typeRef)}</div>
                  </th>
                `).join('')}
              </tr>
            </thead>
            <tbody>
              ${node.rules.map((rule, ruleIndex) => `
                <tr>
                  <td class="rule-num-col">${ruleIndex + 1}</td>
                  ${rule.inputEntries.map((text, colIndex) => `
                    <td class="${colIndex === node.inputs.length - 1 ? 'col-divider' : ''}"><input type="text" class="rule-input-entry" data-rule-index="${ruleIndex}" data-col-index="${colIndex}" value="${escapeHtml(text)}" placeholder="any"></td>
                  `).join('')}
                  ${rule.outputEntries.map((text, colIndex) => `
                    <td><input type="text" class="rule-output-entry" data-rule-index="${ruleIndex}" data-col-index="${colIndex}" value="${escapeHtml(text)}"></td>
                  `).join('')}
                  <td class="rule-actions-col"><button class="remove-rule-button" data-rule-index="${ruleIndex}" title="Remove rule">&times;</button></td>
                </tr>
              `).join('')}
            </tbody>
          </table>
        </div>
        <md-text-button class="add-rule-button">+ Rule</md-text-button>
      </div>
    `;
    this.wireTableEvents();
  }

  wireTableEvents() {
    const wrap = this.querySelector('.table-wrap');
    const node = this._activeNode;

    wrap.querySelector('.back-button').addEventListener('click', () => this.backToDiagram());

    wrap.querySelector('.node-name-input').addEventListener('change', (e) => {
      node.name = e.target.value;
      this.redrawNode(node.id);
      this.markDirty();
    });
    wrap.querySelector('.hit-policy-select').addEventListener('change', (e) => {
      node.hitPolicy = e.target.value;
      this.markDirty();
    });

    // Prompts for expression/type up front, rather than adding a column with placeholder values
    // that would already violate the "expression is mandatory" rule the dialog itself enforces --
    // there's no valid default expression to push a column with any more, this dialog is the only
    // place either field can be set. Cancelling adds nothing.
    wrap.querySelector('.add-input-button').addEventListener('click', async () => {
      const result = await openColumnPropertiesDialog({ kind: 'input', label: '', expression: '', typeRef: 'string' });
      if (!result || result.action !== 'save') return;
      node.inputs.push({ id: nextId('input'), label: result.label, expression: result.expression, typeRef: result.typeRef });
      node.rules.forEach((rule) => rule.inputEntries.push(''));
      this.markDirty();
      this.renderTable();
    });
    wrap.querySelector('.add-output-button').addEventListener('click', async () => {
      const result = await openColumnPropertiesDialog({ kind: 'output', label: '', expression: '', typeRef: 'string' });
      if (!result || result.action !== 'save') return;
      node.outputs.push({ id: nextId('output'), label: result.label, name: result.expression, typeRef: result.typeRef });
      node.rules.forEach((rule) => rule.outputEntries.push(''));
      this.markDirty();
      this.renderTable();
    });
    wrap.querySelector('.add-rule-button').addEventListener('click', () => {
      node.rules.push({
        id: nextId('rule'),
        inputEntries: node.inputs.map(() => ''),
        outputEntries: node.outputs.map(() => ''),
      });
      this.markDirty();
      this.renderTable();
    });

    wrap.querySelectorAll('.remove-rule-button').forEach((btn) => {
      btn.addEventListener('click', () => {
        node.rules.splice(Number(btn.dataset.ruleIndex), 1);
        this.markDirty();
        this.renderTable();
      });
    });

    // Single handler for every column, input or output -- opens the properties dialog with that
    // column's current values (an output's "expression" is its DMN name attribute, see the
    // dialog's own comment), and applies whichever action the dialog resolved with. Replaces the
    // old always-visible per-field inputs and separate remove-*-button handlers entirely. The
    // column *name* is the trigger now (col-name-button), not a separate menu icon.
    wrap.querySelectorAll('.col-name-button').forEach((btn) => {
      btn.addEventListener('click', async () => {
        const kind = btn.dataset.kind;
        const i = Number(btn.dataset.index);
        const column = kind === 'input' ? node.inputs[i] : node.outputs[i];
        const currentExpression = kind === 'input' ? column.expression : column.name;
        const result = await openColumnPropertiesDialog({ kind, label: column.label, expression: currentExpression, typeRef: column.typeRef });
        if (!result) return;

        if (result.action === 'delete') {
          if (kind === 'input') {
            node.inputs.splice(i, 1);
            node.rules.forEach((rule) => rule.inputEntries.splice(i, 1));
          } else {
            node.outputs.splice(i, 1);
            node.rules.forEach((rule) => rule.outputEntries.splice(i, 1));
          }
        } else if (result.action === 'save') {
          column.label = result.label;
          column.typeRef = result.typeRef;
          if (kind === 'input') column.expression = result.expression;
          else column.name = result.expression;
        }
        this.markDirty();
        this.renderTable();
      });
    });

    wrap.querySelectorAll('.rule-input-entry').forEach((inp) => {
      inp.addEventListener('change', () => {
        node.rules[Number(inp.dataset.ruleIndex)].inputEntries[Number(inp.dataset.colIndex)] = inp.value;
        this.markDirty();
      });
    });
    wrap.querySelectorAll('.rule-output-entry').forEach((inp) => {
      inp.addEventListener('change', () => {
        node.rules[Number(inp.dataset.ruleIndex)].outputEntries[Number(inp.dataset.colIndex)] = inp.value;
        this.markDirty();
      });
    });
  }
}

// Promise-based, same shape as ntrloc-create-marker-dialog.js's openCreateMarkerDialog (not a
// shared dependency of this file, so duplicated rather than imported). Resolves with
// { action: 'save', label, expression, typeRef } on Save, { action: 'delete' } on Delete Column,
// or null on Cancel -- label may be blank, expression and typeRef may not (enforced here, not by
// the caller, so the same validation applies whether this is an input or output column: an
// output's "expression" is really its DMN <output name="..."> result-variable name, not an
// evaluated FEEL expression, but the same field/validation serves both -- no reason to make the
// caller special-case the label per kind for what's structurally the same "what identifies this
// column's value" concept either way).
function openColumnPropertiesDialog({ kind, label, expression, typeRef }) {
  return new Promise((resolve) => {
    const dialog = document.createElement('md-dialog');
    dialog.className = 'column-props-dialog';
    dialog.innerHTML = `
      <div slot="headline">${kind === 'input' ? 'Input' : 'Output'} Column</div>
      <div slot="content" class="column-props-content">
        <div>
          <label for="column-label-input">Label</label>
          <input id="column-label-input" class="column-label-input" value="${escapeHtml(label)}" placeholder="Optional">
        </div>
        <div>
          <label for="column-expression-input">Expression</label>
          <input id="column-expression-input" class="column-expression-input" value="${escapeHtml(expression)}"
                 placeholder="${kind === 'input' ? 'process variable or upstream output name' : 'result variable name'}">
        </div>
        <div>
          <label for="column-type-select">Type</label>
          <select id="column-type-select" class="column-type-select">
            ${TYPE_REFS.map((t) => `<option value="${t}" ${t === typeRef ? 'selected' : ''}>${t}</option>`).join('')}
          </select>
        </div>
        <p class="column-props-error" hidden></p>
      </div>
      <div slot="actions">
        <md-text-button class="delete-button">Delete Column</md-text-button>
        <md-text-button class="cancel-button">Cancel</md-text-button>
        <md-filled-button class="save-button">Save</md-filled-button>
      </div>
    `;
    document.body.appendChild(dialog);

    const errorEl = dialog.querySelector('.column-props-error');
    let resolved = false;
    dialog.addEventListener('closed', () => {
      if (!resolved) resolve(null);
      dialog.remove();
    });

    dialog.querySelector('.cancel-button').addEventListener('click', () => dialog.close('cancel'));
    dialog.querySelector('.delete-button').addEventListener('click', () => {
      resolved = true;
      dialog.close('delete');
      resolve({ action: 'delete' });
    });
    dialog.querySelector('.save-button').addEventListener('click', () => {
      const newLabel = dialog.querySelector('.column-label-input').value.trim();
      const newExpression = dialog.querySelector('.column-expression-input').value.trim();
      const newTypeRef = dialog.querySelector('.column-type-select').value;

      if (!newExpression) {
        errorEl.textContent = 'Expression is required.';
        errorEl.hidden = false;
        return;
      }
      if (!newTypeRef) {
        errorEl.textContent = 'Type is required.';
        errorEl.hidden = false;
        return;
      }

      resolved = true;
      dialog.close('save');
      resolve({ action: 'save', label: newLabel, expression: newExpression, typeRef: newTypeRef });
    });

    dialog.open = true;
  });
}

// Every call site here renders into an HTML *attribute* (value="${escapeHtml(...)}"), not just
// text content -- labels, expressions, and rule entries all go through this. The div.textContent/
// innerHTML trick only escapes markup characters (<, >, &), never quotes, since a quote has no
// special meaning inside a text node -- it only matters once that text lands inside a quoted
// attribute. A FEEL string literal like "Confidential" (quotes are part of the literal syntax)
// broke every one of those attributes: the unescaped " closed value="..." early, corrupting the
// rest of the tag. Explicit character replacement, not the div trick, so quotes are covered too.
function escapeHtml(value) {
  return String(value ?? '')
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;');
}

customElements.define('ntrloc-decision-table-editor', NtrlocDecisionTableEditor);
