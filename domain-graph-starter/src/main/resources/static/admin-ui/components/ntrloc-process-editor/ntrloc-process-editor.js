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
// Ensures a stale drag.move.move 'hover' clears when the cursor leaves every element (e.g. drags
// off a Sub-Process onto empty canvas) -- without this, MoveEvents.js keeps treating the last-
// hovered element as the drop target forever (no element.out ever fires for it, since nothing
// new is ever hovered over empty canvas), so a task dragged out of a Sub-Process never actually
// reparents: it just keeps getting SubProcessAutoResizeBehavior-grown back around it.
import HoverFixModule from '../../vendor/diagram-js/diagram-js/lib/features/hover-fix/index.js';
// Draggable bend points/segment handles on selected or hovered connections -- lets a sequence
// flow's route be reshaped (not just source/target), and its start/end reconnected to a
// different node by dragging the end handles. bendpointSnapping (magnetic alignment guides) is
// deliberately not vendored -- see vendor/diagram-js/diagram-js/lib/features/bendpoints/index.js.
import BendpointsModule from '../../vendor/diagram-js/diagram-js/lib/features/bendpoints/index.js';
// BendpointMove.js (dragging a single existing bendpoint, or a connection's own end handle to
// reconnect it) only draws live drag feedback if a 'connectionPreview' service is registered --
// it does `injector.get('connectionPreview', false)` and silently skips the whole live-update
// block otherwise (verified directly against that file), leaving the connection frozen at its
// drag-start shape for the entire drag before jumping to the final one on drop.
import ConnectionPreviewModule from '../../vendor/diagram-js/diagram-js/lib/features/connection-preview/index.js';
import CroppingConnectionDocking from '../../vendor/diagram-js/diagram-js/lib/layout/CroppingConnectionDocking.js';
import { generateShortId } from '../diagram-shared/short-id.js';

import BpmnRenderer from './BpmnRenderer.js';
import BpmnPaletteProvider from './BpmnPaletteProvider.js';
import BpmnRuleProvider from './BpmnRuleProvider.js';
import BpmnContextPadProvider from './BpmnContextPadProvider.js';
import SubProcessAutoResizeBehavior from './SubProcessAutoResizeBehavior.js';
import SubProcessAutoSeedBehavior from './SubProcessAutoSeedBehavior.js';
import SubProcessToggleBehavior from './SubProcessToggleBehavior.js';
import RemoveCrossBoundaryConnectionsBehavior from './RemoveCrossBoundaryConnectionsBehavior.js';
import ConnectionLayouter from '../diagram-shared/ConnectionLayouter.js';
import ConnectionMovePreview from '../diagram-shared/ConnectionMovePreview.js';
import ConnectionRouteOriginBehavior from '../diagram-shared/ConnectionRouteOriginBehavior.js';
import { addArrowheadMarker, nextArrowheadMarkerId } from '../diagram-shared/arrowhead-marker.js';
import { centerDiagram as centerDiagramOnCanvas } from '../diagram-shared/center-diagram.js';
import { importXml, exportXml, readProcessVariables, PROCESS_VARIABLE_TYPES } from './bpmn-io.js';
import { openRunProcessDialog } from './RunProcessDialog.js';
import {
  SCRIPT_TASK, USER_TASK, CALL_ACTIVITY, isDmnTask, isTimerStartEvent, isSubProcess, getLoopType,
  LOOP_TYPE_STANDARD, LOOP_TYPE_MI_PARALLEL, LOOP_TYPE_MI_SEQUENTIAL, getDecisionTableReferenceKey,
} from './bpmn-elements.js';
import { ScriptEditor } from './ScriptEditor.js';

injectStyles('ntrloc-process-editor-styles', `
  :root {
    /* Per-activity-type accent colors (BpmnRenderer.js, bpmn-icons.js) -- fixed, deliberately
       saturated hues (not derived from the app theme) so each BPMN element type reads at a
       glance, matching the color convention shown at open-bpmn.org: green start, red end, blue
       task, orange gateway. */
    --start-fill: #388e3c;
    --start-stroke: #1b5e20;
    --end-fill: #d32f2f;
    --end-stroke: #7f0000;
    --task-fill: #3d6690;
    --task-stroke: #5b87b3;
    /* Script Task: a teal a few degrees off Task's blue -- close enough to still read as "a kind
       of task" next to a plain Task, distinct enough to tell apart at a glance without relying on
       the corner glyph alone. */
    --script-task-fill: #2d6e6e;
    --script-task-stroke: #4f9e9e;
    /* User Task: a violet a few degrees off Task's blue in the other direction from Script
       Task's teal, same reasoning -- reads as "a kind of task" while staying its own color. */
    --user-task-fill: #5b3d8f;
    --user-task-stroke: #8265b3;
    /* DMN Task: a deep gold/amber, distinct from Gateway's brighter orange -- reads as its own
       "kind of task" alongside Script/User Task's teal/violet. */
    --dmn-task-fill: #8f6a12;
    --dmn-task-stroke: #c99a2e;
    /* Call Activity: a slate blue-grey, distinct from Task's plain blue and User Task's violet --
       reads as "a kind of task" while standing apart as "hands off to something else." */
    --call-activity-fill: #445266;
    --call-activity-stroke: #6c7f99;
    /* Sub-Process: a deep rust/brick red, unlike anything else in this palette -- a container is
       categorically different from every other task-shaped element, and needs to read that way
       at a glance whether collapsed (opaque box) or expanded (its stroke, at low fill-opacity). */
    --subprocess-fill: #7a3b28;
    --subprocess-stroke: #b8583c;
    /* Parallel Gateway shares Exclusive Gateway's colors -- same category, see BpmnRenderer.js. */
    --gateway-fill: #d98e34;
    --gateway-stroke: #a9701f;
    /* features/connection-preview's own live drag-feedback stroke (ConnectionPreview.js). */
    --element-dragger-color: var(--accent);
  }
  ntrloc-process-editor {
    display: contents;
  }
  .editor-toolbar {
    display: flex;
    align-items: center;
    gap: 12px;
    padding: 10px 16px;
    border-bottom: 1px solid var(--border);
    flex-shrink: 0;
  }
  .editor-toolbar .spacer { flex: 1; }
  .editor-body {
    flex: 1;
    display: flex;
    min-height: 0;
  }
  .editor-canvas {
    flex: 1;
    position: relative;
    background: var(--bg);
  }
  /* Canvas.js gives its <svg> tabindex="0" (for keyboard shortcuts) and calls .focus() on click
     -- without this, the browser's default focus ring outlines the entire canvas on every click,
     not just a selected element. */
  .editor-canvas svg:focus {
    outline: none;
  }
  .editor-panel {
    width: 260px;
    flex-shrink: 0;
    border-left: 1px solid var(--border);
    padding: 16px;
    overflow-y: auto;
    color: var(--text);
    background: var(--panel-bg);
  }
  /* A Script Task's editor (ScriptEditor.js) needs real room to be usable -- 260px is fine for
     the plain Type/ID/Name fields every other element shows, but cramped for reading/writing an
     actual script. */
  .editor-panel.has-script {
    width: 480px;
  }
  .editor-panel label {
    display: block;
    font-size: 11px;
    font-weight: bold;
    letter-spacing: 0.05em;
    color: var(--muted);
    margin-bottom: 4px;
    margin-top: 12px;
  }
  .editor-panel input {
    width: 100%;
    box-sizing: border-box;
    padding: 6px 8px;
    border: 1px solid var(--border);
    border-radius: 4px;
    background: var(--bg);
    color: var(--text);
  }
  .editor-panel .field-value {
    color: var(--text);
    word-break: break-all;
    font-size: 12px;
  }
  .editor-panel select {
    width: 100%;
    box-sizing: border-box;
    padding: 6px 8px;
    border: 1px solid var(--border);
    border-radius: 4px;
    background: var(--bg);
    color: var(--text);
    font-family: inherit;
  }
  .editor-panel textarea {
    width: 100%;
    box-sizing: border-box;
    padding: 6px 8px;
    border: 1px solid var(--border);
    border-radius: 4px;
    background: var(--bg);
    color: var(--text);
    font-family: inherit;
    font-size: 13px;
    resize: vertical;
    min-height: 60px;
  }
  /* CodeMirror's EditorView mounts here (ScriptEditor.js) -- the fixed height on .cm-editor
     itself (editorTheme in ScriptEditor.js) is what actually bounds it; this margin just gives it
     breathing room from the Script Format select above it. */
  .script-editor-container {
    margin-top: 8px;
  }
  /* Process-level Required Variables (renderProcessPanel) -- same visual language as the
     Sub-Process loop-type menu's option rows, at panel-content scale rather than a floating menu. */
  .variables-table {
    width: 100%;
    border-collapse: collapse;
    margin-top: 4px;
    font-size: 12px;
  }
  .variables-table th {
    text-align: left;
    font-size: 10px;
    color: var(--muted);
    font-weight: bold;
    letter-spacing: 0.05em;
    padding: 2px 4px;
    border-bottom: 1px solid var(--border);
  }
  .variables-table td {
    padding: 3px 4px;
  }
  .variables-table input[type="text"], .variables-table input:not([type]) {
    width: 100%;
    box-sizing: border-box;
    background: var(--bg);
    color: var(--text);
    border: 1px solid var(--border);
    border-radius: 4px;
    padding: 3px 5px;
    font-size: 12px;
  }
  .variables-table select {
    width: 100%;
    background: var(--bg);
    color: var(--text);
    border: 1px solid var(--border);
    border-radius: 4px;
    padding: 3px;
    font-size: 12px;
  }
  /* Two explicit rows (name+type, then required+Add) rather than one flex-wrap row -- at the
     panel's 260px width there isn't room for all four controls on one line, and letting them
     wrap freely left the Add button and Required label visually overlapping (a custom element
     like md-outlined-button doesn't always report intrinsic size correctly to the flex algorithm
     before it's fully upgraded, confirmed live: getBoundingClientRect showed no overlap but the
     actual paint did) rather than cleanly dropping to a second line. */
  .add-variable-row {
    display: flex;
    flex-direction: column;
    gap: 6px;
    margin-top: 8px;
  }
  .add-variable-fields {
    display: flex;
    gap: 6px;
  }
  .add-variable-actions {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 6px;
  }
  .add-variable-row .new-variable-name {
    flex: 1;
    min-width: 60px;
    background: var(--bg);
    color: var(--text);
    border: 1px solid var(--border);
    border-radius: 4px;
    padding: 5px 6px;
    font-size: 12px;
  }
  .add-variable-row select {
    background: var(--bg);
    color: var(--text);
    border: 1px solid var(--border);
    border-radius: 4px;
    padding: 5px;
    font-size: 12px;
  }
  .new-variable-required-label {
    display: flex;
    align-items: center;
    gap: 4px;
    font-size: 12px;
    color: var(--muted);
    white-space: nowrap;
  }
  /* md-outlined-select doesn't stretch to its container by default like a native <select> does. */
  .assignee-select {
    width: 100%;
  }
  /* One md-checkbox per group -- Material has no multi-select dropdown, this is the idiomatic
     equivalent for "pick multiple items from a list" (see renderUserTaskFields). */
  .candidate-groups-list {
    display: flex;
    flex-direction: column;
    gap: 2px;
  }
  .candidate-group-row {
    display: flex;
    align-items: center;
    gap: 4px;
    font-size: 13px;
    cursor: pointer;
  }

  /* diagram-js chrome, re-themed to fit -- its default assets/diagram-js.css assumes a light
     app shell; only the bits actually used here (palette, context pad, outline) are touched. */
  .djs-palette {
    border: 1px solid var(--border) !important;
    background: var(--panel-bg) !important;
  }
  .djs-palette .entry, .djs-context-pad .entry {
    color: var(--text) !important;
  }
  .djs-palette .entry {
    font-size: 11px !important;
  }
  /* diagram-js.css's own default (22x22px box, 22px font) reads fine on a light theme with its
     original icon font glyphs -- here it's plain unicode characters (BpmnContextPadProvider.js)
     which sit noticeably smaller than a purpose-drawn glyph at the same nominal font-size, so the
     box itself is sized up rather than just the text, keeping the enlarged icon still visually
     centered and not cramped against the entry's edges. */
  .djs-context-pad .entry {
    width: 30px !important;
    height: 30px !important;
    font-size: 18px !important;
  }
  .djs-context-pad {
    width: 104px !important;
  }
  /* diagram-js.css sets these two custom properties to a hardcoded white/light-grey default
     (never themeable via the class rules above) -- overridden here, or the context-pad icons'
     own light-colored text (matching the palette, immediately above) sits on an equally light
     background and disappears entirely: two blank-looking white boxes, not obviously interactive. */
  .djs-context-pad {
    --context-pad-entry-background-color: var(--panel-bg);
    --context-pad-entry-hover-background-color: var(--border);
  }
  /* Create module (dragging a new element from the palette) swaps these two in as the canvas
     <svg>'s background via .new-parent/.drop-not-ok classes -- both default to near-white/
     near-red, meant for a light canvas. Set equal to the canvas's own --bg (not just a lighter
     tint of it) so the canvas doesn't visibly change color at all during a palette drag.
     --shape-connect-allowed-fill-color (Connect module: hovering a valid target while dragging out
     a new sequence flow) belongs in this same rule for the same reason, not up in the :root-style
     block above where every other themed color in this file lives -- diagram-js.css's own default
     for all three of these is declared directly on .djs-parent (not :root), and a custom property
     resolves from the *nearest* ancestor that declares it, not the most specific selector that
     matches the same element -- a :root-level override is simply never reached by anything inside
     an element that redeclares the property on itself. Confirmed live: querying
     getComputedStyle(document.documentElement) showed the :root override taking effect exactly as
     written, while the actual target rect's computed fill still resolved to vendor's near-white
     default the whole time. --panel-bg (not --bg, unlike the two drop-* properties above) since
     connect *should* read as "lit up", not "unchanged" -- the accent-colored border below is the
     actual "valid target" signal; this is just what keeps the forced fill change from being
     jarring on top of it. */
  .editor-canvas .djs-parent {
    --shape-drop-allowed-fill-color: var(--bg);
    --shape-drop-not-allowed-fill-color: var(--bg);
    --shape-connect-allowed-fill-color: var(--panel-bg);
  }
  /* The vendor stylesheet only ever changes a valid connect target's *fill* (see
     --shape-connect-allowed-fill-color above) -- no border/stroke of its own signals "drop here"
     the way .attach-ok's stroke does for attaching. Added directly, since there's no existing
     --shape-connect-allowed-stroke-color variable to hook for it. */
  .editor-canvas .djs-shape.connect-ok .djs-visual > :nth-child(1) {
    stroke: var(--accent) !important;
    stroke-width: 2px !important;
  }
  .ntrloc-palette-icon, .ntrloc-context-pad-icon {
    display: flex;
    align-items: center;
    justify-content: center;
    width: 100%;
    height: 100%;
    overflow: hidden;
  }
  /* Flyout describing the activity type, to the right of its palette icon on hover -- .entry is
     diagram-js's own 46x46 palette cell (position: static by default in diagram-js.css), so it
     needs position: relative here for the flyout's "left: 100%" to anchor off *this* icon rather
     than some further-up ancestor. Nothing in .djs-palette clips overflow when open (only the
     collapsed/closed state does, per diagram-js.css), so the flyout can safely extend past it.
  */
  .djs-palette .entry.ntrloc-palette-entry {
    position: relative;
  }
  .ntrloc-palette-flyout {
    display: none;
    position: absolute;
    left: 100%;
    top: 50%;
    transform: translateY(-50%);
    margin-left: 10px;
    width: 200px;
    padding: 10px 12px;
    background: var(--panel-bg);
    border: 1px solid var(--border);
    border-radius: 6px;
    box-shadow: 0 4px 14px rgba(0, 0, 0, 0.35);
    text-align: left;
    line-height: 1.4;
    z-index: 20;
    pointer-events: none;
  }
  /* BpmnContextPadProvider.js's Sub-Process loop-type picker -- a plain fixed-position <div>,
     not a diagram-js PopupMenu (never wired into this app's Diagram instance), so it needs its
     own styling rather than inheriting anything from .djs-context-pad above. Appended to
     document.body rather than the shadow-less custom element's own tree, but this component has
     no Shadow DOM (light DOM only -- see NtrlocProcessEditor's class declaration), so this
     injected <style> tag is already global and reaches it fine either way. */
  .ntrloc-loop-type-menu {
    position: fixed;
    z-index: 50;
    display: flex;
    flex-direction: column;
    min-width: 200px;
    padding: 4px;
    background: var(--panel-bg);
    border: 1px solid var(--border);
    border-radius: 6px;
    box-shadow: 0 4px 14px rgba(0, 0, 0, 0.35);
  }
  .ntrloc-loop-type-option {
    display: block;
    width: 100%;
    padding: 6px 10px;
    background: none;
    border: none;
    border-radius: 4px;
    color: var(--text);
    font-size: 12px;
    text-align: left;
    cursor: pointer;
  }
  .ntrloc-loop-type-option:hover {
    background: var(--border);
  }
  .ntrloc-loop-type-option.selected {
    color: var(--accent);
    font-weight: 600;
  }
  .ntrloc-palette-flyout strong {
    display: block;
    margin-bottom: 4px;
    font-size: 12px;
    color: var(--text);
  }
  .ntrloc-palette-flyout p {
    margin: 0;
    font-size: 12px;
    color: var(--muted);
  }
  .djs-palette .entry.ntrloc-palette-entry:hover .ntrloc-palette-flyout {
    display: block;
  }

  /* features/bendpoints (bend-point/segment handles on a connection) -- diagram-js's own
     assets/diagram-js.css defines all of this against light-theme custom properties this app
     never loads (same "only touch what's actually used" reasoning as the palette/context-pad
     rules above); re-declared here 1:1 against this app's own theme variables instead. */
  .djs-segment-dragger,
  .djs-bendpoint {
    display: none;
  }
  .djs-segment-dragger .djs-visual {
    display: none;
    fill: var(--accent);
    stroke: var(--bg);
    stroke-width: 1px;
    stroke-opacity: 1;
  }
  .djs-segment-dragger:hover .djs-visual {
    display: block;
  }
  .djs-bendpoint .djs-visual {
    fill: var(--accent);
    stroke: var(--bg);
    stroke-width: 1px;
  }
  .djs-segment-dragger:hover,
  .djs-bendpoints.hover .djs-segment-dragger,
  .djs-bendpoints.selected .djs-segment-dragger,
  .djs-bendpoint:hover,
  .djs-bendpoints.hover .djs-bendpoint,
  .djs-bendpoints.selected .djs-bendpoint {
    display: block;
  }
  .djs-drag-active .djs-bendpoints * {
    display: none;
  }
  .djs-bendpoints:not(.hover) .floating {
    display: none;
  }
  .djs-bendpoint.floating:not(.positioned) {
    display: none;
  }
  .djs-segment-dragger:hover .djs-visual,
  .djs-segment-dragger.djs-dragging .djs-visual,
  .djs-bendpoint:hover .djs-visual,
  .djs-bendpoint.floating .djs-visual {
    fill: var(--accent);
    stroke: var(--bg);
    stroke-opacity: 1;
  }
  .djs-bendpoint.floating .djs-hit {
    pointer-events: none;
  }
  .djs-segment-dragger .djs-hit,
  .djs-bendpoint .djs-hit {
    fill: none;
    pointer-events: all;
  }
  .djs-segment-dragger.horizontal .djs-hit {
    cursor: ns-resize;
  }
  .djs-segment-dragger.vertical .djs-hit {
    cursor: ew-resize;
  }
  .djs-segment-dragger.djs-dragging .djs-hit {
    pointer-events: none;
  }
  .djs-multi-select .djs-bendpoint,
  .djs-multi-select .djs-segment-dragger,
  .connect-ok .djs-bendpoint,
  .connect-not-ok .djs-bendpoint,
  .drop-ok .djs-bendpoint,
  .drop-not-ok .djs-bendpoint {
    display: none !important;
  }
  .djs-segment-dragger.djs-dragging,
  .djs-bendpoint.djs-dragging {
    display: block;
    opacity: 1.0;
  }
`);

class NtrlocProcessEditor extends HTMLElement {
  constructor() {
    super();
    this._definitionId = null;
    this._isNew = false;
    this._processId = null;
    this._processName = null;
    this._processDescription = null;
    // {name, type, required} triples -- see bpmn-io.js's readProcessVariables/buildIoSpecification
    // for the real bpmn:InputOutputSpecification shape this round-trips through.
    this._processVariables = [];
    this._diagram = null;
    this._moddle = null;
    this._selectedElement = null;
    this._scriptEditor = null;
    // Memoized promises (not resolved values) -- populated on first User Task selection, reused
    // for the rest of the session rather than re-fetched on every panel render.
    this._usersPromise = null;
    this._processGroupsPromise = null;
    this._decisionsPromise = null;
    this._processDefinitionsPromise = null;
    this._status = null;
    // Running a process only makes sense against exactly what's deployed -- true from load,
    // flipped by any modeling command (see 'commandStack.changed' below) or a name edit (the one
    // change that bypasses the command stack, see updateSelectedName), cleared again on a
    // successful save.
    this._dirty = false;
  }

  connectedCallback() {
    this._definitionId = this.dataset.definitionId;
    // Same reserved-placeholder-prefix detection as ntrloc-decision-table-editor.js -- process
    // definition ids are plain UUIDs assigned by the backend, no composite format to infer "new"
    // from otherwise.
    this._isNew = !this._definitionId || this._definitionId.startsWith('new-process-');
    this.renderShell();
    if (this._isNew) {
      // Pre-filled, not left blank -- see short-id.js's own comment. Still a plain editable text
      // input until first save (unchanged), so a deliberate, memorable id is one edit away for
      // whoever wants one; this is just a sensible default for whoever doesn't.
      this._processId = generateShortId('p');
      this._processName = 'New Process';
      this._processDescription = '';
      this._processVariables = [];
      this.initCanvas();
      this.centerDiagram();
      this.renderPanel();
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
    if (this._scriptEditor) {
      this._scriptEditor.destroy();
      this._scriptEditor = null;
    }
  }

  // Builds the diagram-js Diagram instance and wires its cross-cutting listeners -- shared by
  // both load() (an existing definition, immediately followed by importXml against the fetched
  // XML) and connectedCallback's _isNew branch (a brand-new one, left as the empty canvas this
  // alone produces, ready for the palette). Kept as its own method rather than inlined in load()
  // so "new process" doesn't need a synthetic XML string round-tripped through importXml just to
  // get a working canvas -- diagram-js's own Canvas already creates an empty root on construction.
  initCanvas() {
    const BpmnModdleCtor = window.BpmnModdle;
    this._moddle = new BpmnModdleCtor();

    const container = this.querySelector('.editor-canvas');
    // Generated before the Diagram exists (see arrowhead-marker.js's header comment for why) --
    // this exact string is what BpmnRenderer.js's marker-end ends up referencing.
    const arrowheadMarkerId = nextArrowheadMarkerId('ntrloc-sequenceflow-end');
    this._diagram = new Diagram({
      canvas: { container },
      modules: [
        SelectionModule, OutlineModule, MoveModule, CreateModule, ConnectModule,
        ModelingModule, PaletteModule, ContextPadModule, RulesModule,
        MoveCanvasModule, ZoomScrollModule, HoverFixModule, BendpointsModule, ConnectionPreviewModule,
        {
          __init__: [
            'bpmnRenderer', 'bpmnPaletteProvider', 'bpmnRuleProvider', 'bpmnContextPadProvider',
            'bpmnConnectionMovePreview', 'connectionRouteOriginBehavior', 'subProcessAutoResizeBehavior',
            'subProcessAutoSeedBehavior', 'subProcessToggleBehavior', 'removeCrossBoundaryConnectionsBehavior',
          ],
          moddle: ['value', this._moddle],
          arrowheadMarkerId: ['value', arrowheadMarkerId],
          bpmnRenderer: ['type', BpmnRenderer],
          bpmnPaletteProvider: ['type', BpmnPaletteProvider],
          bpmnRuleProvider: ['type', BpmnRuleProvider],
          bpmnContextPadProvider: ['type', BpmnContextPadProvider],
          bpmnConnectionMovePreview: ['type', ConnectionMovePreview],
          connectionRouteOriginBehavior: ['type', ConnectionRouteOriginBehavior],
          subProcessAutoResizeBehavior: ['type', SubProcessAutoResizeBehavior],
          subProcessAutoSeedBehavior: ['type', SubProcessAutoSeedBehavior],
          subProcessToggleBehavior: ['type', SubProcessToggleBehavior],
          removeCrossBoundaryConnectionsBehavior: ['type', RemoveCrossBoundaryConnectionsBehavior],
          // Overrides ModelingModule's default 'layouter' binding (BaseLayouter, plain
          // center-to-center, no cropping) -- must be listed after ModelingModule above for
          // this override to win; Didi's provider registration is last-write-wins per key.
          layouter: ['type', ConnectionLayouter],
          // Referenced under this exact key (optionally, injector.get(..., false)) by both
          // ConnectionSegmentMove.js (crops a segment-drag's waypoints to the shape boundary --
          // silently skips cropping entirely without this) and ConnectionPreview.js (crops the
          // live drag preview the same way). Same CroppingConnectionDocking class
          // ConnectionLayouter already uses internally, exposed here under the DI name these
          // vendored modules look it up by.
          connectionDocking: ['type', CroppingConnectionDocking],
        },
      ],
    });

    addArrowheadMarker(container, arrowheadMarkerId);

    this._diagram.get('eventBus').on('selection.changed', (event) => {
      this._selectedElement = (event.newSelection && event.newSelection[0]) || null;
      this.renderPanel();
    });

    // Every modeling command (move, create, connect, delete, ...) fires this -- the one
    // exception is the name-edit path (updateSelectedName), which bypasses the command stack
    // entirely and marks dirty itself.
    this._diagram.get('eventBus').on('commandStack.changed', () => this.markDirty());

    // BpmnContextPadProvider.js's loop-type picker mutates businessObject.loopCharacteristics
    // directly (not through a modeling command, same as the Description field's direct
    // bo.documentation writes below) -- commandStack.changed above never fires for it, so it
    // fires this instead. Re-renders the panel too: the loop type just changed which
    // subprocess-specific fields (condition / collection+variable) belong on screen.
    this._diagram.get('eventBus').on('ntrloc.elementPropertiesChanged', () => {
      this.markDirty();
      this.renderPanel();
    });

    // BpmnContextPadProvider.js's "Go to Called Process"/"Go to Decision Table" context-pad
    // entry -- it knows the referenced key (calledElement / decisionTableReferenceKey) but not
    // the id that key resolves to today, so it hands the raw {kind, key} off here rather than
    // duplicating loadProcessDefinitions()/loadDecisionTables()'s fetch-and-cache. Resolving to
    // an id and re-dispatching as a bubbling DOM event (not just handling the navigation here
    // directly) keeps this element the single place ntrloc-tab-workspace.js needs to listen on
    // for "open this other definition" -- the same 'navigate-to-definition' contract, regardless
    // of whether it originated from the inspector (nothing does anymore) or the context pad.
    this._diagram.get('eventBus').on('ntrloc.jumpToReference', ({ kind, key }) => {
      if (!key) return;
      const list = kind === 'process' ? this.loadProcessDefinitions() : this.loadDecisionTables();
      list
        .then((items) => {
          const match = items.find((d) => d.key === key);
          if (!match) return;
          this.dispatchEvent(new CustomEvent('navigate-to-definition', {
            bubbles: true,
            detail: { id: match.id, title: match.name ?? match.key, resourceType: kind === 'process' ? 'bpmn' : 'dmn' },
          }));
        })
        .catch((e) => this.setStatus('Failed to resolve reference: ' + e.message, true));
    });
  }

  async load() {
    try {
      const response = await fetch(`/api/admin/process/definitions/xml?id=${encodeURIComponent(this._definitionId)}`, { credentials: 'include' });
      if (!response.ok) throw new Error('Request failed: ' + response.status);
      const xml = await response.text();

      this.initCanvas();

      const process = await importXml(this._moddle, this._diagram.get('elementFactory'), this._diagram.get('canvas'), xml);
      this._processId = process.id;
      this._processName = process.name;
      // bpmn:Process is just another businessObject with a documentation array -- same helper
      // every element's own Description field already reads with (elementDocumentation below).
      this._processDescription = elementDocumentation(process);
      this._processVariables = readProcessVariables(process);
      // Flowable-namespace, not core BPMN -- same raw $attrs passthrough
      // renderUserTaskFields's candidateUsers/candidateGroups already relies on (bpmn-moddle has
      // no Flowable extension schema loaded). Read by ProcessRunAsUserListener at process-start
      // time to resolve a principal for triggers with no HTTP caller (a timer, e.g.) -- see
      // docs/ntrloc-workflow-summary.md.
      this._processRunAsUser = process.$attrs['flowable:runAsUser'] || '';

      this.centerDiagram();
      this.renderPanel();
      this.reportDirty(false);
    } catch (e) {
      this.setStatus('Failed to load process: ' + e.message, true);
    }
  }

  async save() {
    if (!this._diagram) return;
    if (!this._processId) {
      this.setStatus('Enter a process ID before saving.', true);
      return;
    }
    try {
      this.setStatus('Saving...', false);
      const requestedId = this._processId;
      const xml = await exportXml(
          this._moddle, this._diagram.get('elementRegistry'), this._diagram.get('canvas'),
          requestedId, this._processName, this._processDescription, this._processVariables,
          this._processRunAsUser);

      // First save of a brand-new process goes through the keyless create endpoint, which is the
      // one place the server actually enforces uniqueness (see ProcessAdminController's own
      // comment) -- the id the admin-ui pre-filled or typed is only a candidate there, not a
      // guarantee. An already-established process (this._isNew already false) keeps using the
      // versions endpoint exactly as before: an existing id there always means "add a version,"
      // never a collision to resolve.
      const response = this._isNew
        ? await fetch(`/api/admin/process/definitions?candidateKey=${encodeURIComponent(requestedId)}`, {
            method: 'POST',
            credentials: 'include',
            headers: { 'Content-Type': 'application/xml' },
            body: xml,
          })
        : await fetch(`/api/admin/process/definitions/${encodeURIComponent(requestedId)}/versions`, {
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
      // Point Run at the version we just deployed (not the one this editor was opened with) --
      // running should mean "run what's on screen", and what's on screen is now this version.
      this._definitionId = deployed.id;
      this._isNew = false;
      // Server is authoritative on the id -- update our own display if it had to reassign one due
      // to a collision (only possible on the create path above).
      this._processId = deployed.key;
      // Re-render so the (permanently disabled) Process ID field picks up the possibly-reassigned
      // value if that view happens to be showing.
      this.renderPanel();
      this.reportDirty(false);
      this.setStatus(deployed.key !== requestedId
        ? `Process ID "${requestedId}" was already in use -- assigned "${deployed.key}" instead. Saved as version ${deployed.version}.`
        : `Saved as version ${deployed.version}.`, false);
      // Same bubbling-event contract as dirty-changed -- lets a host that references a process by
      // key (ntrloc-state-machine-editor.js's entry/exit/transition process fields) learn what key
      // to store without reaching into this editor's private fields.
      this.dispatchEvent(new CustomEvent('process-saved', { bubbles: true, detail: { key: this._processId, id: deployed.id, version: deployed.version } }));
    } catch (e) {
      this.setStatus('Failed to save: ' + e.message, true);
    }
  }

  async run() {
    if (!this._diagram || this._dirty) return;

    // Run is only enabled when !_dirty -- what's declared in memory already matches what's
    // deployed, so there's no need to re-fetch/re-parse the deployed XML just to ask it the same
    // question this._processVariables already answers.
    let variables;
    if (this._processVariables.length > 0) {
      variables = await openRunProcessDialog(this._processVariables);
      if (variables === undefined) return; // cancelled
    } else {
      variables = {};
    }

    try {
      this.setStatus('Running...', false);
      const response = await fetch(`/api/admin/process/definitions/start?id=${encodeURIComponent(this._definitionId)}`, {
        method: 'POST',
        credentials: 'include',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ variables }),
      });

      if (!response.ok) {
        const error = await response.json().catch(() => ({ message: 'Request failed: ' + response.status }));
        throw new Error(error.message || 'Request failed: ' + response.status);
      }

      const instance = await response.json();
      this.setStatus(
        instance.ended
          ? `Process ran to completion (instance ${instance.id}).`
          : `Process instance ${instance.id} started.`,
        false);
    } catch (e) {
      this.setStatus('Failed to run: ' + e.message, true);
    }
  }

  markDirty() {
    this.reportDirty(true);
  }

  // Single place _dirty is ever assigned -- also tells the containing tab (if this editor is
  // hosted inside <ntrloc-tab-workspace>) to update its dirty-dot, via a bubbling event rather
  // than a direct reference back to the workspace, so this component stays unaware of whether
  // it's tabbed at all.
  reportDirty(dirty) {
    this._dirty = dirty;
    this.updateRunButtonState();
    this.dispatchEvent(new CustomEvent('dirty-changed', { bubbles: true, detail: { dirty } }));
  }

  updateRunButtonState() {
    const runButton = this.querySelector('.run-button');
    if (!runButton) return;
    // A brand-new, never-saved process reports _dirty === false too (matches "nothing to save
    // yet" the same way a freshly-loaded existing one matches "matches what's deployed") -- but
    // unlike an existing one, there's genuinely nothing deployed to run until the first Save.
    runButton.disabled = this._dirty || this._isNew;
    runButton.title = this._isNew ? 'Save before running' : (this._dirty ? 'Save your changes before running' : '');
  }

  // Kept as an instance method (not called directly as an import) since ntrloc-processes.js's
  // recenterActiveDiagram() calls it on the tab's content element generically, without knowing
  // which editor type it is -- see diagram-shared/center-diagram.js for the actual logic, shared
  // with the DRD canvas.
  centerDiagram() {
    centerDiagramOnCanvas(this._diagram);
  }

  updateSelectedName(name) {
    if (!this._selectedElement) return;
    this._selectedElement.businessObject.name = name;
    this.rerenderElement(this._selectedElement);
    this.markDirty();
  }

  // No dedicated "re-render just this element" API is wired up (label-editing/change-support,
  // which would give us that, are deliberately not part of the reduced module set) -- dropping
  // and re-adding the element is a simple, correct way to force GraphicsFactory to redraw it
  // with its updated businessObject.name. Canvas.removeShape/removeConnection null out
  // element.parent as part of detaching it (verified against Canvas.js's _removeElement) -- for
  // a child of an expanded Sub-Process, re-adding without passing that parent back explicitly
  // would silently reparent it to root on every rename, so it's captured first.
  rerenderElement(element) {
    const canvas = this._diagram.get('canvas');
    const parent = element.parent;
    if (element.waypoints) {
      canvas.removeConnection(element);
      canvas.addConnection(element, parent);
    } else {
      canvas.removeShape(element);
      canvas.addShape(element, parent);
    }
  }

  setStatus(message, isError) {
    this._status = { message, isError };
    const el = this.querySelector('.editor-status');
    if (el) {
      el.textContent = message;
      el.style.color = isError ? '#e24a4a' : 'var(--muted)';
    }
  }

  renderShell() {
    this.innerHTML = `
      <div class="editor-toolbar">
        <span class="editor-status status"></span>
        <span class="spacer"></span>
        <md-outlined-button class="run-button" title="Save your changes before running" disabled>Run</md-outlined-button>
        <md-filled-button class="save-button">Save</md-filled-button>
      </div>
      <div class="editor-body">
        <div class="editor-canvas"></div>
        <div class="editor-panel"></div>
      </div>
    `;
    this.querySelector('.save-button').addEventListener('click', () => this.save());
    this.querySelector('.run-button').addEventListener('click', () => this.run());
  }

  renderPanel() {
    const panel = this.querySelector('.editor-panel');
    const element = this._selectedElement;

    // Any previous panel's ScriptEditor is about to be orphaned by the innerHTML replacement
    // below (or by there being no element at all) -- its EditorView keeps its own DOM/listeners
    // alive independent of that, so it must be torn down explicitly every time, not just when a
    // *different* Script Task is selected.
    if (this._scriptEditor) {
      this._scriptEditor.destroy();
      this._scriptEditor = null;
    }

    if (!element || !element.businessObject) {
      panel.classList.remove('has-script');
      this.renderProcessPanel(panel);
      return;
    }

    const bo = element.businessObject;
    const isScriptTask = bo.$type === SCRIPT_TASK;
    const isUserTask = bo.$type === USER_TASK;
    const isDmnTaskSelected = isDmnTask(element);
    const isCallActivitySelected = bo.$type === CALL_ACTIVITY;
    const isTimerStartEventSelected = isTimerStartEvent(element);
    const isSubProcessSelected = isSubProcess(element);
    const loopType = isSubProcessSelected ? getLoopType(element) : null;
    panel.classList.toggle('has-script', isScriptTask);
    const typeLabel = isDmnTaskSelected ? 'DMN Task'
      : isTimerStartEventSelected ? 'Timer Start Event'
      : friendlyTypeName(bo.$type);
    panel.innerHTML = `
      <label>Type</label>
      <div class="field-value">${escapeHtml(typeLabel)}</div>
      <label>ID</label>
      <div class="field-value">${escapeHtml(bo.id)}</div>
      <label>Name</label>
      <input type="text" class="name-input" value="${escapeHtml(bo.name || '')}">
      <label>Description</label>
      <textarea class="description-input" rows="3" placeholder="Notes about this element, saved as its BPMN documentation.">${escapeHtml(elementDocumentation(bo))}</textarea>
      ${isScriptTask ? `
        <label>Script Format</label>
        <select class="script-format-select">
          <option value="groovy">Groovy</option>
          <option value="javascript">JavaScript</option>
        </select>
        <label>Script</label>
        <div class="script-editor-container"></div>
      ` : ''}
      ${isUserTask ? `
        <label>Assignee</label>
        <md-outlined-select class="assignee-select">
          <md-select-option value="">
            <div slot="headline">(unassigned)</div>
          </md-select-option>
        </md-outlined-select>
        <label>Candidate Groups</label>
        <div class="candidate-groups-list"></div>
        <label>Candidate Users</label>
        <input type="text" class="candidate-users-input" placeholder="comma-separated, e.g. localuser,admin">
      ` : ''}
      ${isDmnTaskSelected ? `
        <label>Decision Table</label>
        <md-outlined-select class="decision-select">
          <md-select-option value="">
            <div slot="headline">(none selected)</div>
          </md-select-option>
        </md-outlined-select>
      ` : ''}
      ${isCallActivitySelected ? `
        <label>Called Process</label>
        <md-outlined-select class="call-activity-select">
          <md-select-option value="">
            <div slot="headline">(none selected)</div>
          </md-select-option>
        </md-outlined-select>
      ` : ''}
      ${isTimerStartEventSelected ? `
        <label>Timer Type</label>
        <select class="timer-type-select">
          <option value="timeDuration">Duration (once, after a delay)</option>
          <option value="timeDate">Date (once, at a fixed time)</option>
          <option value="timeCycle">Cycle (repeating)</option>
        </select>
        <label>Expression</label>
        <input type="text" class="timer-expression-input" placeholder="e.g. PT10M, P1D, R3/PT1H">
      ` : ''}
      ${loopType === LOOP_TYPE_STANDARD ? `
        <label>Loop Condition</label>
        <input type="text" class="loop-condition-input" placeholder="e.g. ${'${'}retryCount >= 3${'}'}">
      ` : ''}
      ${loopType === LOOP_TYPE_MI_PARALLEL || loopType === LOOP_TYPE_MI_SEQUENTIAL ? `
        <label>Collection</label>
        <input type="text" class="mi-collection-input" placeholder="process variable to iterate, e.g. tradeProducts">
        <label>Element Variable</label>
        <input type="text" class="mi-element-variable-input" placeholder="name for the current item, e.g. product">
      ` : ''}
    `;
    panel.querySelector('.name-input').addEventListener('change', (event) => {
      this.updateSelectedName(event.target.value);
    });
    panel.querySelector('.description-input').addEventListener('change', (event) => {
      setElementDocumentation(this._moddle, bo, event.target.value);
      this.markDirty();
    });

    if (isScriptTask) this.renderScriptEditor(panel, bo);
    if (isUserTask) this.renderUserTaskFields(panel, bo);
    if (isDmnTaskSelected) this.renderDmnTaskFields(panel, bo);
    if (isCallActivitySelected) this.renderCallActivityFields(panel, bo);
    if (isTimerStartEventSelected) this.renderTimerStartEventFields(panel, bo);
    if (loopType === LOOP_TYPE_STANDARD) this.renderStandardLoopFields(panel, bo);
    if (loopType === LOOP_TYPE_MI_PARALLEL || loopType === LOOP_TYPE_MI_SEQUENTIAL) this.renderMultiInstanceFields(panel, bo);
  }

  // What the inspector shows with nothing selected -- the process itself, not "nothing to see
  // here". Name/Process ID used to live in the toolbar; moved here so this state is useful
  // instead of empty, and so the process can carry a Description the same way every element
  // already can (elementDocumentation/setElementDocumentation below, reused as-is: a
  // bpmn:Process is just another businessObject with a documentation array). Process ID is
  // always disabled now -- system-generated (short-id.js pre-fill, ProcessAdminController.
  // createDefinition reassigns on collision) rather than admin-chosen, so unlike Name/
  // Description/Run As User there's no window where typing into it does anything.
  renderProcessPanel(panel) {
    panel.innerHTML = `
      <label>Name</label>
      <input type="text" class="process-name-input" value="${escapeHtml(this._processName || '')}">
      <label>Process ID</label>
      <input type="text" class="process-key-input" title="System-generated, not editable"
             value="${escapeHtml(this._processId || '')}" disabled>
      <label>Description</label>
      <textarea class="process-description-input" rows="4"
                placeholder="Notes about this process, saved as its BPMN documentation.">${escapeHtml(this._processDescription || '')}</textarea>
      <label>Run As User</label>
      <input type="text" class="process-run-as-user-input" placeholder="external ID, e.g. admin -- leave blank if this process is only ever started by a caller"
             value="${escapeHtml(this._processRunAsUser || '')}">
      <p class="status">Used only when this process starts with no caller to take a principal
        from (a timer, e.g.) and something in it needs one -- a caller-started run always uses the
        real caller instead, regardless of this setting.</p>
      <label>Required Variables</label>
      ${this._processVariables.length > 0 ? `
        <table class="variables-table">
          <thead><tr><th>Name</th><th>Type</th><th>Req.</th><th></th></tr></thead>
          <tbody>
            ${this._processVariables.map((v, index) => `
              <tr data-index="${index}">
                <td><input class="variable-name-input" value="${escapeHtml(v.name)}"></td>
                <td>
                  <select class="variable-type-select">
                    ${PROCESS_VARIABLE_TYPES.map((t) => `<option value="${t}" ${t === v.type ? 'selected' : ''}>${t}</option>`).join('')}
                  </select>
                </td>
                <td><input type="checkbox" class="variable-required-checkbox" ${v.required ? 'checked' : ''}></td>
                <td><md-text-button class="remove-variable-button">Remove</md-text-button></td>
              </tr>
            `).join('')}
          </tbody>
        </table>
      ` : '<p class="status">None declared -- this process starts with whatever variables the caller happens to send.</p>'}
      <div class="add-variable-row">
        <div class="add-variable-fields">
          <input class="new-variable-name" placeholder="name">
          <select class="new-variable-type">
            ${PROCESS_VARIABLE_TYPES.map((t) => `<option value="${t}">${t}</option>`).join('')}
          </select>
        </div>
        <div class="add-variable-actions">
          <label class="new-variable-required-label"><input type="checkbox" class="new-variable-required" checked> Required</label>
          <md-outlined-button class="add-variable-button">Add</md-outlined-button>
        </div>
      </div>
    `;
    panel.querySelector('.process-name-input').addEventListener('change', (event) => {
      this._processName = event.target.value;
      this.markDirty();
    });
    panel.querySelector('.process-description-input').addEventListener('change', (event) => {
      this._processDescription = event.target.value;
      this.markDirty();
    });
    panel.querySelector('.process-run-as-user-input').addEventListener('change', (event) => {
      this._processRunAsUser = event.target.value.trim();
      this.markDirty();
    });

    panel.querySelectorAll('.variables-table tr[data-index]').forEach((row) => {
      const index = Number(row.dataset.index);
      row.querySelector('.variable-name-input').addEventListener('change', (event) => {
        this._processVariables[index].name = event.target.value.trim();
        this.markDirty();
      });
      row.querySelector('.variable-type-select').addEventListener('change', (event) => {
        this._processVariables[index].type = event.target.value;
        this.markDirty();
      });
      row.querySelector('.variable-required-checkbox').addEventListener('change', (event) => {
        this._processVariables[index].required = event.target.checked;
        this.markDirty();
      });
      row.querySelector('.remove-variable-button').addEventListener('click', () => {
        this._processVariables.splice(index, 1);
        this.markDirty();
        this.renderProcessPanel(panel);
      });
    });

    panel.querySelector('.add-variable-button').addEventListener('click', () => {
      const nameInput = panel.querySelector('.new-variable-name');
      const name = nameInput.value.trim();
      if (!name) return;
      this._processVariables.push({
        name,
        type: panel.querySelector('.new-variable-type').value,
        required: panel.querySelector('.new-variable-required').checked,
      });
      this.markDirty();
      this.renderProcessPanel(panel);
    });
  }

  // Split out of renderPanel purely because ScriptEditor needs its container already attached to
  // the DOM (CodeMirror measures/mounts into it immediately) -- this runs right after the
  // innerHTML assignment above puts that container in place.
  renderScriptEditor(panel, bo) {
    const language = bo.scriptFormat || 'groovy';
    const formatSelect = panel.querySelector('.script-format-select');
    formatSelect.value = language;

    this._scriptEditor = new ScriptEditor(panel.querySelector('.script-editor-container'), {
      doc: bo.script || '',
      language,
      onChange: (script) => {
        bo.script = script;
        this.markDirty();
      },
    });

    formatSelect.addEventListener('change', (event) => {
      bo.scriptFormat = event.target.value;
      this._scriptEditor.setLanguage(event.target.value);
      this.markDirty();
    });
  }

  // assignee/candidateUsers/candidateGroups are Flowable-namespace attributes, not core BPMN --
  // bpmn-moddle has no Flowable extension schema loaded, so it preserves them as raw passthrough
  // on businessObject.$attrs instead of real modeled properties (same mechanism the sayHello
  // service task's flowable:delegateExpression already relies on -- see bpmn-io.js's export note).
  // $attrs itself is a non-reassignable property moddle already initializes on every element
  // (both moddle.create() and XML-imported ones) -- only its contents can be mutated.
  renderUserTaskFields(panel, bo) {
    const attrs = bo.$attrs;

    const candidateUsersInput = panel.querySelector('.candidate-users-input');
    candidateUsersInput.value = attrs['flowable:candidateUsers'] || '';
    candidateUsersInput.addEventListener('change', (event) => {
      attrs['flowable:candidateUsers'] = event.target.value;
      this.markDirty();
    });

    // md-outlined-select mirrors ntrloc-search.js's own item-type/sort-field selects: options
    // are md-select-option elements with a slotted "headline" div, selection read the same way
    // as a native <select> (a 'change' event, event.target.value).
    const assigneeSelect = panel.querySelector('.assignee-select');
    assigneeSelect.addEventListener('change', (event) => {
      attrs['flowable:assignee'] = event.target.value;
      this.markDirty();
    });
    this.loadUsers()
      .then((users) => {
        const current = attrs['flowable:assignee'] || '';
        assigneeSelect.innerHTML = `
          <md-select-option value="" ${!current ? 'selected' : ''}>
            <div slot="headline">(unassigned)</div>
          </md-select-option>
          ${users.map((user) => `
            <md-select-option value="${escapeHtml(user.externalId)}" ${current === user.externalId ? 'selected' : ''}>
              <div slot="headline">${escapeHtml(user.displayName)}</div>
            </md-select-option>
          `).join('')}
        `;
        // Newly-inserted <md-select-option> children need one microtask to finish their own
        // upgrade/registration with the parent select before setting .value actually updates its
        // closed-state display -- an immediate same-tick set silently leaves it blank even though
        // the correct option carries `selected` in the DOM (verified live; see
        // renderDmnTaskFields' decision-select for the identical issue/fix).
        queueMicrotask(() => { assigneeSelect.value = current; });
      })
      .catch((e) => this.setStatus('Failed to load users: ' + e.message, true));

    // Material has no multi-select dropdown -- one md-checkbox per group is the idiomatic
    // Material equivalent of "pick multiple items from a list" instead.
    const groupsList = panel.querySelector('.candidate-groups-list');
    this.loadProcessGroups()
      .then((groups) => {
        const selected = new Set(
            (attrs['flowable:candidateGroups'] || '').split(',').map((s) => s.trim()).filter(Boolean));
        groupsList.innerHTML = groups.map((group) => `
          <label class="candidate-group-row">
            <md-checkbox value="${escapeHtml(group.name)}" ${selected.has(group.name) ? 'checked' : ''}></md-checkbox>
            ${escapeHtml(group.name)}
          </label>
        `).join('');
        groupsList.querySelectorAll('md-checkbox').forEach((checkbox) => {
          checkbox.addEventListener('change', () => {
            const checked = Array.from(groupsList.querySelectorAll('md-checkbox'))
                .filter((c) => c.checked)
                .map((c) => c.value);
            attrs['flowable:candidateGroups'] = checked.join(',');
            this.markDirty();
          });
        });
      })
      .catch((e) => this.setStatus('Failed to load process groups: ' + e.message, true));
  }

  renderDmnTaskFields(panel, bo) {
    const select = panel.querySelector('.decision-select');
    select.addEventListener('change', (event) => {
      this.setDecisionTableReferenceKey(bo, event.target.value);
      this.markDirty();
    });
    this.loadDecisionTables()
      .then((decisions) => {
        const current = getDecisionTableReferenceKey(bo);
        // De-duplicated by key -- multiple deployed versions share a key, and
        // decisionTableReferenceKey always resolves to the latest version at runtime (see
        // DmnActivityBehavior), so only one entry per key is meaningful to offer here.
        const byKey = new Map();
        decisions.forEach((d) => { if (!byKey.has(d.key)) byKey.set(d.key, d); });
        select.innerHTML = `
          <md-select-option value="" ${!current ? 'selected' : ''}>
            <div slot="headline">(none selected)</div>
          </md-select-option>
          ${Array.from(byKey.values()).map((d) => `
            <md-select-option value="${escapeHtml(d.key)}" ${current === d.key ? 'selected' : ''}>
              <div slot="headline">${escapeHtml(d.name ?? d.key)}</div>
            </md-select-option>
          `).join('')}
        `;
        // md-outlined-select's closed-state display doesn't pick up a pre-selected child from a
        // raw innerHTML injection, and setting .value in the very same tick isn't enough either --
        // the newly-inserted <md-select-option> children are themselves custom elements that need
        // one microtask to finish their own upgrade/registration with the parent select before its
        // .value setter can find and display them (verified live: a queueMicrotask delay fixes it,
        // an immediate same-tick set does not). See renderUserTaskFields' assignee-select for the
        // identical fix, same root cause.
        queueMicrotask(() => { select.value = current; });
      })
      .catch((e) => this.setStatus('Failed to load decision tables: ' + e.message, true));
  }

  // Writes the DMN Task's decisionTableReferenceKey field extension
  // (<flowable:field name="decisionTableReferenceKey"><flowable:string>KEY</flowable:string>
  // </flowable:field>, nested inside <extensionElements>) -- unlike every other Flowable
  // attribute this editor handles (assignee, candidateGroups, ...), flowable:field has no flat-
  // attribute form, so this can't go through businessObject.$attrs the way those do. Goes through
  // moddle's generic createAny()/$body/$children convention, verified directly against the
  // vendored bpmn-moddle bundle's own serializer (parseGeneric reads $body/$children/plain-
  // properties back out) -- not assumed. The read side (getDecisionTableReferenceKey) lives in
  // bpmn-elements.js now -- BpmnContextPadProvider.js's "Go to Decision Table" entry needs it too,
  // and it needs no moddle instance the way this write side does. extensionElements itself IS a
  // real schema type (moddle.create), but the field/string elements inside it are
  // foreign-namespace and must use createAny().
  setDecisionTableReferenceKey(bo, key) {
    if (!bo.extensionElements) {
      bo.extensionElements = this._moddle.create('bpmn:ExtensionElements', { values: [] });
    }
    const values = bo.extensionElements.values;
    let fieldEl = values.find((v) => v.$type === 'flowable:field' && v.name === 'decisionTableReferenceKey');
    if (!fieldEl) {
      fieldEl = this._moddle.createAny('flowable:field', 'http://flowable.org/bpmn', { name: 'decisionTableReferenceKey' });
      values.push(fieldEl);
    }
    const stringEl = this._moddle.createAny('flowable:string', 'http://flowable.org/bpmn', {});
    stringEl.$body = key;
    fieldEl.$children = [stringEl];
  }

  // Mirrors renderDmnTaskFields almost verbatim -- same select/microtask/dedupe-by-key pattern,
  // just against process definitions instead of decisions. calledElement is real core BPMN
  // schema on bpmn:CallActivity (verified against the vendored bpmn-moddle bundle and against
  // Flowable's CallActivityBehavior, which defaults to resolving it as a process definition key
  // when no flowable:calledElementType is set) -- unlike decisionTableReferenceKey, this needs no
  // extensionElements/createAny indirection, a plain property assignment round-trips correctly.
  renderCallActivityFields(panel, bo) {
    const select = panel.querySelector('.call-activity-select');
    select.addEventListener('change', (event) => {
      bo.calledElement = event.target.value || undefined;
      this.markDirty();
    });
    this.loadProcessDefinitions()
      .then((definitions) => {
        const current = bo.calledElement || '';
        const byKey = new Map();
        definitions.forEach((d) => { if (!byKey.has(d.key)) byKey.set(d.key, d); });
        select.innerHTML = `
          <md-select-option value="" ${!current ? 'selected' : ''}>
            <div slot="headline">(none selected)</div>
          </md-select-option>
          ${Array.from(byKey.values()).map((d) => `
            <md-select-option value="${escapeHtml(d.key)}" ${current === d.key ? 'selected' : ''}>
              <div slot="headline">${escapeHtml(d.name ?? d.key)}</div>
            </md-select-option>
          `).join('')}
        `;
        // Same fix as renderDmnTaskFields' decision-select / renderUserTaskFields' assignee-select
        // -- newly-inserted <md-select-option> children need a microtask to finish their own
        // upgrade before the parent select's .value setter can find and display them.
        queueMicrotask(() => { select.value = current; });
      })
      .catch((e) => this.setStatus('Failed to load process definitions: ' + e.message, true));
  }

  // A bpmn:TimerEventDefinition has three mutually-exclusive timing fields (timeDuration/
  // timeDate/timeCycle, each a bpmn:FormalExpression) -- exactly one is ever set at a time, which
  // is what the type <select> switches between; the text input always edits whichever one that
  // currently is, so switching type doesn't lose what's already been typed.
  //
  // Placeholder is keyed per field (not one static hint covering all three) after a real
  // deploy failure this shape produced: "PT2M" is exactly correct for Duration, but typed into
  // Cycle it's missing the leading "R/" that marks it as an ISO 8601 *repeating* interval --
  // without that prefix Flowable's business calendar falls back to treating the whole string as
  // a Quartz cron expression instead, which "PT2M" isn't valid as either, so it fails at deploy
  // time with a "Failed to parse cron expression" error that gives no hint the field itself was
  // the right idea, just missing three characters. A single combined placeholder listing all
  // three formats at once (what this used to show) reads as "any of these work here", when only
  // one of them actually does for whichever type is currently selected.
  static TIMER_FIELD_PLACEHOLDERS = {
    timeDuration: 'e.g. PT10M, P1D, PT2H30M',
    timeDate: 'e.g. 2026-12-31T09:00:00',
    timeCycle: 'e.g. R/PT2M, R3/PT1H (R/... = repeat forever, R3/... = 3 times)',
  };

  renderTimerStartEventFields(panel, bo) {
    const timerDef = bo.eventDefinitions.find((ed) => ed.$type === 'bpmn:TimerEventDefinition');
    const typeSelect = panel.querySelector('.timer-type-select');
    const expressionInput = panel.querySelector('.timer-expression-input');

    const TIMER_FIELDS = ['timeDuration', 'timeDate', 'timeCycle'];
    const currentField = TIMER_FIELDS.find((f) => timerDef[f]) || 'timeDuration';
    typeSelect.value = currentField;
    expressionInput.value = (timerDef[currentField] && timerDef[currentField].body) || '';
    expressionInput.placeholder = NtrlocProcessEditor.TIMER_FIELD_PLACEHOLDERS[currentField];

    function setExpression(field, body) {
      TIMER_FIELDS.forEach((f) => { timerDef[f] = undefined; });
      if (body) {
        timerDef[field] = this._moddle.create('bpmn:FormalExpression', { body });
      }
    }

    typeSelect.addEventListener('change', (event) => {
      expressionInput.placeholder = NtrlocProcessEditor.TIMER_FIELD_PLACEHOLDERS[event.target.value];
      setExpression.call(this, event.target.value, expressionInput.value.trim());
      this.markDirty();
    });
    expressionInput.addEventListener('change', (event) => {
      setExpression.call(this, typeSelect.value, event.target.value.trim());
      this.markDirty();
    });
  }

  // bpmn:StandardLoopCharacteristics.loopCondition is a bpmn:FormalExpression, same shape as a
  // Timer field above -- testBefore is fixed to false at creation time (setLoopType,
  // bpmn-elements.js) and not exposed here: this app only models the post-test/"repeat until"
  // form of the standard loop, not the pre-test/"while" form.
  renderStandardLoopFields(panel, bo) {
    const conditionInput = panel.querySelector('.loop-condition-input');
    conditionInput.value = (bo.loopCharacteristics.loopCondition && bo.loopCharacteristics.loopCondition.body) || '';
    conditionInput.addEventListener('change', (event) => {
      bo.loopCharacteristics.loopCondition = this._moddle.create('bpmn:FormalExpression', {
        body: event.target.value.trim(),
      });
      this.markDirty();
    });
  }

  // flowable:collection/flowable:elementVariable on bpmn:MultiInstanceLoopCharacteristics are
  // Flowable-namespace attributes, same $attrs passthrough situation as renderUserTaskFields'
  // flowable:assignee/flowable:candidateUsers above (bpmn-moddle has no Flowable extension schema
  // loaded) -- matches the two attributes trade-product-export.bpmn20.xml's "Per Product"
  // subprocess already hand-authors (flowable:collection="tradeProducts"
  // flowable:elementVariable="product").
  renderMultiInstanceFields(panel, bo) {
    const attrs = bo.loopCharacteristics.$attrs;

    const collectionInput = panel.querySelector('.mi-collection-input');
    collectionInput.value = attrs['flowable:collection'] || '';
    collectionInput.addEventListener('change', (event) => {
      attrs['flowable:collection'] = event.target.value.trim();
      this.markDirty();
    });

    const elementVariableInput = panel.querySelector('.mi-element-variable-input');
    elementVariableInput.value = attrs['flowable:elementVariable'] || '';
    elementVariableInput.addEventListener('change', (event) => {
      attrs['flowable:elementVariable'] = event.target.value.trim();
      this.markDirty();
    });
  }

  loadProcessDefinitions() {
    if (!this._processDefinitionsPromise) {
      this._processDefinitionsPromise = fetch('/api/admin/process/definitions', { credentials: 'include' })
          .then((response) => {
            if (!response.ok) throw new Error('Request failed: ' + response.status);
            return response.json();
          });
    }
    return this._processDefinitionsPromise;
  }

  loadDecisionTables() {
    if (!this._decisionsPromise) {
      this._decisionsPromise = fetch('/api/admin/dmn/decisions', { credentials: 'include' })
          .then((response) => {
            if (!response.ok) throw new Error('Request failed: ' + response.status);
            return response.json();
          });
    }
    return this._decisionsPromise;
  }

  loadUsers() {
    if (!this._usersPromise) {
      this._usersPromise = fetch('/api/admin/users', { credentials: 'include' })
          .then((response) => {
            if (!response.ok) throw new Error('Request failed: ' + response.status);
            return response.json();
          });
    }
    return this._usersPromise;
  }

  loadProcessGroups() {
    if (!this._processGroupsPromise) {
      this._processGroupsPromise = fetch('/api/admin/process/groups', { credentials: 'include' })
          .then((response) => {
            if (!response.ok) throw new Error('Request failed: ' + response.status);
            return response.json();
          });
    }
    return this._processGroupsPromise;
  }
}

// bo.$type as-is ("bpmn:ParallelGateway") is accurate but not what a user would call the thing --
// DMN Task is handled separately in renderPanel since it isn't a distinct $type at all.
const FRIENDLY_TYPE_NAMES = {
  'bpmn:ExclusiveGateway': 'Exclusive Gateway',
  'bpmn:ParallelGateway': 'Parallel Gateway',
  'bpmn:CallActivity': 'Call Activity',
  'bpmn:SubProcess': 'Sub-Process',
  'bpmn:StartEvent': 'Start Event',
  'bpmn:EndEvent': 'End Event',
  'bpmn:Task': 'Task',
  'bpmn:ScriptTask': 'Script Task',
  'bpmn:UserTask': 'User Task',
};

function friendlyTypeName(type) {
  return FRIENDLY_TYPE_NAMES[type] || type;
}

// bpmn:documentation is an array (the BPMN spec allows more than one -- e.g. per-locale text),
// but nothing here authors more than one, so only the first is shown/edited; any others an
// imported diagram happened to carry are left untouched on its businessObject and simply not
// surfaced in this panel. .text carries the raw XML content verbatim, including whatever
// indentation/newlines the source file had around it (trade-product-export.bpmn20.xml's own
// <documentation> blocks are a good example) -- trimmed here since a person editing a plain
// textarea has no reason to expect or preserve that formatting.
function elementDocumentation(bo) {
  const doc = bo.documentation && bo.documentation[0];
  return doc ? (doc.text || '').trim() : '';
}

function setElementDocumentation(moddle, bo, text) {
  const trimmed = text.trim();
  if (!trimmed) {
    bo.documentation = [];
    return;
  }
  if (bo.documentation && bo.documentation[0]) {
    bo.documentation[0].text = trimmed;
  } else {
    bo.documentation = [moddle.create('bpmn:Documentation', { text: trimmed })];
  }
}

function escapeHtml(value) {
  const div = document.createElement('div');
  div.textContent = String(value ?? '');
  return div.innerHTML;
}

customElements.define('ntrloc-process-editor', NtrlocProcessEditor);
