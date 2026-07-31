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
  .dmn-columns-toolbar {
    margin-bottom: 8px;
  }
  .dmn-table-wrap {
    overflow-x: auto;
  }
  table.dmn-table {
    border-collapse: collapse;
    min-width: 100%;
  }
  table.dmn-table th, table.dmn-table td {
    border: 1px solid var(--border);
    padding: 6px;
    vertical-align: top;
  }
  table.dmn-table th.rule-num-col, table.dmn-table td.rule-num-col {
    text-align: center;
    color: var(--muted);
    font-size: 12px;
    width: 32px;
  }
  table.dmn-table th.rule-actions-col, table.dmn-table td.rule-actions-col {
    width: 28px;
    text-align: center;
  }
  .col-header {
    display: flex;
    align-items: center;
    gap: 4px;
    margin-bottom: 4px;
  }
  .col-header input {
    flex: 1;
    font-weight: bold;
  }
  table.dmn-table th input, table.dmn-table th select, table.dmn-table td input {
    width: 100%;
    box-sizing: border-box;
    padding: 4px 6px;
    border: 1px solid var(--border);
    border-radius: 4px;
    background: var(--bg);
    color: var(--text);
    font-size: 12px;
    margin-top: 2px;
  }
  .remove-input-button, .remove-output-button, .remove-rule-button {
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
  .remove-input-button:hover, .remove-output-button:hover, .remove-rule-button:hover {
    background: var(--panel-bg);
    color: var(--text);
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
        <div class="dmn-columns-toolbar">
          <md-text-button class="add-input-button">+ Input Column</md-text-button>
          <md-text-button class="add-output-button">+ Output Column</md-text-button>
        </div>
        <div class="dmn-table-wrap">
          <table class="dmn-table">
            <thead>
              <tr>
                <th class="rule-num-col">#</th>
                ${node.inputs.map((input, i) => `
                  <th class="input-col">
                    <div class="col-header">
                      <input type="text" class="input-label-input" data-index="${i}" value="${escapeHtml(input.label)}" placeholder="Label">
                      <button class="remove-input-button" data-index="${i}" title="Remove column">&times;</button>
                    </div>
                    <input type="text" class="input-expr-input" data-index="${i}" value="${escapeHtml(input.expression)}" placeholder="process variable or upstream output name">
                    <select class="input-type-select" data-index="${i}">
                      ${TYPE_REFS.map((t) => `<option value="${t}" ${t === input.typeRef ? 'selected' : ''}>${t}</option>`).join('')}
                    </select>
                  </th>
                `).join('')}
                ${node.outputs.map((output, i) => `
                  <th class="output-col">
                    <div class="col-header">
                      <input type="text" class="output-label-input" data-index="${i}" value="${escapeHtml(output.label)}" placeholder="Label">
                      <button class="remove-output-button" data-index="${i}" title="Remove column">&times;</button>
                    </div>
                    <input type="text" class="output-name-input" data-index="${i}" value="${escapeHtml(output.name)}" placeholder="result variable">
                    <select class="output-type-select" data-index="${i}">
                      ${TYPE_REFS.map((t) => `<option value="${t}" ${t === output.typeRef ? 'selected' : ''}>${t}</option>`).join('')}
                    </select>
                  </th>
                `).join('')}
                <th class="rule-actions-col"></th>
              </tr>
            </thead>
            <tbody>
              ${node.rules.map((rule, ruleIndex) => `
                <tr>
                  <td class="rule-num-col">${ruleIndex + 1}</td>
                  ${rule.inputEntries.map((text, colIndex) => `
                    <td><input type="text" class="rule-input-entry" data-rule-index="${ruleIndex}" data-col-index="${colIndex}" value="${escapeHtml(text)}" placeholder="any"></td>
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

    wrap.querySelector('.add-input-button').addEventListener('click', () => {
      node.inputs.push({ id: nextId('input'), label: 'Input', expression: '', typeRef: 'string' });
      node.rules.forEach((rule) => rule.inputEntries.push(''));
      this.markDirty();
      this.renderTable();
    });
    wrap.querySelector('.add-output-button').addEventListener('click', () => {
      node.outputs.push({ id: nextId('output'), label: 'Output', name: 'output', typeRef: 'string' });
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

    wrap.querySelectorAll('.remove-input-button').forEach((btn) => {
      btn.addEventListener('click', () => {
        const i = Number(btn.dataset.index);
        node.inputs.splice(i, 1);
        node.rules.forEach((rule) => rule.inputEntries.splice(i, 1));
        this.markDirty();
        this.renderTable();
      });
    });
    wrap.querySelectorAll('.remove-output-button').forEach((btn) => {
      btn.addEventListener('click', () => {
        const i = Number(btn.dataset.index);
        node.outputs.splice(i, 1);
        node.rules.forEach((rule) => rule.outputEntries.splice(i, 1));
        this.markDirty();
        this.renderTable();
      });
    });
    wrap.querySelectorAll('.remove-rule-button').forEach((btn) => {
      btn.addEventListener('click', () => {
        node.rules.splice(Number(btn.dataset.ruleIndex), 1);
        this.markDirty();
        this.renderTable();
      });
    });

    wrap.querySelectorAll('.input-label-input').forEach((inp) => {
      inp.addEventListener('change', () => {
        node.inputs[Number(inp.dataset.index)].label = inp.value;
        this.markDirty();
      });
    });
    wrap.querySelectorAll('.input-expr-input').forEach((inp) => {
      inp.addEventListener('change', () => {
        node.inputs[Number(inp.dataset.index)].expression = inp.value;
        this.markDirty();
      });
    });
    wrap.querySelectorAll('.input-type-select').forEach((sel) => {
      sel.addEventListener('change', () => {
        node.inputs[Number(sel.dataset.index)].typeRef = sel.value;
        this.markDirty();
      });
    });
    wrap.querySelectorAll('.output-label-input').forEach((inp) => {
      inp.addEventListener('change', () => {
        node.outputs[Number(inp.dataset.index)].label = inp.value;
        this.markDirty();
      });
    });
    wrap.querySelectorAll('.output-name-input').forEach((inp) => {
      inp.addEventListener('change', () => {
        node.outputs[Number(inp.dataset.index)].name = inp.value;
        this.markDirty();
      });
    });
    wrap.querySelectorAll('.output-type-select').forEach((sel) => {
      sel.addEventListener('change', () => {
        node.outputs[Number(sel.dataset.index)].typeRef = sel.value;
        this.markDirty();
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

function escapeHtml(value) {
  const div = document.createElement('div');
  div.textContent = String(value ?? '');
  return div.innerHTML;
}

customElements.define('ntrloc-decision-table-editor', NtrlocDecisionTableEditor);
