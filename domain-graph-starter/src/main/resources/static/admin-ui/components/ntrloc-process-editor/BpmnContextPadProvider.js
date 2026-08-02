import {
  isSubProcess, getLoopType, setLoopType, LOOP_TYPE_LABELS, isCallActivity, isDmnTask,
  getDecisionTableReferenceKey,
} from './bpmn-elements.js';

// Minimal context pad: delete the selected element, or drag from it to start a connection.
// No append-shape shortcuts (create a task from a hovering "+" icon etc.) -- the palette
// covers element creation; this only needs to cover the two actions the palette can't. Sub-
// Process collapse/expand isn't here -- clicking its own +/- marker does that directly
// (SubProcessToggleBehavior.js), the standard BPMN-tool convention for it. Sub-Process's loop-
// type picker (one-time/loop/multi-instance parallel/multi-instance sequential) IS here, though --
// unlike collapse/expand there's no marker-click convention for a 4-way choice, and this is the
// one place every selectable element already gets a small floating menu of actions.
const LOOP_TYPE_MENU_ORDER = ['none', 'loop', 'parallel', 'sequential'];

export default function BpmnContextPadProvider(connect, contextPad, modeling, eventBus, moddle) {
  this._connect = connect;
  this._modeling = modeling;
  this._eventBus = eventBus;
  this._moddle = moddle;

  contextPad.registerProvider(this);
}

BpmnContextPadProvider.$inject = ['connect', 'contextPad', 'modeling', 'eventBus', 'moddle'];

BpmnContextPadProvider.prototype.getContextPadEntries = function(element) {
  const connect = this._connect;
  const modeling = this._modeling;

  const removeElement = () => modeling.removeElements([element]);
  const startConnect = (event, el, autoActivate) => connect.start(event, el, autoActivate);

  const entries = {
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

  if (isSubProcess(element)) {
    entries['loop-type'] = {
      group: 'edit',
      title: 'Loop type',
      html: '<div class="entry ntrloc-context-pad-icon"><span>&#8635;</span></div>',
      action: {
        click: (event) => this.openLoopTypeMenu(event, element),
      },
    };
  }

  // "Go to Called Process"/"Go to Decision Table" -- lives here rather than the inspector panel
  // (where the Called Process/Decision Table select already shows the same reference) since this
  // is meant to read as an action alongside delete/connect, not another field to look at. Only
  // offered once a reference is actually set -- an empty calledElement/decisionTableReferenceKey
  // has nowhere to jump to. The key alone is fired on the eventBus; resolving it to an actual
  // definition id is ntrloc-process-editor.js's job (it already owns
  // loadProcessDefinitions()/loadDecisionTables()'s fetch-and-cache, no reason to duplicate that
  // here just to build a context-pad icon).
  if (isCallActivity(element) && element.businessObject.calledElement) {
    entries['jump-to-reference'] = {
      group: 'edit',
      title: 'Go to Called Process',
      html: '<div class="entry ntrloc-context-pad-icon"><span>&#8599;</span></div>',
      action: {
        click: () => this._eventBus.fire('ntrloc.jumpToReference', {
          kind: 'process', key: element.businessObject.calledElement,
        }),
      },
    };
  } else if (isDmnTask(element) && getDecisionTableReferenceKey(element.businessObject)) {
    entries['jump-to-reference'] = {
      group: 'edit',
      title: 'Go to Decision Table',
      html: '<div class="entry ntrloc-context-pad-icon"><span>&#8599;</span></div>',
      action: {
        click: () => this._eventBus.fire('ntrloc.jumpToReference', {
          kind: 'decision', key: getDecisionTableReferenceKey(element.businessObject),
        }),
      },
    };
  }

  return entries;
};

// Custom-built, not diagram-js's PopupMenu module (never wired into this app's Diagram instance --
// see ntrloc-process-editor.js's modules list) -- a small fixed-position <div> anchored off the
// clicked icon's own rect is enough for a 4-item list and matches how the rest of this app's UI
// (e.g. the palette flyout) is already built directly against the DOM rather than through a
// diagram-js feature module.
BpmnContextPadProvider.prototype.openLoopTypeMenu = function(event, element) {
  const existing = document.querySelector('.ntrloc-loop-type-menu');
  if (existing) existing.remove();

  const icon = (event.delegateTarget || event.target).closest('.entry') || event.target;
  const rect = icon.getBoundingClientRect();

  const menu = document.createElement('div');
  menu.className = 'ntrloc-loop-type-menu';
  menu.style.left = `${rect.left}px`;
  menu.style.top = `${rect.bottom + 4}px`;

  const currentType = getLoopType(element);
  LOOP_TYPE_MENU_ORDER.forEach((loopType) => {
    const option = document.createElement('button');
    option.type = 'button';
    option.className = 'ntrloc-loop-type-option' + (loopType === currentType ? ' selected' : '');
    option.textContent = LOOP_TYPE_LABELS[loopType];
    option.addEventListener('click', () => {
      setLoopType(this._moddle, element.businessObject, loopType);
      this._eventBus.fire('elements.changed', { elements: [element] });
      this._eventBus.fire('ntrloc.elementPropertiesChanged', { element });
      menu.remove();
    });
    menu.appendChild(option);
  });

  document.body.appendChild(menu);

  // Deferred, not bound in the same tick: the click that opened this menu would otherwise
  // immediately bubble into this same listener and close it right back.
  setTimeout(() => {
    const closeOnOutsideClick = (closeEvent) => {
      if (!menu.contains(closeEvent.target)) {
        menu.remove();
        document.removeEventListener('click', closeOnOutsideClick);
      }
    };
    document.addEventListener('click', closeOnOutsideClick);
  }, 0);
};
