injectStyles('ntrloc-state-machine-editor-styles', `
  /* md-dialog's shadow stylesheet hardcodes "max-width:min(560px,100% - 48px)" on :host with no
     custom-property hook to widen it (verified against the vendored source -- there is no
     --md-dialog-container-min-width token in this version, despite
     ntrloc-item-mutation-dialog.js's own comment assuming one; that dialog just never needed to
     be wider than 560px, so the assumption was never actually exercised). :host rules apply to
     the host element with ordinary specificity, not any special shadow-DOM priority, so an
     external rule of equal-or-greater specificity does win the cascade -- but a plain class
     selector alone is only a specificity tie, decided by stylesheet order, which is too fragile
     to rely on. !important forces it regardless of order. */
  /* Near-fullscreen, with a thin margin so it still reads as a popup over the (unchanged)
     underlying page rather than a route change -- per explicit direction: process/guard editing
     needs the room, but it should still feel like a dialog, not a navigation.
     :host also needs an explicit width/height, not just max-width/max-height -- its own
     "width:fit-content;height:fit-content" otherwise sizes it to its CONTENT's natural size
     (bottom-up), capped by max-*, rather than actually filling up to that cap (top-down). Giving
     it a real height is also what makes [slot=content]'s own height:100% below resolve to
     something concrete instead of the shadow stylesheet's own "height:min-content" default --
     percentage heights need a definite ancestor, and this is what finally provides one. */
  md-dialog.state-machine-editor-dialog {
    width: calc(100% - 32px) !important;
    height: calc(100% - 32px) !important;
    max-width: calc(100% - 32px) !important;
    max-height: calc(100% - 32px) !important;
  }
  /* This is the light-DOM element ntrloc-state-machine-editor.js itself sets innerHTML on
     (the "div slot=content" element), slotted into the shadow root's own ".content" (a plain
     "display:block" element, flex:1 within a taller flex chain now that :host has a real height
     -- see the comment above). Stretching this to 100% of that resolved height, then laying its
     one child (.editor-body) out as a column, is what lets .editor-body's own flex:1 below
     actually mean something, instead of collapsing to fit-content the way it would against an
     "auto"-height ancestor. */
  .state-machine-editor-dialog > [slot=content] {
    height: 100%;
    display: flex;
    flex-direction: column;
    min-height: 0;
  }
  .state-machine-editor-dialog .editor-body {
    flex: 1;
    min-height: 0;
    display: flex;
    /* Was 16px each side of the splitter (32px total dead space) -- the splitter itself is now
       wide enough (see .editor-splitter/.splitter-collapse-button below) to read as a real
       divider on its own, so it doesn't need nearly as much surrounding buffer to not look
       cramped against the panes on either side. */
    gap: 4px;
  }
  /* Per the state-machine-mockups/ layout (state-machine-editor.png etc.): the diagram stays a
     narrow, always-visible strip on the left -- it's not replaced by anything, including the
     entry/exit/guard/process editors below, which all live in the wide detail pane instead. */
  .state-machine-editor-dialog .editor-diagram-pane {
    /* Overridden inline per-render (and live, during a splitter drag/collapse) from
       local.diagramWidth/local.paneCollapsed -- this is just the pre-JS fallback. */
    flex: 0 0 340px;
    min-width: 0;
    min-height: 0;
    display: flex;
    flex-direction: column;
  }
  /* Draggable divider between the diagram and detail panes, plus two collapse toggles -- resizing
     and collapsing are both applied as direct inline-style writes on the two panes (see
     wireSplitter()), never through a full renderContent(): that rebuilds .editor-body from a
     string every time, which would tear down (and lose) a live, possibly-mid-edit
     <ntrloc-process-editor> for what's supposed to be a pure layout change. */
  .state-machine-editor-dialog .editor-splitter {
    /* Widened to fit the collapse buttons below (32px, 2x their original size) -- they're
       stacked in this column, not side by side, so the splitter only needs to be as wide as one
       button, not two. */
    flex: 0 0 32px;
    position: relative;
    display: flex;
    flex-direction: column;
    align-items: center;
    justify-content: center;
    gap: 6px;
    cursor: col-resize;
  }
  .state-machine-editor-dialog .editor-splitter::before {
    content: '';
    position: absolute;
    top: 0;
    bottom: 0;
    left: 50%;
    width: 1px;
    background: var(--border);
    transform: translateX(-50%);
  }
  .state-machine-editor-dialog .splitter-collapse-button {
    /* 2x the original 16px/10px -- 4x (64px) read as too big live. */
    position: relative;
    width: 32px;
    height: 32px;
    padding: 0;
    display: flex;
    align-items: center;
    justify-content: center;
    background: var(--panel-bg);
    border: 1.5px solid var(--border);
    border-radius: 6px;
    color: var(--muted);
    font-size: 20px;
    line-height: 1;
    cursor: pointer;
  }
  .state-machine-editor-dialog .splitter-collapse-button:hover {
    color: var(--text);
    border-color: var(--accent);
  }
  .state-machine-editor-dialog .splitter-collapse-button.active {
    background: var(--accent);
    border-color: var(--accent);
    color: var(--bg);
  }
  .state-machine-editor-dialog .editor-diagram-el {
    flex: 1;
    min-height: 0;
  }
  .state-machine-editor-dialog .editor-diagram-el .state-machine-diagram-scroll {
    height: 100%;
    /* Centered on both axes -- superseded the original state-machine-editor.png-driven choice of
       top-aligning this pane's vertical-orientation diagram (leaving empty space below a short
       chart); explicit direction was to center the whole chart vertically within the pane instead.
       "safe center" (see ntrloc-state-machine-diagram.js's own base rule for the full story) on
       both axes, not plain "center", for the same reason it already mattered horizontally here:
       confirmed live that plain "center" leaves a leading self-loop permanently unreachable (not
       just scrolled-away-by-default) the moment the diagram exceeds this narrow pane's size on
       that axis, since a centered overflow's leading half sits in negative-scroll space no
       scrollLeft/scrollTop can ever reach. */
    justify-content: center;
    justify-content: safe center;
    align-items: center;
    align-items: safe center;
  }
  .state-machine-editor-dialog .editor-diagram-toolbar {
    display: flex;
    justify-content: space-between;
    align-items: center;
    margin-bottom: 8px;
  }
  .state-machine-editor-dialog .editor-connecting-banner {
    background: var(--panel-bg);
    border: 1px solid var(--accent);
    border-radius: 6px;
    padding: 6px 10px;
    font-size: 13px;
    display: flex;
    justify-content: space-between;
    align-items: center;
    gap: 8px;
    margin-bottom: 8px;
  }
  .state-machine-editor-dialog .editor-connect-error {
    color: #f85149;
    font-size: 12px;
    margin: 4px 0 0 0;
  }
  .state-machine-editor-dialog .editor-deleted-list {
    display: flex;
    flex-wrap: wrap;
    gap: 6px;
    margin-top: 12px;
  }
  .state-machine-editor-dialog .deleted-chip {
    display: inline-flex;
    align-items: center;
    gap: 6px;
    background: var(--panel-bg);
    border: 1px solid var(--border);
    border-radius: 12px;
    padding: 2px 4px 2px 10px;
    font-size: 12px;
    color: var(--muted);
    text-decoration: line-through;
  }
  .state-machine-editor-dialog .deleted-chip .restore-deleted-button {
    text-decoration: none;
  }
  .state-machine-editor-dialog .editor-detail-pane {
    flex: 1 1 auto;
    min-width: 0;
    min-height: 0;
    display: flex;
    flex-direction: column;
    border-left: 1px solid var(--border);
    padding-left: 16px;
    overflow-y: auto;
  }
  .state-machine-editor-dialog .detail-header input.detail-input {
    display: block;
    width: 100%;
    box-sizing: border-box;
    background: var(--bg);
    color: var(--text);
    border: 1px solid var(--border);
    border-radius: 4px;
    padding: 6px 8px;
    font-size: 13px;
    font-family: inherit;
    margin-bottom: 8px;
  }
  .state-machine-editor-dialog .detail-header input.state-name-input,
  .state-machine-editor-dialog .detail-header input.transition-name-input {
    font-size: 20px;
    font-weight: bold;
    border: none;
    background: none;
    padding: 0;
    margin-bottom: 4px;
  }
  .state-machine-editor-dialog .editor-checkbox-row {
    display: flex;
    align-items: center;
    gap: 8px;
    margin: 8px 0;
  }
  /* Tabs switch which large editor occupies .detail-tab-content below -- this is the mechanism
     mockups -2/-3 show for a transition (Guard/Process) and the first mockup shows for a state
     (Entry process/Exit process), replacing the plain field-picker this used to be. */
  .state-machine-editor-dialog .detail-tabs {
    display: flex;
    gap: 4px;
    border-bottom: 1px solid var(--border);
    margin: 12px 0;
  }
  .state-machine-editor-dialog .detail-tab-button {
    background: none;
    border: none;
    border-bottom: 2px solid transparent;
    margin-bottom: -1px;
    color: var(--muted);
    font-size: 14px;
    font-weight: bold;
    padding: 8px 14px;
    cursor: pointer;
  }
  .state-machine-editor-dialog .detail-tab-button:hover {
    color: var(--text);
  }
  .state-machine-editor-dialog .detail-tab-button.active {
    color: var(--text);
    border-bottom-color: var(--accent);
  }
  /* flex:1 lets this fill whatever's left in .editor-detail-pane's column after the header/tabs/
     footer take their natural size -- harmless for the guard tab (the predicate-builder's pills
     just sit at their own natural height inside a taller-than-needed box), but required for the
     process tab: ntrloc-process-editor.js's host is "display:contents", so its own
     .editor-body/.editor-canvas children become flex items of whatever wraps them directly (the
     same way it works inside ntrloc-tab-workspace.js) -- only is-process-tab adds the
     display:flex;flex-direction:column those children actually need to size against. */
  .state-machine-editor-dialog .detail-tab-content {
    flex: 1;
    min-height: 0;
  }
  .state-machine-editor-dialog .detail-tab-content.is-process-tab {
    display: flex;
    flex-direction: column;
  }
  .state-machine-editor-dialog .editor-transition-list {
    list-style: none;
    margin: 4px 0 0;
    padding: 0;
  }
  .state-machine-editor-dialog .editor-transition-list li {
    display: flex;
    justify-content: space-between;
    align-items: center;
    padding: 4px 0;
    font-size: 13px;
    border-bottom: 1px solid var(--border);
  }
  .state-machine-editor-dialog .editor-transition-list button.transition-jump-button {
    background: none;
    border: none;
    color: var(--text);
    text-align: left;
    cursor: pointer;
    font-size: 13px;
    padding: 0;
    flex: 1;
  }
  .state-machine-editor-dialog .editor-transition-list button.transition-jump-button:hover {
    color: var(--accent);
  }
  .state-machine-editor-dialog .detail-footer {
    margin-top: 16px;
    padding-top: 12px;
    border-top: 1px solid var(--border);
  }
  .state-machine-editor-dialog .detail-footer label {
    display: block;
    font-size: 11px;
    color: var(--muted);
    font-weight: bold;
    letter-spacing: 0.05em;
    margin: 0 0 4px;
  }
  .state-machine-editor-dialog .editor-detail-actions {
    margin-top: 12px;
    display: flex;
    gap: 8px;
  }
  .state-machine-editor-dialog .editor-readonly-value {
    padding: 2px 0 8px;
    color: var(--muted);
    font-size: 13px;
  }
`);

// Promise-based wrapper around a transient <md-dialog>, structurally similar to
// ntrloc-item-mutation-dialog.js/ntrloc-controlled-list-dialog.js -- but unlike either, edits here
// apply directly to the live StateViewModel/TransitionViewModel objects on `item.states` as they
// happen (same idiom as ntrloc-item-detail.js's own inline name/description fields), not a
// separate staged copy resolved on close. There's nothing to "stage" or "cancel": the objects
// being edited are already the ones schemaViewModel.collectMutations() reads from, and the outer
// Save button is what actually persists them. Closing this dialog (any way) just resolves with no
// payload -- it's a bigger canvas onto the same live schema state, not its own transaction.
//
// Layout follows docs/state-machine-mockups/ (state-machine-editor.png / -2 / -3), not an earlier
// picker+"Edit"-button design this replaced: the diagram is a persistent narrow strip on the left;
// the wide right pane shows the selected state/transition's fields plus a row of tabs (Entry
// process/Exit process for a state, Guard/Process for a transition) whose content is the actual
// embedded editor -- ntrloc-process-editor.js's real BPMN canvas, or ntrloc-predicate-builder.js's
// pills -- never a process-name <select>. See docs/ntrloc-state-machine-summary.md Section 11,
// points 3/5: "not a process-name picker". Point 4's drag-based And/Or/Has/= guard palette is a
// separate, larger follow-up -- this still uses ntrloc-predicate-builder.js's existing
// button-driven pills, just given a full tab's worth of room.
//
// A state/transition needs a real (saved) id before transitions can be added to/from it -- see
// ItemDefinitionViewModel.addState()'s own comment. This mirrors the existing Links-panel
// precedent (openItemMutationDialog is never reached for a new item either); the caller
// (ntrloc-item-detail.js) is expected to only offer this dialog once item.id is real.
function openStateMachineEditorDialog(item) {
  return new Promise((resolve) => {
    const local = {
      selectedState: null,
      selectedTransition: null,
      connectingFrom: null,
      connectError: null,
      // 'entry'|'exit' while a state is selected, 'guard'|'process' while a transition is --
      // reset to a sensible default by selectState()/selectTransition() below.
      activeTab: null,
      // Splitter state (see wireSplitter()) -- diagramWidth only matters when paneCollapsed is
      // null; scoped to this dialog instance, not persisted across it being closed and reopened.
      // 480, not something narrower like 340: two side-by-side states (2*160 NODE_WIDTH + 44
      // NODE_GAP = 364) plus self-loop clearance (58 PADDING_LEADING + 24 PADDING = 82) is 446px
      // wide before any diagram even branches further -- a narrower default left "safe center"
      // (see ntrloc-state-machine-diagram.js) permanently falling back to start-alignment for any
      // realistic diagram, since it never actually fit. This leaves real slack for centering to be
      // visible in the common case, matching state-machine-editor.png, while still reading as a
      // narrow side panel rather than half the dialog.
      diagramWidth: 480,
      paneCollapsed: null, // null | 'diagram' | 'detail'
    };

    const dialog = document.createElement('md-dialog');
    dialog.className = 'state-machine-editor-dialog';

    // A process reference is stored as the process *key* (see schema-view-model.js's
    // processOptions getter's own comment) -- resolving it back to a specific definition id to
    // hand to <ntrloc-process-editor>'s dataset.definitionId reuses that same de-duped
    // latest-version-per-key list rather than re-deriving it.
    function resolveDefinitionIdForKey(key) {
      return schemaViewModel.processOptions.find((def) => def.key === key)?.id ?? null;
    }

    function visibleStates() {
      return item.states.filter((s) => !s.isDeleted);
    }

    function diagramStates() {
      return visibleStates().map((s) => ({
        id: s.id,
        name: s.name,
        isInitial: s.isInitial,
        vm: s,
        transitions: s.transitions.filter((t) => !t.isDeleted).map((t) => ({
          id: t.id,
          toStateId: t.toStateId,
          name: t.name,
          vm: t,
        })),
      }));
    }

    function selectState(vm) {
      local.selectedState = vm;
      local.selectedTransition = null;
      local.connectingFrom = null;
      local.connectError = null;
      local.activeTab = 'entry';
      renderContent();
    }

    function selectTransition(vm) {
      local.selectedTransition = vm;
      local.selectedState = null;
      local.connectingFrom = null;
      local.connectError = null;
      local.activeTab = 'guard';
      renderContent();
    }

    function onDiagramSelectState(wrapper) {
      const vm = wrapper.vm;
      if (local.connectingFrom) {
        if (vm.isNew) {
          local.connectError = `Save "${vm.name || '(unnamed)'}" before connecting to it.`;
          renderContent();
          return;
        }
        const created = local.connectingFrom.addTransition(vm.id, vm.name);
        local.connectingFrom = null;
        local.connectError = null;
        selectTransition(created);
        notifySchemaViewModelChange();
        return;
      }
      selectState(vm);
    }

    function onDiagramSelectTransition(wrapper) {
      selectTransition(wrapper.vm);
    }

    function detailPaneHtml() {
      if (local.connectingFrom) {
        return '<p class="status">Click a state in the diagram to connect to it (or click the same state again for a self-transition).</p>';
      }
      if (local.selectedState) return stateDetailHtml(local.selectedState);
      if (local.selectedTransition) return transitionDetailHtml(local.selectedTransition);
      return '<p class="status">Select a state or transition to edit it, or add a new state.</p>';
    }

    // The definition id has to be baked into the markup string itself (as a data-definition-id
    // attribute), not set as a property afterward -- <ntrloc-process-editor> reads
    // dataset.definitionId in connectedCallback, which fires synchronously the moment this HTML is
    // parsed into the already-connected dialog, before any wireContent() code could run. The
    // placeholder id is derived deterministically from (kind, ownerId) rather than a counter --
    // stable across re-renders of the same tab, and distinct across different states/transitions
    // (each has its own real id) without needing any shared mutable state to hand them out.
    function processTabContentHtml(currentKey, kind, ownerId) {
      const definitionId = (currentKey && resolveDefinitionIdForKey(currentKey)) || `new-process-${kind}-${ownerId}`;
      return `<ntrloc-process-editor class="tab-process-editor-el" data-definition-id="${escapeHtml(definitionId)}"></ntrloc-process-editor>`;
    }

    function guardTabContentHtml(vm) {
      return `
        <ntrloc-predicate-builder class="transition-guard-builder-el"></ntrloc-predicate-builder>
        <div class="guard-error-hint-container">${guardErrorHintHtml(vm)}</div>
      `;
    }

    function guardErrorHintHtml(vm) {
      return predicateHasErrors(vm.guardCondition)
        ? '<p class="editor-connect-error">This guard is incomplete and won\'t be saved until it\'s finished.</p>'
        : '';
    }

    function stateDetailHtml(vm) {
      const outgoing = vm.transitions.filter((t) => !t.isDeleted);
      const isProcessTab = true; // both state tabs are process tabs
      return `
        <div class="detail-header">
          <input class="detail-input state-name-input" value="${escapeHtml(vm.name)}" placeholder="State name" />
          <input class="detail-input state-description-input" value="${escapeHtml(vm.description ?? '')}" placeholder="Description (optional)" />
          <div class="editor-checkbox-row">
            <md-checkbox class="state-initial-checkbox" ${vm.isInitial ? 'checked' : ''}></md-checkbox>
            <span>Initial state</span>
          </div>
        </div>

        <div class="detail-tabs">
          <button class="detail-tab-button ${local.activeTab === 'entry' ? 'active' : ''}" data-tab="entry">Entry process</button>
          <button class="detail-tab-button ${local.activeTab === 'exit' ? 'active' : ''}" data-tab="exit">Exit process</button>
        </div>
        <div class="detail-tab-content ${isProcessTab ? 'is-process-tab' : ''}">
          ${local.activeTab === 'entry'
            ? processTabContentHtml(vm.entryProcessId, 'state-entry', vm.id)
            : processTabContentHtml(vm.exitProcessId, 'state-exit', vm.id)}
        </div>

        <div class="detail-footer">
          <label>Outgoing transitions</label>
          ${outgoing.length > 0 ? `
            <ul class="editor-transition-list">
              ${outgoing.map((t) => `
                <li><button class="transition-jump-button" data-transition-id="${escapeHtml(t.id)}">${escapeHtml(t.name || '(unnamed)')} → ${escapeHtml(t.toStateName)}</button></li>
              `).join('')}
            </ul>
          ` : '<p class="status">None yet.</p>'}

          <div class="editor-detail-actions">
            ${!vm.isNew ? '<md-outlined-button class="add-transition-button">+ Add Transition</md-outlined-button>' : ''}
            <md-text-button class="delete-state-button">Delete</md-text-button>
          </div>
          ${vm.isNew ? '<p class="status">Save this item type before adding transitions to a new state.</p>' : ''}
        </div>
      `;
    }

    function transitionDetailHtml(vm) {
      const isProcessTab = local.activeTab === 'process';
      return `
        <div class="detail-header">
          <input class="detail-input transition-name-input" value="${escapeHtml(vm.name)}" placeholder="Transition name" />
          <div class="editor-readonly-value">→ ${escapeHtml(vm.toStateName)}</div>
          <input class="detail-input transition-description-input" value="${escapeHtml(vm.description ?? '')}" placeholder="Description (optional)" />
        </div>

        <div class="detail-tabs">
          <button class="detail-tab-button ${local.activeTab === 'guard' ? 'active' : ''}" data-tab="guard">Guard</button>
          <button class="detail-tab-button ${local.activeTab === 'process' ? 'active' : ''}" data-tab="process">Process</button>
        </div>
        <div class="detail-tab-content ${isProcessTab ? 'is-process-tab' : ''}">
          ${isProcessTab ? processTabContentHtml(vm.processId, 'transition', vm.id) : guardTabContentHtml(vm)}
        </div>

        <div class="detail-footer">
          <md-text-button class="delete-transition-button">Delete</md-text-button>
        </div>
      `;
    }

    function deletedListHtml() {
      const deletedStates = item.states.filter((s) => s.isDeleted);
      const deletedTransitions = item.states.flatMap((s) => s.transitions.filter((t) => t.isDeleted));
      if (deletedStates.length === 0 && deletedTransitions.length === 0) return '';
      return `
        <div class="editor-deleted-list">
          ${deletedStates.map((s) => `
            <span class="deleted-chip">${escapeHtml(s.originalName)}<button class="restore-deleted-button" data-kind="state" data-id="${escapeHtml(s.id)}" title="Restore">↺</button></span>
          `).join('')}
          ${deletedTransitions.map((t) => `
            <span class="deleted-chip">${escapeHtml(t.originalName)}<button class="restore-deleted-button" data-kind="transition" data-id="${escapeHtml(t.id)}" title="Restore">↺</button></span>
          `).join('')}
        </div>
      `;
    }

    // Diagram-pane style is the one thing a splitter drag rewrites live (see wireSplitter()) --
    // computed from local state here too so a later, unrelated full renderContent() (e.g.
    // selecting a different node) reflects whatever the user last dragged/collapsed to, instead of
    // resetting back to the 340px default.
    function diagramPaneStyle() {
      if (local.paneCollapsed === 'diagram') return 'display: none;';
      if (local.paneCollapsed === 'detail') return 'flex: 1 1 auto;';
      return `flex: 0 0 ${local.diagramWidth}px;`;
    }

    function detailPaneStyle() {
      return local.paneCollapsed === 'detail' ? 'display: none;' : '';
    }

    function renderContent() {
      // renderContent() rebuilds the whole dialog body from an HTML string below, which tears
      // down and reconstructs <ntrloc-state-machine-diagram> as a brand new element instance every
      // single time (including on every state/transition selection, via selectState/
      // selectTransition) -- its zoom/pan state lives only on that instance (see its own
      // constructor comment), so it has to be captured before the rebuild and fed into the
      // replacement below, or every click would silently reset the user's zoom/pan (found live).
      const previousDiagram = dialog.querySelector('.editor-diagram-el');
      const savedViewState = previousDiagram ? previousDiagram.viewState : null;

      dialog.querySelector('[slot=headline]').textContent = `States: ${item.name}`;
      dialog.querySelector('[slot=content]').innerHTML = `
        <div class="editor-body">
          <div class="editor-diagram-pane" style="${diagramPaneStyle()}">
            <div class="editor-diagram-toolbar">
              <md-outlined-button class="add-state-button">+ Add State</md-outlined-button>
            </div>
            ${local.connectingFrom ? `
              <div class="editor-connecting-banner">
                <span>Connecting from "${escapeHtml(local.connectingFrom.name || '(unnamed)')}"…</span>
                <md-text-button class="cancel-connecting-button">Cancel</md-text-button>
              </div>
              ${local.connectError ? `<p class="editor-connect-error">${escapeHtml(local.connectError)}</p>` : ''}
            ` : ''}
            <ntrloc-state-machine-diagram class="editor-diagram-el"></ntrloc-state-machine-diagram>
            ${deletedListHtml()}
          </div>
          <div class="editor-splitter">
            <button class="splitter-collapse-button ${local.paneCollapsed === 'diagram' ? 'active' : ''}" data-collapse="diagram" title="Collapse diagram">‹</button>
            <button class="splitter-collapse-button ${local.paneCollapsed === 'detail' ? 'active' : ''}" data-collapse="detail" title="Collapse details">›</button>
          </div>
          <div class="editor-detail-pane" style="${detailPaneStyle()}">${detailPaneHtml()}</div>
        </div>
      `;
      wireContent(savedViewState);
    }

    function wireContent(savedViewState) {
      const diagram = dialog.querySelector('.editor-diagram-el');
      // Set before `.data` below (whose setter triggers the new instance's first render) so that
      // first render already bakes the restored zoom into the SVG it builds, rather than rendering
      // once at 1x and needing a second pass.
      if (savedViewState) diagram.viewState = savedViewState;
      diagram.data = {
        states: diagramStates(),
        selectedStateId: local.selectedState?.id ?? null,
        selectedTransitionId: local.selectedTransition?.id ?? null,
        onSelectState: onDiagramSelectState,
        onSelectTransition: onDiagramSelectTransition,
        // Vertical here, unlike ntrloc-item-detail.js's read-only horizontal usage -- this pane is
        // a narrow, always-visible strip (per the state-machine-mockups/ layout), and a vertical
        // flow makes far better use of narrow-but-tall space than a horizontal one would.
        orientation: 'vertical',
      };

      dialog.querySelector('.add-state-button').addEventListener('click', () => {
        const vm = item.addState();
        selectState(vm);
        notifySchemaViewModelChange();
      });

      wireSplitter();

      const cancelConnectingButton = dialog.querySelector('.cancel-connecting-button');
      if (cancelConnectingButton) cancelConnectingButton.addEventListener('click', () => {
        local.connectingFrom = null;
        local.connectError = null;
        renderContent();
      });

      dialog.querySelectorAll('.restore-deleted-button').forEach((button) => {
        button.addEventListener('click', () => {
          const { kind, id } = button.dataset;
          if (kind === 'state') {
            const vm = item.states.find((s) => s.id === id);
            if (vm) vm.isDeleted = false;
          } else {
            for (const s of item.states) {
              const vm = s.transitions.find((t) => t.id === id);
              if (vm) { vm.isDeleted = false; break; }
            }
          }
          renderContent();
          notifySchemaViewModelChange();
        });
      });

      dialog.querySelectorAll('.detail-tab-button').forEach((button) => {
        button.addEventListener('click', () => {
          local.activeTab = button.dataset.tab;
          renderContent();
        });
      });

      // A process tab's embedded editor is the same element regardless of whether it belongs to a
      // state (entry/exit) or a transition -- which field actually gets the deployed key is
      // resolved from local.selectedState/selectedTransition + local.activeTab at save time, not
      // baked into a per-field listener.
      const tabProcessEditor = dialog.querySelector('.tab-process-editor-el');
      if (tabProcessEditor) tabProcessEditor.addEventListener('process-saved', (event) => {
        if (local.selectedState) {
          if (local.activeTab === 'entry') local.selectedState.entryProcessId = event.detail.key;
          else local.selectedState.exitProcessId = event.detail.key;
        } else if (local.selectedTransition) {
          local.selectedTransition.processId = event.detail.key;
        }
        notifySchemaViewModelChange();
        // schemaViewModel.processDefinitions is fetched once at schema load and only refreshed on
        // reload() -- keeping it in sync in the background (not awaited, and deliberately not
        // folded into a renderContent() here, which would tear down and recreate this still-open
        // <ntrloc-process-editor> for no reason) matches what a full reload would eventually do
        // anyway once the outer Save button is used.
        schemaViewModel._loadProcessDefinitions();
      });

      const guardBuilder = dialog.querySelector('.transition-guard-builder-el');
      if (guardBuilder) guardBuilder.data = {
        predicate: local.selectedTransition.guardCondition,
        properties: item.properties,
        onChange: (newPredicate) => {
          local.selectedTransition.guardCondition = newPredicate;
          notifySchemaViewModelChange();
          // Not a full renderContent() -- the predicate builder already re-rendered its own pills
          // in response to this same edit (see its own _notify()); rebuilding it again here would
          // just tear down and immediately recreate the element it's called from.
          const hintContainer = dialog.querySelector('.guard-error-hint-container');
          if (hintContainer) hintContainer.innerHTML = guardErrorHintHtml(local.selectedTransition);
        },
      };

      const stateNameInput = dialog.querySelector('.state-name-input');
      if (stateNameInput) stateNameInput.addEventListener('change', (event) => {
        local.selectedState.name = event.target.value;
        wireDiagramOnly();
        notifySchemaViewModelChange();
      });
      const stateDescriptionInput = dialog.querySelector('.state-description-input');
      if (stateDescriptionInput) stateDescriptionInput.addEventListener('change', (event) => {
        local.selectedState.description = event.target.value || null;
        notifySchemaViewModelChange();
      });
      const stateInitialCheckbox = dialog.querySelector('.state-initial-checkbox');
      if (stateInitialCheckbox) stateInitialCheckbox.addEventListener('change', (event) => {
        local.selectedState.isInitial = event.target.checked;
        wireDiagramOnly();
        notifySchemaViewModelChange();
      });
      const addTransitionButton = dialog.querySelector('.add-transition-button');
      if (addTransitionButton) addTransitionButton.addEventListener('click', () => {
        local.connectingFrom = local.selectedState;
        local.selectedState = null;
        local.connectError = null;
        renderContent();
      });
      const deleteStateButton = dialog.querySelector('.delete-state-button');
      if (deleteStateButton) deleteStateButton.addEventListener('click', () => {
        item.removeState(local.selectedState);
        local.selectedState = null;
        renderContent();
        notifySchemaViewModelChange();
      });
      dialog.querySelectorAll('.transition-jump-button').forEach((button) => {
        button.addEventListener('click', () => {
          const vm = local.selectedState.transitions.find((t) => t.id === button.dataset.transitionId);
          if (vm) selectTransition(vm);
        });
      });

      const transitionNameInput = dialog.querySelector('.transition-name-input');
      if (transitionNameInput) transitionNameInput.addEventListener('change', (event) => {
        local.selectedTransition.name = event.target.value;
        wireDiagramOnly();
        notifySchemaViewModelChange();
      });
      const transitionDescriptionInput = dialog.querySelector('.transition-description-input');
      if (transitionDescriptionInput) transitionDescriptionInput.addEventListener('change', (event) => {
        local.selectedTransition.description = event.target.value || null;
        notifySchemaViewModelChange();
      });
      const deleteTransitionButton = dialog.querySelector('.delete-transition-button');
      if (deleteTransitionButton) deleteTransitionButton.addEventListener('click', () => {
        const owner = item.states.find((s) => s.transitions.includes(local.selectedTransition));
        if (owner) owner.removeTransition(local.selectedTransition);
        local.selectedTransition = null;
        renderContent();
        notifySchemaViewModelChange();
      });
    }

    // Both the drag-to-resize and the two collapse toggles write directly to the panes' own
    // inline styles rather than going through renderContent() -- see local.diagramWidth's own
    // comment for why a live process editor can't tolerate the detail pane (or its parent chain)
    // being torn down and rebuilt just to change a width. local.diagramWidth/paneCollapsed are
    // still kept up to date so the *next* real renderContent() (triggered by something unrelated,
    // like picking a different node) starts from wherever the user last left the split.
    function wireSplitter() {
      const splitter = dialog.querySelector('.editor-splitter');
      const diagramPane = dialog.querySelector('.editor-diagram-pane');
      const detailPane = dialog.querySelector('.editor-detail-pane');

      function applyCollapse(which) {
        local.paneCollapsed = local.paneCollapsed === which ? null : which;
        diagramPane.style.cssText = diagramPaneStyle();
        detailPane.style.cssText = detailPaneStyle();
        splitter.querySelectorAll('.splitter-collapse-button').forEach((button) => {
          button.classList.toggle('active', button.dataset.collapse === local.paneCollapsed);
        });
      }

      splitter.querySelectorAll('.splitter-collapse-button').forEach((button) => {
        button.addEventListener('click', () => applyCollapse(button.dataset.collapse));
      });

      splitter.addEventListener('mousedown', (event) => {
        if (event.target.closest('.splitter-collapse-button')) return;
        if (local.paneCollapsed) return; // nothing to drag when one side is hidden
        event.preventDefault();
        const bodyRect = dialog.querySelector('.editor-body').getBoundingClientRect();

        function onMove(moveEvent) {
          const minDetailWidth = 300;
          const width = Math.min(
            Math.max(moveEvent.clientX - bodyRect.left, 200),
            bodyRect.width - minDetailWidth,
          );
          local.diagramWidth = width;
          diagramPane.style.flex = `0 0 ${width}px`;
        }
        function onUp() {
          document.removeEventListener('mousemove', onMove);
          document.removeEventListener('mouseup', onUp);
        }
        document.addEventListener('mousemove', onMove);
        document.addEventListener('mouseup', onUp);
      });
    }

    // Re-applying just the diagram's `.data` (not a full renderContent()) after a name/isInitial
    // edit would still work via renderContent, but it steals focus from the input the user is
    // actively typing in -- 'change' only fires on blur/Enter though, so in practice this is only
    // reached after the input already lost focus. Kept as its own step anyway so the diagram
    // relabels itself without rebuilding the (unrelated) detail pane inputs -- and the possibly
    // live process editor/predicate builder mid-edit -- around it.
    function wireDiagramOnly() {
      const diagram = dialog.querySelector('.editor-diagram-el');
      diagram.data = {
        states: diagramStates(),
        selectedStateId: local.selectedState?.id ?? null,
        selectedTransitionId: local.selectedTransition?.id ?? null,
        onSelectState: onDiagramSelectState,
        onSelectTransition: onDiagramSelectTransition,
        // Vertical here, unlike ntrloc-item-detail.js's read-only horizontal usage -- this pane is
        // a narrow, always-visible strip (per the state-machine-mockups/ layout), and a vertical
        // flow makes far better use of narrow-but-tall space than a horizontal one would.
        orientation: 'vertical',
      };
    }

    dialog.innerHTML = `
      <div slot="headline"></div>
      <div slot="content"></div>
      <div slot="actions">
        <md-filled-button class="done-button">Done</md-filled-button>
      </div>
    `;
    document.body.appendChild(dialog);

    dialog.addEventListener('closed', () => {
      resolve();
      dialog.remove();
    });
    dialog.querySelector('.done-button').addEventListener('click', () => dialog.close('done'));

    renderContent();
    dialog.open = true;
  });
}

function escapeHtml(value) {
  const div = document.createElement('div');
  div.textContent = String(value ?? '');
  return div.innerHTML;
}
