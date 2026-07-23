import {
  START_EVENT, END_EVENT, EXCLUSIVE_GATEWAY, PARALLEL_GATEWAY, CALL_ACTIVITY,
  TASK, SCRIPT_TASK, USER_TASK,
} from './bpmn-elements.js';

// Not a real bpmn:* type -- a DMN Task is a bpmn:ServiceTask with flowable:type="dmn" (see
// bpmn-elements.js's isDmnTask), and ICONS below is keyed by raw bpmn type everywhere else, so it
// needs a distinct lookup key of its own rather than colliding with plain Service Tasks.
export const DMN_TASK_ICON_KEY = 'dmn-task';

// The single Sub-Process palette entry is always expanded when dropped (see
// BpmnPaletteProvider.js), so this is the only Sub-Process icon needed -- distinct key rather
// than the bare SUB_PROCESS one since the *canvas* rendering still differs by actual
// collapsed/expanded state after the fact (BpmnRenderer.js), same reasoning as DMN Task above.
export const SUB_PROCESS_EXPANDED_ICON_KEY = 'subprocess-expanded';

// A Timer Start Event is a bpmn:StartEvent carrying a bpmn:TimerEventDefinition child (see
// bpmn-elements.js's isTimerStartEvent) -- same reasoning as DMN Task above, needs its own lookup
// key since it shares bpmn:StartEvent's type with a plain none-start event.
export const TIMER_START_EVENT_ICON_KEY = 'timer-start-event';

// Small original SVG glyphs for the palette (and, for Task, a matching marker drawn on the shape
// itself in BpmnRenderer.js) -- plain geometric shapes matching open BPMN notation concepts, not
// a reproduction of bpmn-font's specific licensed glyph artwork (see BpmnRenderer.js's own note).
// Each mirrors what BpmnRenderer.js actually draws on canvas, just at icon scale: a solid green
// circle for Start, solid red for End, a blue rounded rect with a checklist mark for Task, and an
// orange diamond with a white "X" for the gateway -- fill colors read straight from the same
// --start-fill/--end-fill/--task-fill/--gateway-fill custom properties the canvas shapes use
// (ntrloc-process-editor.js), via inline `style=`, which (unlike a plain attribute) reliably
// resolves var() on markup parsed from an HTML string.
const ICONS = {
  [START_EVENT]: `
    <svg viewBox="0 0 24 24" width="22" height="22">
      <circle cx="12" cy="12" r="9" style="fill: var(--start-fill); stroke: var(--start-stroke); stroke-width: 1.5"/>
    </svg>`,
  [END_EVENT]: `
    <svg viewBox="0 0 24 24" width="22" height="22">
      <circle cx="12" cy="12" r="9" style="fill: var(--end-fill); stroke: var(--end-stroke); stroke-width: 1.5"/>
    </svg>`,
  // Same green fill as a plain Start Event -- a clock face (not a different color) is the BPMN
  // convention for "this is a timer-typed event," matching how the canvas rendering distinguishes
  // it too (BpmnRenderer.js).
  [TIMER_START_EVENT_ICON_KEY]: `
    <svg viewBox="0 0 24 24" width="22" height="22">
      <circle cx="12" cy="12" r="9" style="fill: var(--start-fill); stroke: var(--start-stroke); stroke-width: 1.5"/>
      <circle cx="12" cy="12" r="5.5" fill="none" stroke="white" stroke-width="1.2"/>
      <line x1="12" y1="12" x2="12" y2="8.2" stroke="white" stroke-width="1.2" stroke-linecap="round"/>
      <line x1="12" y1="12" x2="14.5" y2="13.5" stroke="white" stroke-width="1.2" stroke-linecap="round"/>
    </svg>`,
  [TASK]: `
    <svg viewBox="0 0 24 24" width="22" height="22">
      <rect x="2" y="3" width="20" height="18" rx="3" style="fill: var(--task-fill); stroke: var(--task-stroke); stroke-width: 1.5"/>
      <line x1="6" y1="9" x2="18" y2="9" stroke="white" stroke-width="1.5"/>
      <line x1="6" y1="13" x2="18" y2="13" stroke="white" stroke-width="1.5"/>
      <line x1="6" y1="17" x2="14" y2="17" stroke="white" stroke-width="1.5"/>
    </svg>`,
  [SCRIPT_TASK]: `
    <svg viewBox="0 0 24 24" width="22" height="22">
      <rect x="2" y="3" width="20" height="18" rx="3" style="fill: var(--script-task-fill); stroke: var(--script-task-stroke); stroke-width: 1.5"/>
      <text x="12" y="16" text-anchor="middle" font-size="9" font-family="monospace" fill="white">&lt;/&gt;</text>
    </svg>`,
  [USER_TASK]: `
    <svg viewBox="0 0 24 24" width="22" height="22">
      <rect x="2" y="3" width="20" height="18" rx="3" style="fill: var(--user-task-fill); stroke: var(--user-task-stroke); stroke-width: 1.5"/>
      <circle cx="12" cy="10" r="3" fill="white"/>
      <path d="M6 18c0-3.3 2.7-5.5 6-5.5s6 2.2 6 5.5" fill="none" stroke="white" stroke-width="1.7" stroke-linecap="round"/>
    </svg>`,
  [EXCLUSIVE_GATEWAY]: `
    <svg viewBox="0 0 24 24" width="22" height="22">
      <polygon points="12,2 22,12 12,22 2,12" style="fill: var(--gateway-fill); stroke: var(--gateway-stroke); stroke-width: 1.5"/>
      <line x1="8.5" y1="8.5" x2="15.5" y2="15.5" stroke="white" stroke-width="1.5"/>
      <line x1="15.5" y1="8.5" x2="8.5" y2="15.5" stroke="white" stroke-width="1.5"/>
    </svg>`,
  // Same diamond/color as Exclusive Gateway -- see BpmnRenderer.js's note, only the mark differs.
  [PARALLEL_GATEWAY]: `
    <svg viewBox="0 0 24 24" width="22" height="22">
      <polygon points="12,2 22,12 12,22 2,12" style="fill: var(--gateway-fill); stroke: var(--gateway-stroke); stroke-width: 1.5"/>
      <line x1="12" y1="7.5" x2="12" y2="16.5" stroke="white" stroke-width="2" stroke-linecap="round"/>
      <line x1="7.5" y1="12" x2="16.5" y2="12" stroke="white" stroke-width="2" stroke-linecap="round"/>
    </svg>`,
  // A small table grid -- reads as "decision table" at a glance, distinct from Script Task's
  // "</>" and User Task's person glyph while still following the same rounded-rect body.
  [DMN_TASK_ICON_KEY]: `
    <svg viewBox="0 0 24 24" width="22" height="22">
      <rect x="2" y="3" width="20" height="18" rx="3" style="fill: var(--dmn-task-fill); stroke: var(--dmn-task-stroke); stroke-width: 1.5"/>
      <line x1="11" y1="4.5" x2="11" y2="19.5" stroke="white" stroke-width="1.3"/>
      <line x1="3.5" y1="9" x2="20.5" y2="9" stroke="white" stroke-width="1.3"/>
      <line x1="3.5" y1="14.5" x2="20.5" y2="14.5" stroke="white" stroke-width="1.3"/>
    </svg>`,
  // Arrow feeding into a small box -- matches the corner glyph BpmnRenderer.js draws on the
  // shape itself, reading as "hands off to another process."
  [CALL_ACTIVITY]: `
    <svg viewBox="0 0 24 24" width="22" height="22">
      <rect x="2" y="3" width="20" height="18" rx="3" style="fill: var(--call-activity-fill); stroke: var(--call-activity-stroke); stroke-width: 1.5"/>
      <rect x="12" y="7" width="9" height="10" fill="none" stroke="white" stroke-width="1.4"/>
      <path d="M3 12h9 M8 8.5 L12 12 L8 15.5" fill="none" stroke="white" stroke-width="1.4" stroke-linecap="round" stroke-linejoin="round"/>
    </svg>`,
  // Expanded Sub-Process: a mostly-hollow rect (matching the canvas rendering's low fill-opacity)
  // with two small placeholder boxes suggesting "there are nodes inside," rather than the "+"
  // marker (which specifically signals *collapsed*).
  [SUB_PROCESS_EXPANDED_ICON_KEY]: `
    <svg viewBox="0 0 24 24" width="22" height="22">
      <rect x="2" y="3" width="20" height="18" rx="3" style="fill: var(--subprocess-fill); fill-opacity: 0.18; stroke: var(--subprocess-stroke); stroke-width: 1.5"/>
      <rect x="5" y="9" width="6" height="5" rx="1" fill="none" stroke="white" stroke-width="1.2"/>
      <rect x="13" y="12" width="6" height="5" rx="1" fill="none" stroke="white" stroke-width="1.2"/>
    </svg>`,
};

export function paletteIconHtml(bpmnType) {
  return ICONS[bpmnType] || '';
}
