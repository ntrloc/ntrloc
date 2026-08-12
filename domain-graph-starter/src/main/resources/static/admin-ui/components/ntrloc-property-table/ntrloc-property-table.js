injectStyles('ntrloc-property-table-styles', `
  ntrloc-property-table {
    display: contents;
  }
  /* CSS Grid instead of a <table>, originally ported from the Angular reference's
     property-grid.scss, rather than a table-layout approximation of it -- sidesteps table-layout
     quirks (auto-sized columns fighting fixed-width form controls, a nested table-in-a-cell for
     link properties). Row grouping is done with a display:contents wrapper per row (the same
     technique already used for the outer custom element itself) purely so each row can carry
     role="row" and a data-index for wireUp() to query against -- it has zero effect on the grid
     layout itself, which only "sees" the actual cell divs.
     Type/Cardinality/Usage are auto-sized (shrink to whatever their content -- a short read-only
     value or an open select -- actually needs, same as Actions already was) rather than fr'd,
     since their content is always short; Name and Description are the two columns worth reading
     at length, so they're the only ones that get an even fr share of whatever space is left over. */
  .property-grid {
    display: grid;
    grid-template-columns: 12px 1fr 1fr auto auto auto auto;
    gap: 4px 16px;
    align-items: center;
    width: 100%;
  }
  .property-grid .grid-row {
    display: contents;
  }
  .property-grid .grid-header {
    font-size: 11px;
    color: var(--muted);
    font-weight: bold;
    letter-spacing: 0.05em;
    padding-bottom: 6px;
    border-bottom: 1px solid var(--border);
    align-self: end;
  }
  .property-grid .grid-cell {
    padding: 2px 0;
    align-self: center;
  }
  .property-grid .row-deleted .grid-cell {
    opacity: 0.5;
  }
  .prop-grid-dirty-dot {
    color: var(--dirty-color, #e3b341);
  }
  .prop-grid-dirty-dot.is-new {
    color: var(--new-color, #3fb950);
  }
  .prop-grid-dirty-dot.is-deleted {
    color: var(--deleted-color, #f85149);
  }
  .property-grid .editable-field {
    width: 100%;
    --md-filled-text-field-top-space: 4px;
    --md-filled-text-field-bottom-space: 4px;
    /* Material's filled-field default (16px each side) is sized for a standalone field with
       room to breathe -- inside this grid's already-narrow columns it's just wasted width, most
       visible on a select's value text and a text-field's typed text both sitting noticeably
       indented from the cell's edge. --md-filled-field-leading/trailing-space is the shared
       token both md-filled-text-field and md-filled-select fall back to (unlike top/bottom-space,
       select has no *-text-field-leading-space alias of its own to set separately), so setting it
       once here covers every editable-field kind in the grid.  */
    --md-filled-field-leading-space: 4px;
    --md-filled-field-trailing-space: 4px;
  }
  /* md-filled-select has no *-text-field-top/bottom-space alias of its own (unlike
     md-filled-text-field) -- it reads the shared underlying --md-filled-field-top/bottom-space
     tokens directly, so that's what has to be set here to make the two match heights. */
  .property-grid md-filled-select.editable-field {
    --md-filled-field-top-space: 4px;
    --md-filled-field-bottom-space: 4px;
  }
  .property-grid .original-value {
    font-size: 11px;
    color: var(--muted);
    text-decoration: line-through;
    margin-top: 2px;
  }
  .property-grid .defined-in-badge {
    display: block;
    font-size: 11px;
    font-style: italic;
    opacity: 0.6;
    color: var(--accent);
    margin-top: 2px;
  }
  .property-grid .read-only-value {
    display: block;
    padding: 4px 0;
    font-size: 13px;
    color: var(--text);
    min-height: 1em;
  }
  .property-grid .grid-row.row-clickable {
    cursor: pointer;
  }
  .property-grid .grid-row.row-clickable:hover .grid-cell {
    background: var(--panel-bg);
  }
  .property-grid .actions-cell {
    display: flex;
    align-items: center;
    gap: 2px;
    white-space: nowrap;
  }
  /* md-text-button defaults to a 40px-tall container -- once the row's text fields were
     compacted, this became the tallest thing in the row and the actual floor on row height.
     30px still comfortably fits the button's own label text/click target. */
  .property-grid .actions-cell md-text-button {
    --md-text-button-container-height: 30px;
  }
  /* No icon font is vendored in this app (see ntrloc-item-detail.js's chevron-SVG comment) --
     a small stroked inline SVG instead of an <md-text-button>"Delete" label, matching
     ntrloc-links-table.js's identical icon-button treatment for its own Delete action. */
  .property-grid .icon-button {
    display: inline-flex;
    align-items: center;
    justify-content: center;
    width: 30px;
    height: 30px;
    padding: 0;
    background: none;
    border: none;
    border-radius: 6px;
    color: var(--muted);
    cursor: pointer;
  }
  .property-grid .icon-button:hover {
    background: var(--panel-bg);
    color: var(--deleted-color, #f85149);
  }
  .add-property-row {
    margin-top: 8px;
  }
  .property-grid .filter-row .grid-cell {
    padding-bottom: 6px;
  }
  .property-grid .filter-cell {
    position: relative;
  }
  .property-grid .filter-input {
    width: 100%;
    box-sizing: border-box;
    padding: 4px 24px 4px 6px;
    font-size: 13px;
    font-family: inherit;
    background: var(--panel-bg);
    border: 1px solid var(--border);
    border-radius: 4px;
    color: var(--text);
    outline: none;
  }
  .property-grid .filter-input:focus {
    border-color: var(--accent);
  }
  .property-grid .filter-clear-button {
    position: absolute;
    right: 4px;
    top: 50%;
    transform: translateY(-50%);
    background: none;
    border: none;
    color: var(--muted);
    cursor: pointer;
    font-size: 13px;
    line-height: 1;
    padding: 2px 4px;
  }
  .property-grid .filter-clear-button:hover {
    color: var(--text);
  }
  .property-grid .filter-empty-status {
    grid-column: 1 / -1;
  }
  /* Nested-property rows (children of an OBJECT property) reuse the same grid row shape as a
     top-level property -- indentation is just left padding on the name cell, not a separate
     nested grid, so a child's Name/Description/Type/etc. columns still line up with every other
     row's. */
  .property-grid .name-cell {
    display: flex;
    align-items: flex-start;
    gap: 4px;
  }
  .property-grid .name-cell-fields {
    flex: 1;
    min-width: 0;
  }
  /* Same inline-SVG chevron as ntrloc-item-detail.js's panel-header sections (see that file's own
     comment on why: no icon font is vendored here) -- sized down to fit inline in a table row
     rather than a section header. */
  .property-grid .expand-toggle-button {
    display: inline-flex;
    align-items: center;
    justify-content: center;
    width: 16px;
    height: 20px;
    padding: 0;
    background: none;
    border: none;
    color: var(--muted);
    cursor: pointer;
    flex-shrink: 0;
  }
  .property-grid .expand-toggle-button .chevron {
    transition: transform 0.15s ease;
  }
  .property-grid .expand-toggle-button .chevron.collapsed {
    transform: rotate(-90deg);
  }
  .property-grid .expand-toggle-spacer {
    display: inline-block;
    width: 16px;
    flex-shrink: 0;
  }
  .property-grid .add-nested-property-button {
    background: none;
    border: none;
    color: var(--accent);
    font-size: 13px;
    font-weight: 500;
    cursor: pointer;
    padding: 4px 0;
    text-align: left;
  }
  .property-grid .add-nested-property-button:hover {
    text-decoration: underline;
  }
`);

// Maps a read-only-value cell's field class (see render()) to the selector for the control it
// becomes once the row opens for editing -- lets the row-click handler focus whichever field the
// user actually clicked, not always Name. type-select/cardinality-select only actually render in
// some states (see render()'s own conditions), so a miss here falls back to .name-input rather
// than silently focusing nothing.
const PROPERTY_FIELD_FOCUS_TARGETS = {
  'name-value': '.name-input',
  'description-value': '.description-input',
  'type-value': '.type-select',
  'cardinality-value': '.cardinality-select',
  'usage-value': '.usage-select',
};

// Reusable editable property grid -- used both for an item/trait's own properties and for a
// link's nested properties (previously duplicated markup in each place). Given { properties,
// propertyTypes, allowAdd } via the .data setter; mutates the PropertyDefinitionViewModel objects
// in `properties` directly by reference (they belong to whatever ItemDefinitionViewModel/
// TraitDefinitionViewModel/LinkViewModel the caller holds) and calls notifySchemaViewModelChange()
// after every edit -- this component does not call the mutation API itself, matching the
// established "dumb presentation component" convention from before this rewrite.
class NtrlocPropertyTable extends HTMLElement {
  constructor() {
    super();
    // Instance state, not view-model state -- filtering is a display-only concern, same class of
    // trade-off as ntrloc-links-table.js's own _collapsedGroups: it resets if some unrelated edit
    // elsewhere in the panel triggers a full notifySchemaViewModelChange() rebuild (which replaces
    // this component with a fresh instance), but persists correctly across every keystroke within
    // a single filtering session, since typing in the filter itself only ever calls a local
    // this.render(), never notify.
    this._nameFilter = '';
    this._descriptionFilter = '';
    // Clicking any button (Done, Delete, Revert, an expand toggle, ...) while a text field
    // elsewhere in the table still has focus would otherwise blur that field first -- and the
    // field's own 'change' handler (wired per-row in wireUp()) responds to blur by calling
    // this.render(), which tears down and rebuilds the row's whole DOM, including the very button
    // just pressed, before the browser gets to dispatch its 'click' event. Net effect: the first
    // click only unfocuses the field, doing nothing else; a second click, landing cleanly on the
    // freshly-rendered button, is what actually registers. Suppressing the browser's default
    // focus-shift on mousedown keeps the field focused through the press, so no premature
    // re-render happens and the same button receives both mousedown and the click that follows.
    // Bound once here (not per-render in wireUp()) since `this` is the one thing that survives a
    // local render() -- only a full notifySchemaViewModelChange()-triggered external rebuild
    // replaces the whole component, and that path is unaffected either way.
    this.addEventListener('mousedown', (event) => this.commitFocusedInputAndSuppressBlur(event));
  }

  // Since preventDefault() on mousedown stops the field from blurring at all, its own 'change'
  // handler never fires -- so whatever's currently typed has to be committed here instead, the
  // same way that handler would have. Only text fields have this delayed-commit-on-blur problem;
  // a select's own 'change' already fires the instant an option is picked, well before any
  // subsequent button click, so there's nothing pending to lose for those.
  commitFocusedInputAndSuppressBlur(event) {
    if (!event.target.closest('button, md-text-button, md-outlined-button')) return;
    event.preventDefault();
    const focused = document.activeElement;
    const fieldToProp = { 'name-input': 'name', 'description-input': 'description' };
    for (const [fieldClass, propKey] of Object.entries(fieldToProp)) {
      if (!focused?.classList?.contains(fieldClass)) continue;
      const row = focused.closest('.grid-row[data-index]');
      const prop = row && this._visibleRows[Number(row.dataset.index)]?.prop;
      if (prop) prop[propKey] = propKey === 'description' ? (focused.value || null) : focused.value;
      break;
    }
  }

  set data({ properties, propertyTypes, allowAdd }) {
    this._properties = properties || [];
    this._propertyTypes = propertyTypes || [];
    this._allowAdd = !!allowAdd;
    this.render();
  }

  get data() {
    return this._properties || [];
  }

  connectedCallback() {
    this.render();
  }

  hasControlledList(prop) {
    const CONTROLLED_LIST_TYPES = new Set(['STRING', 'INT', 'LONG']);
    return !prop.isNew && prop.controlledListId != null && CONTROLLED_LIST_TYPES.has(prop.type);
  }

  // Flattens the top-level properties named by topLevelIndices, plus -- for any OBJECT property
  // with isExpanded true -- its children, recursively, into a single
  // ordered list of rows: either { prop, containerArray, indexInContainer, depth } for a real
  // property, or { isAddButton: true, parentProp, depth } for the "+ Add property to X" row that
  // follows an expanded object property's own children (at their depth, so it lines up with its
  // siblings -- see the mockup this was built from). This flattening is what makes a nested
  // property row addressable at all: it doesn't live in this._properties, it lives in its
  // parent's own .properties array, so wireUp() needs containerArray (not always
  // this._properties) to know where to splice a still-new row out of on delete.
  //
  // The add-button always appears for an expanded object property, new or already-saved --
  // toCreatePropertySpec/collectNestedPropertyMutations (schema-view-model.js) handle both: a new
  // child of a still-new parent is embedded in the parent's own create mutation, a new child of an
  // existing parent gets its own CREATE_OBJECT_PROPERTY_CHILD. Recursion happens even into a
  // currently-empty object property (nothing to walk, but the add-button still needs to render).
  //
  // Children are always shown in full once their parent is expanded -- the name/description
  // filter only ever narrows which *top-level* rows appear, matching the filter's existing
  // "narrows the property list" framing rather than pruning inside an expanded subtree too.
  collectVisibleRows(topLevelIndices) {
    const rows = [];
    const walk = (indices, containerArray, depth) => {
      for (const index of indices) {
        const prop = containerArray[index];
        rows.push({ prop, containerArray, indexInContainer: index, depth });
        if (prop.type === 'OBJECT' && prop.isExpanded) {
          const childOrder = prop.properties.map((_, i) => i).sort((a, b) => prop.properties[a].name.localeCompare(prop.properties[b].name));
          walk(childOrder, prop.properties, depth + 1);
          rows.push({ isAddButton: true, parentProp: prop, depth: depth + 1 });
        }
      }
    };
    walk(topLevelIndices, this._properties, 0);
    return rows;
  }

  render() {
    const props = this._properties || [];
    // Display order only -- sorts a list of indices into the real (unsorted) storage array
    // rather than the array itself, so this._visibleRows below still refers to the true position
    // in whichever array actually owns each row. Sorting props itself would also risk reshuffling
    // a row's storage position out from under an in-flight edit.
    const displayOrder = props.map((_, index) => index).sort((a, b) => props[a].name.localeCompare(props[b].name));
    // Case-insensitive substring match, independently per column (both must match when both are
    // set). A row currently new or mid-edit is never hidden by its own now-stale match state --
    // without that, adding a property while a filter is active would make it vanish immediately
    // (blank name doesn't match anything), and renaming a row you're actively editing would make
    // it disappear out from under you the moment it stops matching.
    const nameFilter = this._nameFilter.trim().toLowerCase();
    const descriptionFilter = this._descriptionFilter.trim().toLowerCase();
    const filteredOrder = displayOrder.filter((index) => {
      const prop = props[index];
      if (prop.isNew || prop.isEditing) return true;
      const nameMatches = !nameFilter || prop.name.toLowerCase().includes(nameFilter);
      const descriptionMatches = !descriptionFilter || (prop.description ?? '').toLowerCase().includes(descriptionFilter);
      return nameMatches && descriptionMatches;
    });
    // Recomputed fresh every render (expand/collapse, filtering, and edits all call render()) --
    // wireUp() below reads this to resolve each rendered row's data-index back to its real prop.
    this._visibleRows = filteredOrder.length === 0 ? [] : this.collectVisibleRows(filteredOrder);
    this.innerHTML = `
      ${props.length === 0 ? '<p class="status">No properties defined.</p>' : `
        <div class="property-grid" role="grid" aria-label="Properties">
          <div class="grid-row filter-row" role="row">
            <div class="grid-cell" role="gridcell"></div>
            <div class="grid-cell filter-cell" role="gridcell">
              <input type="text" class="filter-input name-filter-input" placeholder="Filter by name" value="${escapeHtml(this._nameFilter)}" />
              ${this._nameFilter ? '<button class="filter-clear-button name-filter-clear" title="Clear filter" aria-label="Clear name filter">✕</button>' : ''}
            </div>
            <div class="grid-cell filter-cell" role="gridcell">
              <input type="text" class="filter-input description-filter-input" placeholder="Filter by description" value="${escapeHtml(this._descriptionFilter)}" />
              ${this._descriptionFilter ? '<button class="filter-clear-button description-filter-clear" title="Clear filter" aria-label="Clear description filter">✕</button>' : ''}
            </div>
            <div class="grid-cell" role="gridcell"></div>
            <div class="grid-cell" role="gridcell"></div>
            <div class="grid-cell" role="gridcell"></div>
            <div class="grid-cell" role="gridcell"></div>
          </div>
          <div class="grid-row" role="row">
            <div class="grid-header" role="columnheader"></div>
            <div class="grid-header" role="columnheader">Name</div>
            <div class="grid-header" role="columnheader">Description</div>
            <div class="grid-header" role="columnheader">Type</div>
            <div class="grid-header" role="columnheader">Cardinality</div>
            <div class="grid-header" role="columnheader">Usage</div>
            <div class="grid-header" role="columnheader"></div>
          </div>
          ${this._visibleRows.length === 0 ? '<p class="status filter-empty-status">No properties match the current filter.</p>' : this._visibleRows.map((row, index) => {
            if (row.isAddButton) {
              return `
              <div class="grid-row add-child-property-row" role="row" data-index="${index}">
                <div class="grid-cell" role="gridcell"></div>
                <div class="grid-cell name-cell" role="gridcell" style="padding-left: ${row.depth * 20}px">
                  <span class="expand-toggle-spacer"></span>
                  <button class="add-nested-property-button">+ Add property to ${escapeHtml(row.parentProp.name)}</button>
                </div>
                <div class="grid-cell" role="gridcell"></div>
                <div class="grid-cell" role="gridcell"></div>
                <div class="grid-cell" role="gridcell"></div>
                <div class="grid-cell" role="gridcell"></div>
                <div class="grid-cell" role="gridcell"></div>
              </div>
            `;
            }
            const { prop, depth } = row;
            const editable = !prop.isDeleted && !prop.isReadonly;
            const showForm = editable && prop.isEditing;
            const isObjectType = prop.type === 'OBJECT';
            const isExpanded = isObjectType && prop.isExpanded;
            // Every row reserves the same gutter width, whether or not it actually has a twisty
            // -- otherwise a scalar row's name would start further left than an OBJECT sibling's
            // at the same depth (the sibling's twisty pushes its name over), leaving property
            // labels raggedly unaligned instead of lining up column-straight like every other
            // field in the grid.
            const toggleHtml = isObjectType ? `
              <button class="expand-toggle-button" title="${isExpanded ? 'Collapse' : 'Expand'}" aria-label="${isExpanded ? 'Collapse' : 'Expand'} ${escapeHtml(prop.name)}" aria-expanded="${isExpanded}">
                <svg class="chevron ${isExpanded ? '' : 'collapsed'}" viewBox="0 0 24 24" width="14" height="14"
                     fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round">
                  <polyline points="6 9 12 15 18 9"></polyline>
                </svg>
              </button>
            ` : '<span class="expand-toggle-spacer"></span>';
            return `
            <div class="grid-row ${prop.isDeleted ? 'row-deleted' : ''} ${editable && !prop.isEditing ? 'row-clickable' : ''}" role="row" data-index="${index}" data-depth="${depth}">
              <div class="grid-cell" role="gridcell">${prop.isDirty ? `<span class="prop-grid-dirty-dot ${prop.isNew ? 'is-new' : ''} ${prop.isDeleted ? 'is-deleted' : ''}">●</span>` : ''}</div>
              <div class="grid-cell name-cell" role="gridcell" style="padding-left: ${depth * 20}px">
                ${toggleHtml}
                <div class="name-cell-fields">
                  ${showForm
                    ? `<md-filled-text-field class="editable-field name-input" value="${escapeHtml(prop.name)}"></md-filled-text-field>`
                    : `<span class="read-only-value name-value">${escapeHtml(prop.name)}</span>`}
                  ${!prop.isNew && prop.name !== prop.originalName && prop.originalName ? `<div class="original-value">${escapeHtml(prop.originalName)}</div>` : ''}
                  ${prop.definedIn ? `<span class="defined-in-badge">via ${escapeHtml(prop.definedIn.entityName)}</span>` : ''}
                </div>
              </div>
              <div class="grid-cell" role="gridcell">
                ${showForm
                  ? `<md-filled-text-field class="editable-field description-input" value="${escapeHtml(prop.description ?? '')}"></md-filled-text-field>`
                  : `<span class="read-only-value description-value">${escapeHtml(prop.description ?? '')}</span>`}
                ${!prop.isNew && prop.description !== prop.originalDescription && prop.originalDescription ? `<div class="original-value">${escapeHtml(prop.originalDescription)}</div>` : ''}
              </div>
              <div class="grid-cell" role="gridcell">
                ${prop.isNew && showForm ? `
                  <md-filled-select class="editable-field type-select">
                    ${this._propertyTypes.map((t) => `<md-select-option value="${t.type}" ${t.type === prop.type ? 'selected' : ''}><div slot="headline">${t.type}</div></md-select-option>`).join('')}
                  </md-filled-select>
                ` : `<span class="read-only-value type-value">${escapeHtml(prop.type)}</span>`}
              </div>
              <div class="grid-cell" role="gridcell">
                ${prop.validCardinalities.length > 1 && showForm ? `
                  <md-filled-select class="editable-field cardinality-select">
                    ${prop.validCardinalities.map((c) => `<md-select-option value="${c}" ${c === prop.cardinality ? 'selected' : ''}><div slot="headline">${c}</div></md-select-option>`).join('')}
                  </md-filled-select>
                ` : `<span class="read-only-value cardinality-value">${escapeHtml(prop.cardinality)}</span>`}
                ${!prop.isNew && prop.cardinality !== prop.originalCardinality && prop.originalCardinality ? `<div class="original-value">${escapeHtml(prop.originalCardinality)}</div>` : ''}
              </div>
              <div class="grid-cell" role="gridcell">
                ${showForm ? `
                  <md-filled-select class="editable-field usage-select">
                    ${['OPTIONAL', 'REQUIRED', 'DEPRECATED'].map((u) => `<md-select-option value="${u}" ${u === prop.usage ? 'selected' : ''}><div slot="headline">${u}</div></md-select-option>`).join('')}
                  </md-filled-select>
                ` : `<span class="read-only-value usage-value">${escapeHtml(prop.usage)}</span>`}
                ${!prop.isNew && prop.usage !== prop.originalUsage && prop.originalUsage ? `<div class="original-value">${escapeHtml(prop.originalUsage)}</div>` : ''}
              </div>
              <div class="grid-cell actions-cell" role="gridcell">
                ${showForm ? '<md-text-button class="done-button">Done</md-text-button>' : ''}
                ${this.hasControlledList(prop) ? '<md-text-button class="list-button">List</md-text-button>' : ''}
                ${!prop.isReadonly ? (
                  prop.isDeleted
                    ? '<md-text-button class="restore-button">Restore</md-text-button>'
                    : `
                      ${prop.ownFieldsDirty && !prop.isNew ? '<md-text-button class="revert-button">Revert</md-text-button>' : ''}
                      <button class="icon-button delete-button" title="Delete" aria-label="Delete">
                        <svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
                          <polyline points="3 6 5 6 21 6"></polyline>
                          <path d="M19 6l-1 14a2 2 0 0 1-2 2H8a2 2 0 0 1-2-2L5 6"></path>
                          <path d="M10 11v6"></path>
                          <path d="M14 11v6"></path>
                          <path d="M9 6V4a1 1 0 0 1 1-1h4a1 1 0 0 1 1 1v2"></path>
                        </svg>
                      </button>
                    `
                ) : ''}
              </div>
            </div>
          `;
          }).join('')}
        </div>
      `}
      ${this._allowAdd ? `
        <div class="add-property-row">
          <md-outlined-button class="add-property-button">+ Add Property</md-outlined-button>
        </div>
      ` : ''}
    `;
    this.wireUp();
  }

  wireUp() {
    // Plain native <input>s, not Material fields -- .focus() is synchronous and immediate, no
    // shadow-DOM-upgrade delay to work around. Still needed at all because this.render() replaces
    // innerHTML on every keystroke (to re-filter live): the input that had focus is destroyed and
    // a new one created with the updated value, so without re-focusing (and restoring the caret
    // position, not just focus) only one character could ever be typed before focus fell out of
    // the field.
    const nameFilterInput = this.querySelector('.name-filter-input');
    if (nameFilterInput) nameFilterInput.addEventListener('input', (event) => {
      const cursorPos = event.target.selectionStart;
      this._nameFilter = event.target.value;
      this.render();
      const newInput = this.querySelector('.name-filter-input');
      if (newInput) {
        newInput.focus();
        newInput.setSelectionRange(cursorPos, cursorPos);
      }
    });

    const nameFilterClear = this.querySelector('.name-filter-clear');
    if (nameFilterClear) nameFilterClear.addEventListener('click', () => {
      this._nameFilter = '';
      this.render();
    });

    const descriptionFilterInput = this.querySelector('.description-filter-input');
    if (descriptionFilterInput) descriptionFilterInput.addEventListener('input', (event) => {
      const cursorPos = event.target.selectionStart;
      this._descriptionFilter = event.target.value;
      this.render();
      const newInput = this.querySelector('.description-filter-input');
      if (newInput) {
        newInput.focus();
        newInput.setSelectionRange(cursorPos, cursorPos);
      }
    });

    const descriptionFilterClear = this.querySelector('.description-filter-clear');
    if (descriptionFilterClear) descriptionFilterClear.addEventListener('click', () => {
      this._descriptionFilter = '';
      this.render();
    });

    this.querySelectorAll('.grid-row[data-index]').forEach((row) => {
      const index = Number(row.dataset.index);
      const visibleRow = this._visibleRows[index];

      if (visibleRow.isAddButton) {
        const addNestedButton = row.querySelector('.add-nested-property-button');
        addNestedButton.addEventListener('click', () => {
          const newProp = PropertyDefinitionViewModel.create(this._propertyTypes);
          visibleRow.parentProp.properties.push(newProp);
          notifySchemaViewModelChange();
          this.focusNewPropertyInput(newProp);
        });
        return; // nothing else in this loop body applies to a synthetic add-button row
      }

      const { prop, containerArray } = visibleRow;

      const expandToggleButton = row.querySelector('.expand-toggle-button');
      if (expandToggleButton) expandToggleButton.addEventListener('click', (event) => {
        event.stopPropagation(); // row itself may also be row-clickable (opens for editing) -- toggling expand shouldn't also open it
        prop.isExpanded = !prop.isExpanded;
        this.render();
      });

      if (row.classList.contains('row-clickable')) {
        row.addEventListener('click', (event) => {
          if (event.target.closest('.actions-cell')) return;
          // Whichever field the click actually landed in gets focused once the row opens, not
          // always Name.
          const clickedValue = event.target.closest('.read-only-value');
          const fieldClass = clickedValue ? [...clickedValue.classList].find((c) => c !== 'read-only-value') : null;
          const preferredSelector = PROPERTY_FIELD_FOCUS_TARGETS[fieldClass] ?? '.name-input';
          prop.isEditing = true;
          this.render();
          // Scoped by data-index, not a positional index into every matching field in the table --
          // these inputs now only exist for rows currently in edit mode (see render()), so with
          // more than one row open at once a plain querySelectorAll(...)[index] would grab the
          // wrong row's input.
          const target = this.querySelector(`.grid-row[data-index="${index}"] ${preferredSelector}`)
            ?? this.querySelector(`.grid-row[data-index="${index}"] .name-input`);
          if (target) Promise.resolve(target.updateComplete).then(() => target.focus());
        });
      }

      const doneButton = row.querySelector('.done-button');
      if (doneButton) doneButton.addEventListener('click', () => {
        prop.isEditing = false;
        this.render();
      });

      const nameInput = row.querySelector('.name-input');
      if (nameInput) nameInput.addEventListener('change', (event) => {
        prop.name = event.target.value;
        this.render();
        notifySchemaViewModelChange();
      });

      const descriptionInput = row.querySelector('.description-input');
      if (descriptionInput) descriptionInput.addEventListener('change', (event) => {
        prop.description = event.target.value || null;
        this.render();
        notifySchemaViewModelChange();
      });

      const typeSelect = row.querySelector('.type-select');
      if (typeSelect) typeSelect.addEventListener('change', (event) => {
        prop.updateType(event.target.value, this._propertyTypes);
        // Expanded by default the moment a property becomes OBJECT-typed -- the user picking
        // OBJECT is almost always immediately followed by adding its first child, so the "Add
        // property to X" affordance (only shown while expanded) should already be visible.
        if (prop.type === 'OBJECT') prop.isExpanded = true;
        this.render();
        notifySchemaViewModelChange();
      });

      const cardinalitySelect = row.querySelector('.cardinality-select');
      if (cardinalitySelect) cardinalitySelect.addEventListener('change', (event) => {
        prop.cardinality = event.target.value;
        this.render();
        notifySchemaViewModelChange();
      });

      const usageSelect = row.querySelector('.usage-select');
      if (usageSelect) usageSelect.addEventListener('change', (event) => {
        prop.usage = event.target.value;
        this.render();
        notifySchemaViewModelChange();
      });

      const listButton = row.querySelector('.list-button');
      if (listButton) listButton.addEventListener('click', async () => {
        const pending = schemaViewModel.pendingControlledListReplacements.get(prop.id) ?? null;
        const result = await openControlledListDialog(prop, pending);
        if (result !== undefined) {
          schemaViewModel.setPendingControlledList(prop.id, result);
        }
      });

      const revertButton = row.querySelector('.revert-button');
      if (revertButton) revertButton.addEventListener('click', () => {
        prop.revert();
        this.render();
        notifySchemaViewModelChange();
      });

      const deleteButton = row.querySelector('.delete-button');
      if (deleteButton) deleteButton.addEventListener('click', () => {
        if (prop.isNew) {
          // containerArray, not always this._properties -- a nested child that was never saved
          // lives in its parent's own .properties array.
          const idx = containerArray.indexOf(prop);
          if (idx !== -1) containerArray.splice(idx, 1);
        } else {
          prop.isDeleted = true;
        }
        this.render();
        notifySchemaViewModelChange();
      });

      const restoreButton = row.querySelector('.restore-button');
      if (restoreButton) restoreButton.addEventListener('click', () => {
        prop.isDeleted = false;
        this.render();
        notifySchemaViewModelChange();
      });
    });

    const addButton = this.querySelector('.add-property-button');
    if (addButton) addButton.addEventListener('click', () => {
      const newProp = PropertyDefinitionViewModel.create(this._propertyTypes);
      this._properties.push(newProp);
      notifySchemaViewModelChange();
      this.focusNewPropertyInput(newProp);
    });
  }

  // notifySchemaViewModelChange() is not fire-and-forget here -- every subscriber (currently just
  // ntrloc-schema-editor.js) reacts to it by synchronously rebuilding its entire innerHTML, which
  // tears down and recreates this component's own DOM subtree (a fresh <ntrloc-property-table>,
  // fresh rows) before this line returns. Capturing "the new row's input" as a DOM node beforehand
  // and focusing it afterward -- even after awaiting its updateComplete -- silently does nothing,
  // because by the time that promise resolves the captured node is already disconnected; focus()
  // on a disconnected element is a no-op, not an error, which is why this looked like it worked
  // but never actually moved focus. Fixed by re-finding the row from scratch, from `document`,
  // only after the rebuild has already happened -- searching every ntrloc-property-table's
  // *current* _visibleRows for the same newProp object (identity, not value, since a blank name
  // isn't unique) rather than assuming this instance's own subtree still exists. Searches
  // _visibleRows, not .data/this._properties directly, since data-index is now a position in the
  // flattened (top-level + expanded children) row list, not a raw array index -- see
  // collectVisibleRows' own comment. Shared by both the top-level "+ Add Property" button and
  // every nested "+ Add property to X" button, since both create a new row the same way and need
  // the identical re-find-after-rebuild dance.
  focusNewPropertyInput(newProp) {
    for (const table of document.querySelectorAll('ntrloc-property-table')) {
      const index = (table._visibleRows ?? []).findIndex((row) => row.prop === newProp);
      if (index === -1) continue;
      // Scoped by data-index -- see the row-click handler's own comment above for why a
      // positional index into every .name-input no longer works now that it's conditional on
      // edit mode.
      const target = table.querySelector(`.grid-row[data-index="${index}"] .name-input`);
      if (target) Promise.resolve(target.updateComplete).then(() => target.focus());
      break;
    }
  }
}

function escapeHtml(value) {
  const div = document.createElement('div');
  div.textContent = String(value ?? '');
  return div.innerHTML;
}

customElements.define('ntrloc-property-table', NtrlocPropertyTable);
